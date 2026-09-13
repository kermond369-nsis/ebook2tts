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

/**
 * 模型安装顺序：
 * 1. filesDir/models 已就绪 → 直接用
 * 2. APK assets/models/<dir>/ → 拷贝
 * 3. 自定义 URL 或内置镜像 → 下载解压
 */
object ModelDownloader {

    private const val TAG = "ModelDownloader"
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 180_000

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
            hasAssets(context, spec) -> "APK 内置 · 点下载/安装解压"
            dir.exists() && (dir.list()?.isNotEmpty() == true) -> "不完整 · ${dir.list()?.joinToString()}"
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

    private fun hasAssets(context: Context, spec: ModelSpec): Boolean {
        return try {
            context.assets.open("models/${spec.dirName}/${spec.modelName}").use { true }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * @param customUrl 用户自定义下载地址；非空则优先使用
     */
    suspend fun install(
        context: Context,
        spec: ModelSpec,
        customUrl: String? = null,
        onProgress: (Float, String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val target = modelDir(context, spec)
        if (isReady(context, spec)) return@withContext target

        // 1) assets
        if (hasAssets(context, spec)) {
            onProgress(0.1f, "Extracting bundled model…")
            extractFromAssets(context, spec, target)
            onProgress(1f, "Ready from APK assets")
            if (!isReady(context, spec)) {
                throw IllegalStateException("Bundled model incomplete: ${spec.modelName}")
            }
            return@withContext target
        }

        // 2) network
        val archive = File(modelRoot(context), spec.archiveName)
        val urls = buildList {
            customUrl?.trim()?.takeIf { it.startsWith("http") }?.let { add(it) }
            add(spec.primaryUrl)
            add(spec.mirrorUrl)
        }
        Log.i(TAG, "download urls=$urls")
        var lastErr: Exception? = null
        for (url in urls) {
            try {
                onProgress(0f, "Connecting…")
                Log.i(TAG, "GET $url")
                downloadFile(url, archive) { p, msg -> onProgress(p * 0.85f, msg) }
                Log.i(TAG, "downloaded ${archive.length()} bytes from $url")
                lastErr = null
                break
            } catch (e: Exception) {
                Log.e(TAG, "FAIL $url: ${e.javaClass.simpleName}: ${e.message}", e)
                lastErr = e
                onProgress(0f, "Failed (${e.message}), trying next…")
            }
        }
        if (lastErr != null) {
            throw IllegalStateException(
                "All sources failed. Last: ${lastErr.message}\n" +
                    "You can paste a custom URL (tar.bz2) in the field above.",
                lastErr
            )
        }

        onProgress(0.86f, "解压中…")
        Log.i(TAG, "extract archive=${archive.length()} -> $target")
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        extractTarBz2(archive, target)
        flattenSingleChildDir(target)
        archive.delete()

        if (!isReady(context, spec)) {
            val listing = target.walkTopDown().filter { it.isFile }.take(20)
                .joinToString { it.relativeTo(target).path }
            Log.e(TAG, "not ready after extract. files=$listing")
            throw IllegalStateException("解压后缺少 ${spec.modelName}，目录内容：$listing")
        }
        Log.i(TAG, "extract ok size=${dirSize(target)}")
        onProgress(1f, "完成")
        target
    }

    /** 兼容旧调用 */
    suspend fun download(
        context: Context,
        spec: ModelSpec,
        onProgress: (Float, String) -> Unit,
    ): File = install(context, spec, null, onProgress)

    fun delete(context: Context, spec: ModelSpec) {
        modelDir(context, spec).deleteRecursively()
    }

    private fun extractFromAssets(context: Context, spec: ModelSpec, target: File) {
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        val am = context.assets
        val prefix = "models/${spec.dirName}"
        fun copyFile(assetPath: String, outFile: File) {
            outFile.parentFile?.mkdirs()
            am.open(assetPath).use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
        }
        fun walk(assetPath: String) {
            val children = am.list(assetPath)
            if (children.isNullOrEmpty()) {
                val rel = assetPath.removePrefix("$prefix/").removePrefix(prefix)
                if (rel.isNotEmpty() && rel != assetPath) {
                    copyFile(assetPath, File(target, rel))
                }
                return
            }
            for (c in children) {
                walk("$assetPath/$c")
            }
        }
        walk(prefix)
        Log.i(TAG, "assets extract files=${target.walkTopDown().count { it.isFile }}")
    }

    /** 官方 tar 顶层常有同名目录，若只有一层子目录则上提 */
    private fun flattenSingleChildDir(dir: File) {
        val children = dir.listFiles() ?: return
        if (children.size == 1 && children[0].isDirectory) {
            val inner = children[0]
            val tmp = File(dir.parentFile, dir.name + ".flat")
            if (tmp.exists()) tmp.deleteRecursively()
            inner.renameTo(tmp)
            dir.deleteRecursively()
            tmp.renameTo(dir)
            Log.i(TAG, "flattened nested dir -> $dir")
        }
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
        conn.setRequestProperty("User-Agent", "ebook2tts-local/0.1")
        try {
            conn.connect()
            val code = conn.responseCode
            Log.i(TAG, "HTTP $code for $urlString")
            if (code !in 200..299) {
                val err = conn.errorStream?.readBytes()?.decodeToString()?.take(200) ?: ""
                throw IllegalStateException("HTTP $code $err")
            }
            val total = conn.contentLengthLong
            val input = conn.inputStream
            val out = FileOutputStream(tmp)
            val buf = ByteArray(256 * 1024)
            var read = 0L
            var n: Int
            var lastPct = -1
            while (input.read(buf).also { n = it } > 0) {
                out.write(buf, 0, n)
                read += n
                if (total > 0) {
                    val pct = (read * 100 / total).toInt()
                    if (pct != lastPct && pct % 2 == 0) {
                        lastPct = pct
                        onProgress(
                            read.toFloat() / total,
                            "${formatSize(read)} / ${formatSize(total)} ($pct%)"
                        )
                    }
                } else if (read % (4L * 1024 * 1024) < buf.size) {
                    onProgress(0.5f, "Downloaded ${formatSize(read)}")
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
                            val buf = ByteArray(256 * 1024)
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
