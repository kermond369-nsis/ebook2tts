package com.kermond.ebook2tts.engine

import com.kermond.ebook2tts.core.CustomHttpTemplate
import com.kermond.ebook2tts.core.TtsProviderKind
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InterruptedIOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * 自定义 HTTP 模板 TTS 适配器：支持任意 JSON POST 转接端点。
 */
class CustomHttpTtsProvider(
    private val client: OkHttpClient,
    private val io: ExecutorService,
) : TtsProvider {

    override val kind: TtsProviderKind = TtsProviderKind.CUSTOM_HTTP

    override fun synthesize(
        request: OnlineRequest,
        text: String,
        speed: Float,
        onChunk: (TtsAudioChunk) -> Boolean,
        isCancelled: () -> Boolean,
    ): OnlineOutcome {
        val callRef = AtomicReference<Call?>(null)
        val job = try {
            io.submit(Callable { runCustom(request, text, speed, onChunk, isCancelled, callRef) })
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
        val url = request.baseUrl.trim()
        if (!url.startsWith("https://")) return "自定义接口地址必须以 https:// 开头"
        val job = try {
            io.submit(Callable {
                val req = buildHttpRequest(url, request, "测试", 1.0f)
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        return@Callable ErrorSanitizer.sanitize("HTTP ${resp.code}: $body")
                    }
                    val body = resp.body ?: return@Callable "响应无内容"
                    val defaultSr = if (request.sampleRate > 0) request.sampleRate else kind.defaultSampleRate
                    val res = body.byteStream().use { stream ->
                        AudioStreamDecoder.decodeStream(
                            input = stream,
                            formatHint = request.responseFormat,
                            contentType = resp.header("Content-Type"),
                            defaultSampleRate = defaultSr,
                            onChunk = { false }, // 收到首块音频即判定通路有效并结束
                            isCancelled = { false },
                        )
                    }
                    when {
                        res.bytes > 0 -> null
                        res.error != null -> ErrorSanitizer.sanitize(res.error)
                        else -> "未收到可解码的音频数据"
                    }
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

    private fun runCustom(
        request: OnlineRequest,
        text: String,
        speed: Float,
        onChunk: (TtsAudioChunk) -> Boolean,
        isCancelled: () -> Boolean,
        callRef: AtomicReference<Call?>,
    ): OnlineOutcome {
        val t0 = System.currentTimeMillis()
        val url = request.baseUrl.trim()
        if (!url.startsWith("https://")) {
            return OnlineOutcome.Failed(OnlineFailureKind.HTTP, "invalid_url", "自定义接口地址必须以 https:// 开头")
        }
        return try {
            val req = buildHttpRequest(url, request, text, speed)
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

    private fun buildHttpRequest(
        url: String,
        request: OnlineRequest,
        text: String,
        speed: Float,
    ): Request {
        val bodyStr = CustomHttpTemplate.renderBody(
            template = request.customBodyTemplate,
            text = text,
            voice = request.voice,
            model = request.model,
            speed = speed,
            apiKey = request.apiKey,
        )
        val builder = Request.Builder()
            .url(url)
            .post(bodyStr.toRequestBody(JSON_MEDIA))
        val parsedHeaders = CustomHttpTemplate.parseHeaders(request.customHeaders, request.apiKey)
        var hasAuth = false
        for ((k, v) in parsedHeaders) {
            if (k.equals("Authorization", ignoreCase = true)) hasAuth = true
            builder.header(k, v)
        }
        if (!hasAuth && request.apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer ${request.apiKey}")
        }
        return builder.build()
    }

    private companion object {
        private const val CANCEL_TICK_MS = 50L
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
