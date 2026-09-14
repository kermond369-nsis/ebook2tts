package com.kermond.ebook2tts.engine

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 在线后端生产代码 [OnlineBackend] 验证（OkHttp 官方 MockWebServer，不触真网）：
 * SSE 流式解析 / PCM16 帧对齐 / 载荷协议（messages+audio+stream）/ 401/429/500 分类 /
 * 读超时 → 可读摘要（截断 200 字）/ 取消即断连接 / GET /models 密钥校验。
 *
 * 「在线失败 → 回落决策」链路：本类的 Failed(kind) 输出直接喂 [OnlineFallback.decide]
 * （矩阵见 [OnlineRouterTest]）。
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
        // 401 且尚无音频 → 该请求整段回落本地
        assertEquals(FallbackAction.RESTART_LOCAL, OnlineFallback.decide(false, 24000, OnlineSettings_SR))
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
        assertEquals(OnlineFailureKind.NETWORK, failed.kind)
        assertEquals("timeout", failed.reason)
        assertTrue(failed.summary.length <= 200)
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

    private companion object {
        const val OnlineSettings_SR = 24000
    }
}
