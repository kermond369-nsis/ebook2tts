package com.kermond.ebook2tts.engine

import com.kermond.ebook2tts.core.TtsProviderKind
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 在线后端生产代码 [OnlineBackend] 验证（OkHttp 官方 MockWebServer，不触真网）：
 * SSE 流式解析 / PCM16 帧对齐 / 载荷协议（messages+audio+stream）/ 401/429/500 分类 /
 * 读超时与弱网首包硬超时看门狗 → TIMEOUT / 取消即断连接 / GET /models 密钥校验。
 */
class OnlineBackendTest {

    private lateinit var server: MockWebServer
    private lateinit var backend: OnlineBackend
    private var baseUrl = ""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/v1").toString().trimEnd('/')
        backend = OnlineBackend(client = testClient(readTimeoutMs = 700))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun testClient(readTimeoutMs: Long): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(1, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private fun request() = OnlineRequest(
        baseUrl = baseUrl,
        apiKey = "sk-test",
        model = "mimo-v2.5-tts",
        voice = "白桦",
        style = "",
    )

    private fun sse(vararg dataLines: String): String =
        dataLines.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

    private fun audioLine(bytes: ByteArray): String =
        """{"choices":[{"delta":{"audio":{"data":"${Base64.getEncoder().encodeToString(bytes)}"}}}]}"""

    @Test
    fun happyPath_streamsPcm_andSendsProtocolPayload() {
        val pcm1 = ByteArray(960) { 1 }
        val pcm2 = ByteArray(480) { 2 }
        server.enqueue(MockResponse().setResponseCode(200).setBody(sse(audioLine(pcm1), audioLine(pcm2))))

        val got = ArrayList<ByteArray>()
        val outcome = backend.synthesize(request(), "你好，世界。", { got.add(it); true }, { false })

        assertTrue("应成功：$outcome", outcome is OnlineOutcome.Ok)
        val ok = outcome as OnlineOutcome.Ok
        assertEquals(1440, ok.bytes)
        assertEquals(2, ok.chunks)
        assertTrue(ok.ttfbMs >= 0L)
        assertEquals(1440, got.sumOf { it.size })

        val recorded = server.takeRequest()
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        assertEquals("text/event-stream", recorded.getHeader("Accept"))
        assertTrue(recorded.path!!.endsWith("/chat/completions"))
        val body = recorded.body.readUtf8()
        assertTrue("载荷须含 messages", body.contains("\"messages\""))
        assertTrue("载荷须含 audio.format=pcm16", body.contains("\"format\":\"pcm16\""))
        assertTrue("载荷须含音色", body.contains("白桦"))
        assertTrue("载荷须为流式", body.contains("\"stream\":true"))
    }

    @Test
    fun oddLengthChunks_areFrameAligned() {
        // 两块各 3 字节：PCM16 帧边界不齐 → 跨块拼接后按帧对齐输出（全部偶数长）
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(sse(audioLine(ByteArray(3) { 1 }), audioLine(ByteArray(3) { 2 })))
        )
        val got = ArrayList<ByteArray>()
        val ok = backend.synthesize(request(), "x", { got.add(it); true }, { false }) as OnlineOutcome.Ok
        assertEquals(6, ok.bytes)
        assertEquals(listOf(2, 4), got.map { it.size })
    }

