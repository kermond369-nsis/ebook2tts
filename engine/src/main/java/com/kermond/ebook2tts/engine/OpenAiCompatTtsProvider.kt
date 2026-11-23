package com.kermond.ebook2tts.engine

import com.kermond.ebook2tts.core.TtsProviderKind
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenAI `/v1/audio/speech` 兼容在线 TTS 适配器。
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
        val firstByteSeen = AtomicBoolean(false)
        return OnlineCallRunner.executeStream(
            io = io,
            timeoutMs = request.timeoutMs,
            firstByteSeen = firstByteSeen,
            isCancelled = isCancelled,
        ) { callRef ->
            runSpeech(request, text, speed, onChunk, firstByteSeen, isCancelled, callRef)
        }
    }

    override fun validate(request: OnlineRequest): String? {
        val base = request.baseUrl.trim().trimEnd('/')
        if (!EndpointUrlPolicy.isAllowed(base)) return "接口地址必须以 https:// 开头"
        return OnlineCallRunner.executeValidation(io) {
            val validateClient = OnlineCallRunner.clientForTimeout(client, 15_000)
            val modelsBuilder = Request.Builder().url("$base/models").get()
            if (request.apiKey.isNotBlank()) {
                modelsBuilder.header("Authorization", "Bearer ${request.apiKey}")
            }
            validateClient.newCall(modelsBuilder.build()).execute().use { resp ->
                if (resp.isSuccessful) return@executeValidation null
                if (resp.code == 401 || resp.code == 403) {
                    val body = resp.body?.string().orEmpty()
                    return@executeValidation ErrorSanitizer.sanitize("HTTP ${resp.code}: $body")
                }
                if (resp.code != 404 && resp.code != 405 && resp.code != 501) {
                    val body = resp.body?.string().orEmpty()
                    return@executeValidation ErrorSanitizer.sanitize("HTTP ${resp.code}: $body")
                }
            }
            val speechReq = buildSpeechHttpRequest(base, request, "测试", 1.0f)
            validateClient.newCall(speechReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    return@executeValidation ErrorSanitizer.sanitize("HTTP ${resp.code}: $body")
                }
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                if (bytes.isEmpty()) "服务端返回空音频" else null
            }
        }
    }

    private fun runSpeech(
        request: OnlineRequest,
        text: String,
        speed: Float,
        onChunk: (TtsAudioChunk) -> Boolean,
        firstByteSeen: AtomicBoolean,
        isCancelled: () -> Boolean,
        callRef: AtomicReference<Call?>,
    ): OnlineOutcome {
        val t0 = System.currentTimeMillis()
        val base = request.baseUrl.trim().trimEnd('/')
        if (!EndpointUrlPolicy.isAllowed(base)) {
            return OnlineOutcome.Failed(OnlineFailureKind.HTTP, "invalid_url", "接口地址必须以 https:// 开头")
        }
        val streamClient = OnlineCallRunner.clientForTimeout(client, request.timeoutMs)
        return try {
            val req = buildSpeechHttpRequest(base, request, text, speed)
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
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
