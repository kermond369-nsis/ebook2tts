package com.kermond.ebook2tts.engine

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * HTTP 传输层（IM-202 / ADR-004）：OkHttp 统一承载「清单拉取 / 多源测速 / 断点续传下载」。
 *
 * 纪律：
 * - 断点续传依赖 `Range`；服务器不支持（返回 200）时自动从头重下，绝不半包拼接坏文件；
 * - 下载完成必须 `length == expectedBytes`（清单实测值，ERR-001）才交给 SHA-256 校验；
 * - 取消通过 `isCancelled` 回调检查，抛 `IOException("cancelled")` 由上层清理残包。
 */
class HttpDownloader(
    private val client: OkHttpClient = defaultClient(),
) : ManifestSource {

    override fun fetch(url: String): String? = runCatching {
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newBuilder().callTimeout(FETCH_TIMEOUT_S, TimeUnit.SECONDS).build()
            .newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
    }.onFailure {
        Log.w(TAG, "manifest fetch failed|${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()

    /**
     * 多源测速：对每个源做一次 ~64KB 的 `Range` 探测，返回字节/毫秒最高的源；
     * 全部探测失败时返回首个源（让下载阶段给出真实错误）。
     *
     * 探测**并行**执行：国内直连时官方源往往要等到超时才失败，串行会把下载起跑拖慢数倍。
     */
    fun pickFastest(sources: List<String>): String? {
        if (sources.isEmpty()) return null
        if (sources.size == 1) return sources.first()
        val pool = Executors.newFixedThreadPool(minOf(sources.size, 3))
        try {
            val futures = sources.map { s -> pool.submit(Callable { s to probe(s) }) }
            var best: String? = null
            var bestRate = -1.0
            for (f in futures) {
                val (s, rate) = f.get()
                Log.i(TAG, "probe|$s|rate=${"%.1f".format(rate)}KB/s")
                if (rate > bestRate) {
                    bestRate = rate
                    best = s
                }
            }
            Log.i(TAG, "picked|${best ?: sources.first()}|rate=${"%.1f".format(bestRate)}KB/s")
            return best ?: sources.first()
        } catch (t: Throwable) {
            Log.w(TAG, "probe pool failed: ${t.javaClass.simpleName}")
            return sources.first()
        } finally {
            pool.shutdown()
        }
    }

    private fun probe(url: String): Double {
        val t0 = System.nanoTime()
        val result = runCatching {
            val req = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-${PROBE_BYTES - 1}")
                .build()
            client.newBuilder().callTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS).build()
                .newCall(req).execute().use { resp ->
                    if (resp.code != 206 && !resp.isSuccessful) return@use -1.0
                    val body = resp.body ?: return@use -1.0
                    var read = 0L
                    body.byteStream().use { input ->
                        val buf = ByteArray(16 * 1024)
                        while (read < PROBE_BYTES) {
                            val n = input.read(buf)
                            if (n < 0) break
                            read += n
                        }
                    }
                    val ms = (System.nanoTime() - t0) / 1_000_000.0
                    if (read <= 0L || ms <= 0.0) -1.0 else read / ms
                }
        }
        result.exceptionOrNull()?.let { Log.w(TAG, "probe failed|$url|${it.javaClass.simpleName}: ${it.message}") }
        return result.getOrDefault(-1.0)
    }

    /**
     * 断点续传下载。[expectedBytes] 为清单实测下载体积（> 0 时启用完整性与续传判定）。
     */
    fun download(
        url: String,
        part: File,
        expectedBytes: Long,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        part.parentFile?.mkdirs()
        var offset = if (part.exists()) part.length() else 0L
        if (expectedBytes > 0L && offset == expectedBytes) return
        if (expectedBytes > 0L && offset > expectedBytes) {
            // 残包超长（清单换版/写入异常）：清空重下，避免越界 Range 触发 416 死循环（agy 交办 P2-01）
            Log.w(TAG, "PART_OVERSIZE|reset|got=$offset|expected=$expectedBytes")
            part.delete()
            offset = 0L
        }

        val req = Request.Builder().url(url)
            .apply { if (offset > 0L) header("Range", "bytes=$offset-") }
            .build()
        client.newBuilder().build().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val append = resp.code == 206 && offset > 0L
            if (!append && offset > 0L) {
                // 服务器不支持 Range：整包重下，绝不半包拼接
                Log.w(TAG, "RANGE_UNSUPPORTED|restart|url=$url|code=${resp.code}")
                offset = 0L
            }
            Log.i(TAG, "GET|url=$url|code=${resp.code}|resume_from=$offset|expected=$expectedBytes")
            val body = resp.body ?: throw IOException("empty body")
            FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) throw IOException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        offset += n
                        onProgress(offset, expectedBytes)
                    }
                }
                out.flush()
            }
            Log.i(TAG, "DONE|url=$url|bytes=${part.length()}")
        }
        if (expectedBytes > 0L && part.length() != expectedBytes) {
            throw IOException("incomplete download ${part.length()}/$expectedBytes")
        }
    }

    companion object {
        private const val TAG = "HttpDownloader"
        private const val PROBE_BYTES = 64L * 1024L
        private const val PROBE_TIMEOUT_S = 15L
        private const val FETCH_TIMEOUT_S = 15L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()
    }
}