    @Test
    fun streamWithoutAudio_classifiedEmpty() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"no audio\"}}]}\n\ndata: [DONE]\n\n")
        )
        val outcome = backend.synthesize(request(), "x", { true }, { false })
        val failed = outcome as OnlineOutcome.Failed
        assertEquals(OnlineFailureKind.EMPTY, failed.kind)
        assertEquals("empty", failed.reason)
    }

    @Test
    fun http401_classifiedAuth_summaryTruncatedTo200() {
        // 500 字节错误体：摘要必须截断到 200 字以内且可读
        val longBody = "{\"error\":\"" + "x".repeat(500) + "\"}"
        server.enqueue(MockResponse().setResponseCode(401).setBody(longBody))

        val outcome = backend.synthesize(request(), "x", { true }, { false })
        val failed = outcome as OnlineOutcome.Failed
        assertEquals(OnlineFailureKind.AUTH, failed.kind)
        assertEquals("http_401", failed.reason)
        assertTrue(failed.summary.contains("401"))
        assertTrue("摘要须截断 200 字（实测 ${failed.summary.length}）", failed.summary.length <= 200)
    }

    @Test
    fun http429_classifiedRateLimit() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("{\"error\":\"too many requests\"}"))
        val failed = backend.synthesize(request(), "x", { true }, { false }) as OnlineOutcome.Failed
        assertEquals(OnlineFailureKind.RATE_LIMIT, failed.kind)
        assertEquals("http_429", failed.reason)
    }

    @Test
    fun http500_classifiedHttp() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("server exploded"))
        val failed = backend.synthesize(request(), "x", { true }, { false }) as OnlineOutcome.Failed
        assertEquals(OnlineFailureKind.HTTP, failed.kind)
        assertEquals("http_500", failed.reason)
        assertTrue(failed.summary.length <= 200)
    }

    @Test
    fun readTimeout_classifiedNetworkTimeout() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val failed = backend.synthesize(request(), "x", { true }, { false }) as OnlineOutcome.Failed
        assertEquals(OnlineFailureKind.TIMEOUT, failed.kind)
        assertEquals("timeout", failed.reason)
        assertTrue(failed.summary.length <= 200)
    }

    @Test
    fun weakNetworkFirstByteWatchdog_abortsSlowStreamWithinConfiguredTimeout() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeadersDelay(6, TimeUnit.SECONDS)
                .setBody(sse(audioLine(ByteArray(480) { 1 }))),
        )
        val slowClient = testClient(readTimeoutMs = 30_000)
        val slowBackend = OnlineBackend(client = slowClient)
        val t0 = System.nanoTime()
        val outcome = slowBackend.synthesize(
            request().copy(timeoutMs = 2_000),
            "弱网超时测试",
            { true },
            { false },
        )
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        val failed = outcome as OnlineOutcome.Failed
        assertEquals(OnlineFailureKind.TIMEOUT, failed.kind)
        assertEquals("timeout", failed.reason)
        assertTrue("首包看门狗应按 2s 触发（实测 ${elapsedMs}ms）", elapsedMs in 1_800..4_500)
    }

    @Test
    fun cancel_closesInFlightConnectionQuickly() {
        // 对比基准：读超时 5s；取消后应远快于此返回
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val slow = OnlineBackend(client = testClient(readTimeoutMs = 5_000))
        val cancelled = AtomicBoolean(false)
        val flip = Thread {
            Thread.sleep(200)
            cancelled.set(true)
        }
        flip.start()
        val t0 = System.nanoTime()
        val outcome = slow.synthesize(request(), "x", { true }, { cancelled.get() })
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        flip.join()
        assertEquals(OnlineOutcome.Cancelled, outcome)
        assertTrue("取消应在 2s 内生效（对比读超时 5s，实测 ${elapsedMs}ms）", elapsedMs < 2_000)
    }

    @Test
    fun validateKey_okThenRejected() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"data\":[]}"))
        assertNull("200 → 密钥有效", backend.validateKey(request()))

        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"invalid api key\"}"))
        val msg = backend.validateKey(request())
        assertNotNull(msg)
        assertTrue(msg!!.contains("401"))

        val first = server.takeRequest()
        assertEquals("GET", first.method)
        assertTrue(first.path!!.endsWith("/models"))
    }

    @Test
    fun openAiCompat_streamsPcmAndWav_andFallsBackValidateOn404() {
        val pcmPayload = ByteArray(480) { 3 }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "audio/pcm")
                .setBody(Buffer().write(pcmPayload)),
        )
        val openAiReq = OnlineRequest(
            baseUrl = baseUrl,
            apiKey = "",
            model = "gpt-4o-mini-tts",
            voice = "alloy",
            style = "",
            providerKind = TtsProviderKind.OPENAI_COMPAT,
            responseFormat = "pcm",
            sampleRate = 24000,
        )
        val chunks = ArrayList<TtsAudioChunk>()
        val outcome = backend.synthesizeChunks(openAiReq, "你好", 1.3f, { chunks.add(it); true }, { false })
        assertTrue(outcome is OnlineOutcome.Ok)
        assertEquals(480, chunks.sumOf { it.pcm16Mono.size })
        assertEquals(24000, chunks.first().sampleRate)

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.endsWith("/audio/speech"))
        assertNull("空密钥时不得发送 Authorization 头", recorded.getHeader("Authorization"))
        val sentJson = recorded.body.readUtf8()
        assertTrue(sentJson.contains("\"speed\":1.3"))
        assertTrue(sentJson.contains("\"response_format\":\"pcm\""))

        // WAV 16kHz 响应：自动剥头并按 WAV 头中采样率输出
        val wavBytes = buildWav16k(ByteArray(320) { 5 })
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "audio/wav")
                .setBody(Buffer().write(wavBytes)),
        )
        val wavChunks = ArrayList<TtsAudioChunk>()
        val wavOutcome = backend.synthesizeChunks(
            openAiReq.copy(responseFormat = "wav"),
            "WAV测试",
            1.0f,
            { wavChunks.add(it); true },
            { false },
        )
        assertTrue(wavOutcome is OnlineOutcome.Ok)
        assertEquals(320, wavChunks.sumOf { it.pcm16Mono.size })
        assertEquals(16000, wavChunks.first().sampleRate)
        server.takeRequest()

        // /models 返回 404 时自动回落探测 /audio/speech
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(wavBytes)))
        assertNull(backend.validate(openAiReq))
        assertTrue(server.takeRequest().path!!.endsWith("/models"))
        assertTrue(server.takeRequest().path!!.endsWith("/audio/speech"))
    }

    @Test
    fun customHttp_rendersTemplateHeadersAndDecodesWav() {
        val wavBytes = buildWav16k(ByteArray(160) { 7 })
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(wavBytes)))

        val customReq = OnlineRequest(
            baseUrl = "$baseUrl/tts",
            apiKey = "secret-xyz",
            model = "fish-speech-1.5",
            voice = "narrator",
            style = "",
            providerKind = TtsProviderKind.CUSTOM_HTTP,
            responseFormat = "wav",
            sampleRate = 16000,
            customHeaders = "X-Token: {{apiKey}}",
            customBodyTemplate = """{"text":"{{text}}","speaker":"{{voice}}","speed":{{speed}}}""",
        )
        val got = ArrayList<TtsAudioChunk>()
        val res = backend.synthesizeChunks(customReq, "第一行\n\"引用\"", 1.5f, { got.add(it); true }, { false })
        assertTrue(res is OnlineOutcome.Ok)
        assertEquals(160, got.sumOf { it.pcm16Mono.size })
        assertEquals(16000, got.first().sampleRate)

        val recorded = server.takeRequest()
        assertEquals("secret-xyz", recorded.getHeader("X-Token"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\\n\\\"引用\\\""))
        assertTrue(body.contains("\"speed\":1.50"))
    }

    @Test
    fun errorSanitizer_masksSecrets() {
        val masked = ErrorSanitizer.sanitize("HTTP 401: Bearer abc.def.ghi failed sk-12345678 and tp-abcdef99")
        assertTrue(masked.contains("Bearer ***"))
        assertTrue(!masked.contains("abc.def.ghi"))
        assertTrue(!masked.contains("sk-12345678"))
        assertTrue(!masked.contains("tp-abcdef99"))
    }

    @Test
    fun validateKey_sanitizesEchoedSecretInHttpError() {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("Unauthorized for Bearer sk-secret9999"),
        )
        val err = backend.validateKey(request().copy(apiKey = "sk-secret9999"))
        assertNotNull(err)
        assertTrue(err!!.contains("HTTP 401"))
        assertTrue(err.contains("Bearer ***"))
        assertTrue(!err.contains("sk-secret9999"))
    }

    private fun buildWav16k(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
        fun u32(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 24) and 0xFF)
        }
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        u32(36 + pcm.size)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        u32(16)
        u16(1)
        u16(1)
        u32(16000)
        u32(32000)
        u16(2)
        u16(16)
        out.write("data".toByteArray(Charsets.US_ASCII))
        u32(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    private companion object {
        const val OnlineSettings_SR = 24000
    }
}
