package com.kermond.ebook2tts.engine

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.InterruptedIOException
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** 在线合成结果（可读摘要一律截断 200 字） */
sealed class OnlineOutcome {
    /** @param ttfbMs 首块 PCM 相对调用发起的耗时（-1 = 未知） */
    data class Ok(val bytes: Int, val chunks: Int, val ttfbMs: Long) : OnlineOutcome()

    /** @param reason 打点用短标记（http_401/timeout/network/empty/...）；@param summary 可读摘要（≤200 字） */
    data class Failed(val kind: OnlineFailureKind, val reason: String, val summary: String) : OnlineOutcome()

    /** 调用方取消 / 停止（静默退出，不产生 error 回声） */
    object Cancelled : OnlineOutcome()
}

/**
 * MiMo 在线合成后端（协议**复用**甲方原项目 `MiMoApiClient`，不自创）：
 * OpenAI 兼容 `POST {base}/chat/completions`，TTS 走 `messages` + `audio{format,voice}`；
 * 流式为 SSE，逐块取 `choices[0].delta.audio.data`（base64 PCM16，24kHz 单声道）。
 *
 * 纪律：
 * - **网络 IO 一律在本后端自己的工作线程**（线程池内部线程；调用方合成线程只做等待，零 IO——AR-§4.8.4/红线 7）；
 * - 取消 = 关闭在途连接（OkHttp `Call.cancel()`），调用线程 ≤1 个 tick（50ms）内感知；
 * - 错误摘要一律 `.take(200)`；异常/畸形 SSE 行跳过，绝不因单行坏数据中断整段。
 *
 * 本类不做进程/生命周期管理：仅在 `:tts_service` 内使用（红线 4）。
 */
