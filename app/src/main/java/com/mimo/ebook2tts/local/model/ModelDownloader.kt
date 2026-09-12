package com.mimo.ebook2tts.local.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 模型下载与解压到 filesDir/models/<dirName>/。 */
object ModelDownloader {

    private const val TAG = "ModelDownloader"
    private const val CONNECT_TIMEOUT = 20_000
    private const val READ_TIMEOUT = 120_000

    fun modelRoot(context: Context): File =
        File(context.applicationContext.filesDir, "models").apply { mkdirs() }

    fun modelDir(context: Context, spec: ModelSpec): File =
        File(modelRoot(context), spec.dirName)

    fun isReady(context: Context, spec: ModelSpec): Boolean {
        val dir = modelDir(context, spec)
        val model = File(dir, spec.modelName)
        return dir.isDirectory && model.isFile && model.length() > 1_000_000
    }

    fun status(context: Context, spec: ModelSpec): String {
        val dir = modelDir(context, spec)
        return when {
            isReady(context, spec) -> "已就绪 · ${formatSize(dirSize(dir))}"
            dir.exists() && (dir.list()?.isNotEmpty() == true) -> "下载中/不完整"
            else -> "未下载 · 约 ${formatSize(spec.approxBytes)}"
        }
    }

    fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    suspend fun download(
        context: Context,
        spec: ModelSpec,
        onProgress: (Float, String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val root = modelRoot(context)
        val archive = File(root, spec.archiveName)
        val target = modelDir(context, spec)

        if (isReady(context, spec)) return@withContext target

        val urls = listOf(spec.primaryUrl, spec.mirrorUrl)
        var lastErr: Exception? = null
        for (url in urls) {
            try {
                onProgress(0f, "连接…")
                downloadFile(url, archive) { p, msg -> onProgress(p * 0.85f, msg) }
                lastErr = null
                break
            } catch (e: Exception) {
                Log.w(TAG, "download failed: $url", e)
                lastErr = e
            }
        }
        if (lastErr != null) throw lastErr

        onProgress(0.86f, "解压中…")
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        extractTarBz2(archive, target)
        archive.delete()
        onProgress(1f, "完成")

        if (!isReady(context, spec)) {
            throw IllegalStateException("解压后模型文件缺失：${spec.modelName}")
        }
        target
    }

    fun delete(context: Context, spec: ModelSpec) {
        modelDir(context, spec).deleteRecursively()
    }

    private fun downloadFile(
        urlString: String,
        dest: File,
        onProgress: (Float, String) -> Unit,
    ) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.instanceFollowRedirects = true
        try {
            conn.connect()
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong
            val input = conn.inputStream
            val out = FileOutputStream(tmp)
            val buf = ByteArray(64 * 1024)
            var read = 0L
            var n: Int
            var lastPct = -1
            while (input.read(buf).also { n = it } > 0) {
                out.write(buf, 0, n)
                read += n
                if (total > 0) {
                    val pct = (read * 100 / total).toInt()
                    if (pct != lastPct) {
                        lastPct = pct
                        onProgress(
                            read.toFloat() / total,
                            "下载 ${formatSize(read)} / ${formatSize(total)} ($pct%)"
                        )
                    }
                }
            }
            out.flush()
            out.close()
            input.close()
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun extractTarBz2(archive: File, destDir: File) {
        BZip2CompressorInputStream(archive.inputStream().buffered()).use { bzIn ->
            TarArchiveInputStream(bzIn).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    val out = File(destDir, entry.name).canonicalFile
                    if (!out.path.startsWith(destDir.canonicalPath)) {
                        entry = tar.nextTarEntry
                        continue
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos ->
                            val buf = ByteArray(64 * 1024)
                            var n: Int
                            while (tar.read(buf).also { n = it } > 0) {
                                fos.write(buf, 0, n)
                            }
                        }
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
    }
}
