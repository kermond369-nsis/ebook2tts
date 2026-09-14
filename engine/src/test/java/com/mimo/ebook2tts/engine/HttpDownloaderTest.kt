package com.mimo.ebook2tts.engine

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * IM-202 传输层验证：用 MockWebServer 对**生产代码** [HttpDownloader] 做可复现单测
 * （Android 单测类路径无 `com.sun.net.httpserver`，故用 OkHttp 官方测试组件）。
 *
 * 覆盖：整包下载 / 断点续传（Range→206）/ 服务器忽略 Range 时整包重下（防半包损坏）/
 * 体积不符即报错 / 取消后保留残包供续传 / 多源测速跳过死源。
 */
class HttpDownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var payload: ByteArray
    private lateinit var tmpDir: File
    private val downloader = HttpDownloader()
    private var baseUrl = ""

    @Volatile
    private var ignoreRange = false

    @Before
    fun setUp() {
        payload = ByteArray(300_000) { ((it * 31 + 7) % 251).toByte() }
        tmpDir = Files.createTempDirectory("httptest").toFile()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                var start = 0L
                if (!ignoreRange && range != null && range.startsWith("bytes=")) {
                    start = range.removePrefix("bytes=").trimEnd('-').toLongOrNull() ?: 0L
                }
                val body = Buffer().write(payload, start.toInt(), payload.size - start.toInt())
                val resp = MockResponse().setBody(body).setHeader("Accept-Ranges", "bytes")
                if (start > 0L) {
                    resp.setResponseCode(206)
                    resp.setHeader("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
                } else {
                    resp.setResponseCode(200)
                }
                return resp
            }
        }
        server.start()
        baseUrl = server.url("/model.tar.bz2").toString()
    }

    @After
    fun tearDown() {
        server.shutdown()
        tmpDir.deleteRecursively()
    }

    private fun part() = File(tmpDir, "model.tar.bz2.part")

    private fun downloaded(): Boolean = payload.contentEquals(part().readBytes())

    @Test
    fun freshDownload_writesCompleteFile_noRange() {
        downloader.download(baseUrl, part(), payload.size.toLong(), { _, _ -> }, { false })
        assertEquals(payload.size.toLong(), part().length())
        assertTrue(downloaded())
        assertNull("全新下载不应带 Range", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun resumeFromPartial_sendsRangeAndCompletes() {
        part().writeBytes(payload.copyOfRange(0, 100_000))
        downloader.download(baseUrl, part(), payload.size.toLong(), { _, _ -> }, { false })
        assertEquals("bytes=100000-", server.takeRequest().getHeader("Range"))
        assertEquals(payload.size.toLong(), part().length())
        assertTrue(downloaded())
    }

    @Test
    fun serverIgnoresRange_restartsWithFullBody() {
        ignoreRange = true
        part().writeBytes(payload.copyOfRange(0, 100_000))
        downloader.download(baseUrl, part(), payload.size.toLong(), { _, _ -> }, { false })
        assertEquals(payload.size.toLong(), part().length())
        assertTrue("忽略 Range 时必须整包重下、不得半包拼接", downloaded())
    }

    @Test
    fun incompleteDownload_throws() {
        try {
            downloader.download(baseUrl, part(), payload.size + 1L, { _, _ -> }, { false })
            fail("体积不符必须抛 IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("incomplete"))
        }
    }

    @Test
    fun cancel_keepsPartialFileForResume() {
        var cancelled = false
        try {
            downloader.download(
                baseUrl,
                part(),
                payload.size.toLong(),
                { bytes, _ -> if (bytes >= 64L * 1024L) cancelled = true },
                { cancelled },
            )
            fail("取消必须抛 IOException")
        } catch (e: IOException) {
            assertEquals("cancelled", e.message)
        }
        val len = part().length()
        assertTrue("残包应保留且小于整包：$len", len in 1 until payload.size.toLong())
    }

    @Test
    fun pickFastest_skipsDeadSource() {
        assertEquals(baseUrl, downloader.pickFastest(listOf("http://127.0.0.1:1/dead", baseUrl)))
    }
}
