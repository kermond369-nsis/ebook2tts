package com.kermond.ebook2tts.engine

import com.kermond.ebook2tts.core.TtsProviderKind
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.InterruptedIOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenAI `/v1/audio/speech` 兼容在线 TTS 适配器。
 * 兼容 OpenAI、SiliconFlow（CosyVoice2/IndexTTS）、Fish-Speech、Kokoro-FastAPI 等标准协议端点。
 */
class OpenAiCompatTtsProvider(
    private val client: OkHttpClient,
    private val io: ExecutorService,
) : TtsProvider {

    override val kind: TtsProviderKind = TtsProviderKind.OPENAI_COMPAT

    override fun synthesize(
        request: OnlineRequest,
        text: String,
        speed: Float,
        onChunk: (TtsAudioChunk) -> Boolean,
        isCancelled: () -> Boolean,
    ): OnlineOutcome {
        val callRef = AtomicReference<Call?>(null)
        val job = try {
            io.submit(Callable { runSpeech(request, text, speed, onChunk, isCancelled, callRef) })
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
                // 等待结果的同时保持 ≤50ms 取消响应
            } catch (e: ExecutionException) {
                val cause = e.cause
                val summary = ErrorSanitizer.sanitize(cause?.message ?: cause?.javaClass?.simpleName ?: "worker_error")
                return OnlineOutcome.Failed(OnlineFailureKind.NETWORK, "worker", summary)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                callRef.get()?.cancel()
                return OnlineOutcome.Cancelled
            }
        }
    }

    override fun validate(request: OnlineRequest): String? {
        val base = request.baseUrl.trim().trimEnd('/')
        if (!EndpointUrlPolicy.isAllowed(base)) return "接口地址必须以 https:// 开头"
        val job = try {
            io.submit(Callable {
                val modelsBuilder = Request.Builder().url("$base/models").get()
                if (request.apiKey.isNotBlank()) {
                    modelsBuilder.header("Authorization", "Bearer ${request.apiKey}")
                }
                client.newCall(modelsBuilder.build()).execute().use { resp ->
                    if (resp.isSuccessful) return@Callable null
                    if (resp.code == 401 || resp.code == 403) {
                        val body = resp.body?.string().orEmpty()
                        return@Callable ErrorSanitizer.sanitize("HTTP ${resp.code}: $body")
                    }
                    // 部分轻量化自建服务未实现 GET /models（返回 404/405）：直接以最小文本探测 /audio/speech
                    if (resp.code != 404 && resp.code != 405 && resp.code != 501) {
                        val body = resp.body?.string().orEmpty()
                        return@Callable ErrorSanitizer.sanitize("HTTP ${resp.code}: $body")
                    }
                }
                val speechReq = buildSpeechHttpRequest(base, request, "测试", 1.0f)
                client.newCall(speechReq).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        return@Callable ErrorSanitizer.sanitize("HTTP ${resp.code}: $body")
                    }
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    if (bytes.isEmpty()) "服务端返回空音频" else null
                }
            })
        } catch (_: RejectedExecutionException) {
            return "在线线程池不可用"
        }
        return try {
            job.get()
        } catch (e: ExecutionException) {
            ErrorSanitizer.sanitize(e.cause?.message ?: "网络错误")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            job.cancel(true)
            "已取消"
        }
    }

    private fun runSpeech(
        request: OnlineRequest,
        text: String,
        speed: Float,
        onChunk: (TtsAudioChunk) -> Boolean,
        isCancelled: () -> Boolean,
        callRef: AtomicReference<Call?>,
    ): OnlineOutcome {
        val t0 = System.currentTimeMillis()
        val base = request.baseUrl.trim().trimEnd('/')
        if (!EndpointUrlPolicy.isAllowed(base)) {
            return OnlineOutcome.Failed(OnlineFailureKind.HTTP, "invalid_url", "接口地址必须以 https:// 开头")
        }
        return try {
            val req = buildSpeechHttpRequest(base, request, text, speed)
            val call = client.newCall(req)
            callRef.set(call)
            if (isCancelled()) {
                call.cancel()
                return OnlineOutcome.Cancelled
            }
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val raw = resp.body?.string().orEmpty()
                    val failKind = when (resp.code) {
                        401, 403 -> OnlineFailureKind.AUTH
                        429 -> OnlineFailureKind.RATE_LIMIT
                        else -> OnlineFailureKind.HTTP
                    }
                    return OnlineOutcome.Failed(failKind, "http_${resp.code}", ErrorSanitizer.sanitize("HTTP ${resp.code}: $raw"))
                }
                val body = resp.body
                    ?: return OnlineOutcome.Failed(OnlineFailureKind.NETWORK, "empty_body", "响应无内容")
                val defaultSr = if (request.sampleRate > 0) request.sampleRate else kind.defaultSampleRate
                val res = body.byteStream().use { stream ->
                    AudioStreamDecoder.decodeStream(
                        input = stream,
                        formatHint = request.responseFormat,
                        contentType = resp.header("Content-Type"),
                        defaultSampleRate = defaultSr,
                        onChunk = onChunk,
                        isCancelled = isCancelled,
                    )
                }
                when {
                    res.cancelled || isCancelled() -> OnlineOutcome.Cancelled
                    res.bytes > 0 -> OnlineOutcome.Ok(
                        bytes = res.bytes,
                        chunks = res.chunks,
                        ttfbMs = if (res.firstAtMs > 0L) res.firstAtMs - t0 else -1L,
                    )
                    res.error != null -> OnlineOutcome.Failed(
                        OnlineFailureKind.EMPTY,
                        "decode_error",
                        ErrorSanitizer.sanitize(res.error),
                    )
                    else -> OnlineOutcome.Failed(OnlineFailureKind.EMPTY, "empty", "流结束但未收到音频数据")
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
                    ErrorSanitizer.sanitize(t.message ?: t.javaClass.simpleName),
                )
            }
        }
    }

    private fun buildSpeechHttpRequest(
        base: String,
        request: OnlineRequest,
        text: String,
        speed: Float,
    ): Request {
        val fmt = request.responseFormat.trim().lowercase().ifEmpty { "pcm" }
        val payload = JSONObject()
            .put("model", request.model.ifBlank { kind.defaultModel })
            .put("input", text)
            .put("voice", request.voice.ifBlank { kind.defaultVoice })
            .put("speed", kotlin.math.round(speed.coerceIn(0.25f, 4.0f) * 100.0) / 100.0)
        if (fmt != "auto") {
            payload.put("response_format", fmt)
        }
        val builder = Request.Builder()
            .url("$base/audio/speech")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
        if (request.apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer ${request.apiKey}")
        }
        return builder.build()
    }

    private companion object {
        private const val CANCEL_TICK_MS = 50L
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
