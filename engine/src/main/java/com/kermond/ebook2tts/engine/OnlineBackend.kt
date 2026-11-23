package com.kermond.ebook2tts.engine

import com.kermond.ebook2tts.core.OnlineSettings
import com.kermond.ebook2tts.core.TtsProviderKind
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 在线合成结果（可读摘要一律截断 200 字） */
sealed class OnlineOutcome {
    /** @param ttfbMs 首块 PCM 相对调用发起的耗时（-1 = 未知） */
    data class Ok(val bytes: Int, val chunks: Int, val ttfbMs: Long) : OnlineOutcome()

    /** @param reason 打点用短标记（http_401/timeout/network/empty/...）；@param summary 可读摘要（≤200 字） */
    data class Failed(val kind: OnlineFailureKind, val reason: String, val summary: String) : OnlineOutcome()

    /** 调用方取消 / 停止（静默退出） */
    object Cancelled : OnlineOutcome()
}

/**
 * MiMo 与多引擎统一在线合成后端：
 * - 支持小米 MiMo SSE 流式 PCM16（24kHz 单声道）；
 * - 支持 OpenAI `/v1/audio/speech` 兼容接口；
 * - 支持自定义 HTTP JSON 模板；
 * - 内置弱网首包硬超时看门狗，超时立即断连返回 [OnlineFailureKind.TIMEOUT] 以便上层毫秒级回退系统 TTS。
 */
