package com.kermond.ebook2tts.engine

import com.kermond.ebook2tts.core.CustomHttpTemplate
import com.kermond.ebook2tts.core.TtsProviderKind
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
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
        val firstByteSeen = AtomicBoolean(false)
        return OnlineCallRunner.executeStream(
            io = io,
            timeoutMs = request.timeoutMs,
            firstByteSeen = firstByteSeen,
            isCancelled = isCancelled,
        ) { callRef ->
            runCustom(request, text, speed, onChunk, firstByteSeen, isCancelled, callRef)
        }
    }

    override fun validate(request: OnlineRequest): String? {
        val url = request.baseUrl.trim()
        if (!EndpointUrlPolicy.isAllowed(url)) return "自定义接口地址必须以 https:// 开头"
        return OnlineCallRunner.executeValidation(io) {
            val validateClient = OnlineCallRunner.clientForTimeout(client, 15_000)
            val req = buildHttpRequest(url, request, "测试", 1.0f)
            validateClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    return@executeValidation ErrorSanitizer.sanitize("HTTP ${resp.code}: $body")
                }
                val body = resp.body ?: return@executeValidation "响应无内容"
                val defaultSr = if (request.sampleRate > 0) request.sampleRate else kind.defaultSampleRate
                val res = body.byteStream().use { stream ->
                    AudioStreamDecoder.decodeStream(
                        input = stream,
                        formatHint = request.responseFormat,
                        contentType = resp.header("Content-Type"),
                        defaultSampleRate = defaultSr,
                        onChunk = { false },
                        isCancelled = { false },
                    )
                }
                when {
                    res.bytes > 0 -> null
                    res.error != null -> ErrorSanitizer.sanitize(res.error)
                    else -> "未收到可解码的音频数据"
                }
            }
        }
    }

    private fun runCustom(
        request: OnlineRequest,
        text: String,
        speed: Float,
        onChunk: (TtsAudioChunk) -> Boolean,
        firstByteSeen: AtomicBoolean,
        isCancelled: () -> Boolean,
        callRef: AtomicReference<Call?>,
    ): OnlineOutcome {
        val t0 = System.currentTimeMillis()
        val url = request.baseUrl.trim()
        if (!EndpointUrlPolicy.isAllowed(url)) {
            return OnlineOutcome.Failed(OnlineFailureKind.HTTP, "invalid_url", "自定义接口地址必须以 https:// 开头")
        }
        val streamClient = OnlineCallRunner.clientForTimeout(client, request.timeoutMs)
        return try {
            val req = buildHttpRequest(url, request, text, speed)
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
                val body = resp.body
                    ?: return OnlineOutcome.Failed(OnlineFailureKind.NETWORK, "empty_body", "响应无内容")
                val defaultSr = if (request.sampleRate > 0) request.sampleRate else kind.defaultSampleRate
                val res = body.byteStream().use { stream ->
                    AudioStreamDecoder.decodeStream(
                        input = stream,
                        formatHint = request.responseFormat,
                        contentType = resp.header("Content-Type"),
                        defaultSampleRate = defaultSr,
                        onChunk = { chunk ->
                            if (chunk.pcm16Mono.isNotEmpty()) firstByteSeen.set(true)
                            onChunk(chunk)
                        },
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
            OnlineCallRunner.throwableFailure(t, isCancelled, callRef.get())
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
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