class OnlineBackend(
    private val client: OkHttpClient = defaultClient(),
    io: ExecutorService? = null,
) {
    /** 网络线程池：按需创建（在线从未使用时零线程）、守护线程、空闲 60s 自动回收 */
    private val io: ExecutorService = io ?: Executors.newCachedThreadPool { r ->
        Thread(r, "mimo-online").apply { isDaemon = true }
    }

    /**
     * 流式合成一条文本。**阻塞当前线程**直至完成/失败/取消；真正的读写在工作线程，
     * 每解出一个 PCM16 片段即回调 [onPcm]（返回 false = 调用方要求停止）。
     */
    fun synthesize(
        request: OnlineRequest,
        text: String,
        onPcm: (ByteArray) -> Boolean,
        isCancelled: () -> Boolean,
    ): OnlineOutcome {
        val callRef = AtomicReference<Call?>(null)
        val job = try {
            io.submit(Callable { runStream(request, text, onPcm, isCancelled, callRef) })
        } catch (_: RejectedExecutionException) {
            return OnlineOutcome.Failed(OnlineFailureKind.NETWORK, "pool_shutdown", "在线线程池不可用")
        }
        while (true) {
            if (isCancelled()) {
                callRef.get()?.cancel()
                return OnlineOutcome.Cancelled
            }
            try {
                return job.get(CANCEL_TICK_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // 常规轮询：继续等待（取消响应 ≤1 tick）
            } catch (e: ExecutionException) {
                val cause = e.cause
                val summary = (cause?.message ?: cause?.javaClass?.simpleName ?: "worker_error").take(200)
                return OnlineOutcome.Failed(OnlineFailureKind.NETWORK, "worker", summary)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                callRef.get()?.cancel()
                return OnlineOutcome.Cancelled
            }
        }
    }

    /**
     * 校验密钥有效性（协议复用：`GET {base}/models`）。
     * @return null = 通过；否则平台方错误摘要（≤200 字）。网络 IO 在工作线程。
     */
    fun validateKey(request: OnlineRequest): String? {
        val job = try {
            io.submit(Callable {
                val url = request.baseUrl.trimEnd('/') + "/models"
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${request.apiKey}")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) null else ("HTTP ${resp.code}: $body").take(200)
                }
            })
        } catch (_: RejectedExecutionException) {
            return "在线线程池不可用"
        }
        return try {
            job.get()
        } catch (e: ExecutionException) {
            (e.cause?.message ?: "网络错误").take(200)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            job.cancel(true)
            "已取消"
        }
    }

    // ---- 工作线程侧 ----

    private fun runStream(
        request: OnlineRequest,
        text: String,
        onPcm: (ByteArray) -> Boolean,
        isCancelled: () -> Boolean,
        callRef: AtomicReference<Call?>,
    ): OnlineOutcome {
        val t0 = System.currentTimeMillis()
        return try {
            val url = request.baseUrl.trimEnd('/') + "/chat/completions"
            val body = buildTtsPayload(text, request)
                .toRequestBody(JSON_MEDIA)
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${request.apiKey}")
                .header("Accept", "text/event-stream")
                .post(body)
                .build()
            val call = client.newCall(req)
            callRef.set(call)
            if (isCancelled()) {
                call.cancel()
                return OnlineOutcome.Cancelled
            }
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val raw = resp.body?.string().orEmpty()
                    val kind = when (resp.code) {
                        401, 403 -> OnlineFailureKind.AUTH
                        429 -> OnlineFailureKind.RATE_LIMIT
                        else -> OnlineFailureKind.HTTP
                    }
                    return OnlineOutcome.Failed(kind, "http_${resp.code}", "HTTP ${resp.code}: $raw".take(200))
                }
                val source = resp.body?.source()
                    ?: return OnlineOutcome.Failed(OnlineFailureKind.NETWORK, "empty_body", "响应无内容")
                var firstAtMs = 0L
                var bytes = 0
                var chunks = 0
                var carry: Byte? = null // PCM16 帧对齐：奇数尾字节暂存并与下一块拼接
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
                    if (firstAtMs == 0L) firstAtMs = System.currentTimeMillis()
                    bytes += pcm.size
                    chunks++
                    if (!onPcm(pcm)) return OnlineOutcome.Cancelled
                }
                if (bytes > 0) {
                    OnlineOutcome.Ok(bytes, chunks, if (firstAtMs > 0L) firstAtMs - t0 else -1L)
                } else {
                    OnlineOutcome.Failed(OnlineFailureKind.EMPTY, "empty", "流结束但未收到音频数据")
                }
            }
        } catch (t: Throwable) {
            if (isCancelled() || callRef.get()?.isCanceled() == true) {
                OnlineOutcome.Cancelled
            } else {
                val reason = if (t is InterruptedIOException) "timeout" else "network"
                OnlineOutcome.Failed(
                    OnlineFailureKind.NETWORK,
                    reason,
                    (t.message ?: t.javaClass.simpleName).take(200),
                )
            }
        }
    }

    /** 单行 SSE → PCM16；畸形行/无音频增量 → null（跳过，不中断） */
    private fun decodeChunk(data: String): ByteArray? = try {
        val b64 = JSONObject(data)
            .optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("delta")?.optJSONObject("audio")
            ?.optString("data")
        if (b64.isNullOrBlank()) null else Base64.getDecoder().decode(b64)
    } catch (_: Exception) {
        null
    }

    /**
     * TTS 载荷（逐字复用原项目 `buildTtsPayload` 语义）：
     * 内置音色 → `audio.voice`；定制音色模型（model 含 voicedesign）→ user 消息即音色/风格描述。
     */
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
        /** 取消轮询间隔：调用方停止后 ≤50ms 感知并关闭在途连接 */
        private const val CANCEL_TICK_MS = 50L

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** 超时口径与原项目 MiMoApiClient 一致（连接 15s / 读 60s / 写 30s） */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * 进程内共享的在线后端实例（协调器与试听共用；线程为守护线程，进程退出即回收）。
 * 在线从未使用时零线程；OkHttp 客户端本身线程安全、支持并发调用。
 */
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
