package com.kermond.ebook2tts.engine

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * HTTP 传输层（IM-202 / ADR-004）：OkHttp 统一承载「清单拉取 / 多源测速 / 断点续传下载」。
 *
 * 纪律：
 * - 断点续传依赖 `Range`；服务器不支持（返回 200）或返回 416 时自动清残包重下，绝不半包损坏；
 * - 拒绝镜像返回 `text/html` 拦截页污染 `.part`；
 * - 下载完成必须 `length == expectedBytes`（清单实测值）才交给 SHA-256 校验；
 * - 取消通过 `abort()` + `isCancelled` 双保险：秒级中断在途 socket 与探测线程池。
 */
class HttpDownloader(
    private val client: OkHttpClient = defaultClient(),
) : ManifestSource {

    data class ProbeResult(
        val url: String,
        val ok: Boolean,
        val latencyMs: Long,
        val speedKbps: Double,
        val httpCode: Int,
    )

    private val activeCalls = CopyOnWriteArrayList<okhttp3.Call>()

    /** 立即中断所有在途请求（取消/销毁时调用；幂等） */
    fun abort() {
        activeCalls.forEach { runCatching { it.cancel() } }
        activeCalls.clear()
    }

    override fun fetch(url: String): String? = runCatching {
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        val call = client.newBuilder().callTimeout(FETCH_TIMEOUT_S, TimeUnit.SECONDS).build().newCall(req)
        activeCalls.add(call)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        } finally {
            activeCalls.remove(call)
        }
    }.onFailure {
        Log.w(TAG, "manifest fetch failed|${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()

    /**
     * 并行探测所有候选源：每个源拉取 64KB Range，返回延迟与吞吐详情。
     */
    fun probeAll(urls: List<String>, isCancelled: () -> Boolean = { false }): List<ProbeResult> {
        if (urls.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(urls.size.coerceIn(1, 6))
        try {
            val futures = urls.map { u -> pool.submit(Callable { probeOne(u, isCancelled) }) }
            val out = mutableListOf<ProbeResult>()
            for ((idx, f) in futures.withIndex()) {
                while (true) {
                    if (isCancelled()) {
                        pool.shutdownNow()
                        abort()
                        return out
                    }
                    try {
                        out += f.get(150, TimeUnit.MILLISECONDS)
                        break
                    } catch (_: TimeoutException) {
                        // 短轮询以便立即响应取消
                    } catch (_: Throwable) {
                        out += ProbeResult(urls[idx], ok = false, latencyMs = -1L, speedKbps = -1.0, httpCode = 0)
                        break
                    }
                }
            }
            return out
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * 多源测速选最快：对每个源并行做 ~64KB 的 `Range` 探测，返回速度最高的源；
     * 全部探测失败时返回首个源（交由下载阶段尝试与报错）；取消则返回 null。
     */
    fun pickFastest(sources: List<String>, isCancelled: () -> Boolean = { false }): String? {
        if (sources.isEmpty() || isCancelled()) return null
        if (sources.size == 1) return sources.first()
        val results = probeAll(sources, isCancelled)
        if (isCancelled()) {
            Log.i(TAG, "probe cancelled by user")
            return null
        }
        var best: ProbeResult? = null
        for (r in results) {
            Log.i(TAG, "probe|${r.url}|ok=${r.ok}|code=${r.httpCode}|rate=${"%.1f".format(r.speedKbps)}KB/s")
            if (r.ok && (best == null || r.speedKbps > best.speedKbps)) {
                best = r
            }
        }
        val picked = best?.url ?: sources.first()
        Log.i(TAG, "picked|$picked|rate=${"%.1f".format(best?.speedKbps ?: -1.0)}KB/s")
        return picked
    }

    private fun probeOne(url: String, isCancelled: () -> Boolean): ProbeResult {
        if (isCancelled()) return ProbeResult(url, ok = false, latencyMs = -1L, speedKbps = -1.0, httpCode = 0)
        val t0 = System.nanoTime()
        var code = 0
        val result = runCatching {
            val req = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-${PROBE_BYTES - 1}")
                .build()
            val probeClient = client.newBuilder()
                .connectTimeout(PROBE_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(PROBE_READ_TIMEOUT_S, TimeUnit.SECONDS)
                .callTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
                .build()
            val probeCall = probeClient.newCall(req)
            activeCalls.add(probeCall)
            if (isCancelled()) {
                probeCall.cancel()
                activeCalls.remove(probeCall)
                return ProbeResult(url, ok = false, latencyMs = -1L, speedKbps = -1.0, httpCode = 0)
            }
            try {
                probeCall.execute().use { resp ->
                    code = resp.code
                    if (resp.code != 206 && !resp.isSuccessful) {
                        return@use ProbeResult(url, ok = false, latencyMs = elapsedMs(t0), speedKbps = -1.0, httpCode = code)
                    }
                    val ct = resp.header("Content-Type").orEmpty()
                    if (ct.contains("text/html", ignoreCase = true)) {
                        return@use ProbeResult(url, ok = false, latencyMs = elapsedMs(t0), speedKbps = -1.0, httpCode = code)
                    }
                    val body = resp.body
                        ?: return@use ProbeResult(url, ok = false, latencyMs = elapsedMs(t0), speedKbps = -1.0, httpCode = code)
                    var read = 0L
                    var firstByteAt = 0L
                    body.byteStream().use { input ->
                        val buf = ByteArray(16 * 1024)
                        while (read < PROBE_BYTES) {
                            if (isCancelled()) break
                            val n = input.read(buf)
                            if (n < 0) break
                            if (firstByteAt == 0L && n > 0) firstByteAt = System.nanoTime()
                            read += n
                        }
                    }
                    val totalMs = ((System.nanoTime() - t0) / 1_000_000.0).coerceAtLeast(1.0)
                    val latencyMs = if (firstByteAt > 0L) ((firstByteAt - t0) / 1_000_000L).coerceAtLeast(1L) else totalMs.toLong()
                    if (read <= 0L) {
                        ProbeResult(url, ok = false, latencyMs = latencyMs, speedKbps = -1.0, httpCode = code)
                    } else {
                        ProbeResult(url, ok = true, latencyMs = latencyMs, speedKbps = read / totalMs, httpCode = code)
                    }
                }
            } finally {
                activeCalls.remove(probeCall)
            }
        }
        result.exceptionOrNull()?.let { Log.w(TAG, "probe failed|$url|${it.javaClass.simpleName}: ${it.message}") }
        return result.getOrElse {
            ProbeResult(url, ok = false, latencyMs = elapsedMs(t0), speedKbps = -1.0, httpCode = code)
        }
    }

    private fun elapsedMs(t0: Long): Long = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)

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
        if (isCancelled()) throw IOException("cancelled")
        part.parentFile?.mkdirs()
        var offset = if (part.exists()) part.length() else 0L
        if (expectedBytes > 0L && offset == expectedBytes) {
            onProgress(offset, expectedBytes)
            return
        }
        if (expectedBytes > 0L && offset > expectedBytes) {
            Log.w(TAG, "PART_OVERSIZE|reset|got=$offset|expected=$expectedBytes")
            part.delete()
            offset = 0L
        }

        try {
            executeTransfer(url, part, offset, expectedBytes, onProgress, isCancelled)
        } catch (retry416: RangeUnsatisfiableException) {
            Log.w(TAG, "RANGE_416|reset_and_retry|url=$url|oldOffset=$offset|msg=${retry416.message}")
            part.delete()
            executeTransfer(url, part, 0L, expectedBytes, onProgress, isCancelled)
        }

        if (isCancelled()) throw IOException("cancelled")
        if (expectedBytes > 0L && part.length() != expectedBytes) {
            throw IOException("incomplete download ${part.length()}/$expectedBytes")
        }
    }

    private class RangeUnsatisfiableException(msg: String) : IOException(msg)

    private fun executeTransfer(
        url: String,
        part: File,
        initialOffset: Long,
        expectedBytes: Long,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        if (isCancelled()) throw IOException("cancelled")
        var offset = initialOffset
        val req = Request.Builder().url(url)
            .apply { if (offset > 0L) header("Range", "bytes=$offset-") }
            .build()
        val call = client.newBuilder().build().newCall(req)
        activeCalls.add(call)
        if (isCancelled()) {
            call.cancel()
            activeCalls.remove(call)
            throw IOException("cancelled")
        }
        try {
            call.execute().use { resp ->
                if (resp.code == 416 && offset > 0L) {
                    throw RangeUnsatisfiableException("HTTP 416")
                }
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val ct = resp.header("Content-Type").orEmpty()
                if (ct.contains("text/html", ignoreCase = true)) {
                    throw IOException("unexpected html response from $url")
                }
                val append = resp.code == 206 && offset > 0L
                if (!append && offset > 0L) {
                    Log.w(TAG, "RANGE_UNSUPPORTED|restart|url=$url|code=${resp.code}")
                    offset = 0L
                }
                Log.i(TAG, "GET|url=$url|code=${resp.code}|resume_from=$offset|expected=$expectedBytes")
                val body = resp.body ?: throw IOException("empty body")
                val declaredLen = body.contentLength()
                val totalHint = when {
                    expectedBytes > 0L -> expectedBytes
                    declaredLen > 0L -> offset + declaredLen
                    else -> expectedBytes
                }
                FileOutputStream(part, append).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (isCancelled()) throw IOException("cancelled")
                            val n = input.read(buf)
                            if (n < 0) break
                            if (isCancelled()) throw IOException("cancelled")
                            out.write(buf, 0, n)
                            offset += n
                            onProgress(offset, totalHint)
                        }
                    }
                    out.flush()
                }
                Log.i(TAG, "DONE|url=$url|bytes=${part.length()}")
            }
        } catch (t: IOException) {
            if (isCancelled()) throw IOException("cancelled", t)
            throw t
        } finally {
            activeCalls.remove(call)
        }
    }

    companion object {
        private const val TAG = "HttpDownloader"
        private const val PROBE_BYTES = 64L * 1024L
        private const val PROBE_CONNECT_TIMEOUT_S = 5L
        private const val PROBE_READ_TIMEOUT_S = 6L
        private const val PROBE_TIMEOUT_S = 8L
        private const val FETCH_TIMEOUT_S = 10L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()
    }
}