class OnlineBackend(
    private val client: OkHttpClient = defaultClient(),
    io: ExecutorService? = null,
) {
    /** 网络线程池：按需创建、守护线程、空闲 60s 自动回收 */
    private val io: ExecutorService = io ?: Executors.newCachedThreadPool { r ->
        Thread(r, "tts-online").apply { isDaemon = true }
    }

    private val openAiProvider = OpenAiCompatTtsProvider(client, this.io)
    private val customHttpProvider = CustomHttpTtsProvider(client, this.io)

    /**
     * 多引擎统一流式合成入口：根据 [OnlineRequest.providerKind] 调度 MiMo / OpenAI 兼容 / 自定义 HTTP。
     */
    fun synthesizeChunks(
        request: OnlineRequest,
        text: String,
        speed: Float = 1.0f,
        onChunk: (TtsAudioChunk) -> Boolean,
        isCancelled: () -> Boolean,
    ): OnlineOutcome = when (request.providerKind) {
        TtsProviderKind.MIMO -> {
            val sr = if (request.sampleRate > 0) request.sampleRate else OnlineSettings.SAMPLE_RATE
            synthesize(
                request = request,
                text = text,
                onPcm = { pcm -> onChunk(TtsAudioChunk(pcm, sr)) },
                isCancelled = isCancelled,
            )
        }
        TtsProviderKind.OPENAI_COMPAT ->
            openAiProvider.synthesize(request, text, speed, onChunk, isCancelled)
        TtsProviderKind.CUSTOM_HTTP ->
            customHttpProvider.synthesize(request, text, speed, onChunk, isCancelled)
    }

    /** 统一连通性与密钥校验入口 */
    fun validate(request: OnlineRequest): String? = when (request.providerKind) {
        TtsProviderKind.MIMO -> validateKey(request)
        TtsProviderKind.OPENAI_COMPAT -> openAiProvider.validate(request)
        TtsProviderKind.CUSTOM_HTTP -> customHttpProvider.validate(request)
    }

    /** MiMo 流式合成一条文本 */
    fun synthesize(
        request: OnlineRequest,
        text: String,
        onPcm: (ByteArray) -> Boolean,
        isCancelled: () -> Boolean,
    ): OnlineOutcome {
        val firstByteSeen = AtomicBoolean(false)
        return OnlineCallRunner.executeStream(
            io = io,
            timeoutMs = request.timeoutMs,
            firstByteSeen = firstByteSeen,
            isCancelled = isCancelled,
        ) { callRef ->
            runStream(request, text, onPcm, firstByteSeen, isCancelled, callRef)
        }
    }

    /** 校验 MiMo 密钥有效性（`GET {base}/models`） */
    fun validateKey(request: OnlineRequest): String? = OnlineCallRunner.executeValidation(io) {
        val url = request.baseUrl.trimEnd('/') + "/models"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${request.apiKey}")
            .get()
            .build()
        val validateClient = OnlineCallRunner.clientForTimeout(client, 15_000)
        validateClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) null else ErrorSanitizer.sanitize("HTTP ${resp.code}: $body")
        }
    }

    private fun runStream(
        request: OnlineRequest,
        text: String,
        onPcm: (ByteArray) -> Boolean,
        firstByteSeen: AtomicBoolean,
        isCancelled: () -> Boolean,
        callRef: AtomicReference<Call?>,
    ): OnlineOutcome {
        val t0 = System.currentTimeMillis()
        val streamClient = OnlineCallRunner.clientForTimeout(client, request.timeoutMs)
        return try {
            val url = request.baseUrl.trimEnd('/') + "/chat/completions"
            val body = buildTtsPayload(text, request).toRequestBody(JSON_MEDIA)
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${request.apiKey}")
                .header("Accept", "text/event-stream")
                .post(body)
                .build()
            val call = streamClient.newCall(req)
            callRef.set(call)
            if (isCancelled()) {
                call.cancel()
                return OnlineOutcome.Cancelled
            }
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val raw = resp.body?.string().orEmpty()
                    return OnlineCallRunner.httpFailure(resp.code, raw)
                }
                val source = resp.body?.source()
                    ?: return OnlineOutcome.Failed(OnlineFailureKind.NETWORK, "empty_body", "响应无内容")
                class Stats {
                    var firstAtMs = 0L
                    var bytes = 0
                    var chunks = 0
                }
                val st = Stats()
                var carry: Byte? = null
                while (true) {
                    if (isCancelled()) return OnlineOutcome.Cancelled
                    val line = source.readUtf8Line() ?: break
                    if (line.isEmpty() || !line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    var pcm = decodeChunk(data) ?: continue
                    if (pcm.isEmpty()) continue
                    val prev = carry
                    if (prev != null) {
                        pcm = byteArrayOf(prev, *pcm)
                        carry = null
                    }
                    if (pcm.size % 2 != 0) {
                        carry = pcm[pcm.size - 1]
                        pcm = pcm.copyOfRange(0, pcm.size - 1)
                    }
                    if (pcm.isEmpty()) continue
                    firstByteSeen.set(true)
                    if (st.firstAtMs == 0L) st.firstAtMs = System.currentTimeMillis()
                    st.bytes += pcm.size
                    st.chunks++
                    if (!onPcm(pcm)) return OnlineOutcome.Cancelled
                }
                if (st.bytes > 0) {
                    OnlineOutcome.Ok(
                        st.bytes,
                        st.chunks,
                        if (st.firstAtMs > 0L) st.firstAtMs - t0 else -1L,
                    )
                } else {
                    OnlineOutcome.Failed(OnlineFailureKind.EMPTY, "empty", "流结束但未收到音频数据")
                }
            }
        } catch (t: Throwable) {
            OnlineCallRunner.throwableFailure(t, isCancelled, callRef.get())
        }
    }

    /** 单行 SSE → PCM16；畸形行/无音频增量 → null */
    private fun decodeChunk(data: String): ByteArray? = try {
        val b64 = JSONObject(data)
            .optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("delta")?.optJSONObject("audio")
            ?.optString("data")
        if (b64.isNullOrBlank()) null else Base64.getDecoder().decode(b64)
    } catch (_: Exception) {
        null
    }

    private fun buildTtsPayload(text: String, request: OnlineRequest): String {
        val messages = JSONArray()
        val useDesign = request.model.contains("voicedesign")
        if (useDesign) {
            messages.put(JSONObject().put("role", "user").put("content", request.style.ifBlank { "沉稳男声" }))
            messages.put(JSONObject().put("role", "assistant").put("content", text))
        } else {
            if (request.style.isNotBlank()) {
                messages.put(JSONObject().put("role", "user").put("content", request.style))
            }
            messages.put(JSONObject().put("role", "assistant").put("content", text))
        }
        val audio = JSONObject().put("format", "pcm16")
        if (!useDesign && request.voice.isNotBlank()) {
            audio.put("voice", request.voice)
        }
        return JSONObject()
            .put("model", request.model)
            .put("messages", messages)
            .put("audio", audio)
            .put("stream", true)
            .toString()
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/** 进程内共享在线后端实例 */
object OnlineBackendHolder {
    @Volatile
    private var instance: OnlineBackend? = null

    fun get(): OnlineBackend {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val created = OnlineBackend()
            instance = created
            return created
        }
    }
}
