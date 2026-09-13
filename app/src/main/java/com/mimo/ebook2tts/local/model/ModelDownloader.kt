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

    /** 进程级互斥：防止并发安装写同一目录 */
    private val installLock = Any()

    fun hasEnoughSpace(context: Context, spec: ModelSpec): Boolean {
        val root = modelRoot(context)
        val free = root.usableSpace
        // 下载 + 解压 ≈ 2.5× 压缩包，留余量
        val need = spec.approxBytes * 25 / 10
        return free >= need
    }

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
        synchronized(installLock) {
            installLocked(context, spec, customUrl, onProgress)
        }
    }

    private fun installLocked(
        context: Context,
        spec: ModelSpec,
        customUrl: String?,
        onProgress: (Float, String) -> Unit,
    ): File {
        val target = modelDir(context, spec)
        if (isReady(context, spec)) return target

        if (!hasEnoughSpace(context, spec)) {
            val freeMb = modelRoot(context).usableSpace / 1024 / 1024
            throw IllegalStateException("磁盘空间不足（剩余 ${freeMb}MB），约需 ${formatSize(spec.approxBytes * 25 / 10)}")
        }

        // assets
        if (hasAssets(context, spec)) {
            onProgress(0.1f, "正在从安装包解压…")
            extractFromAssets(context, spec, target)
            onProgress(1f, "就绪（来自安装包）")
            if (!isReady(context, spec)) {
                throw IllegalStateException("安装包内模型不完整：${spec.modelName}")
            }
            return target
        }

        // network
        val archive = File(modelRoot(context), spec.archiveName)
        val urls = buildList {
            val cu = customUrl?.trim().orEmpty()
            if (cu.isNotEmpty()) {
                if (!cu.startsWith("https://")) {
                    throw IllegalArgumentException("自定义链接仅支持 https://")
                }
                add(cu)
            }
            add(spec.primaryUrl)
            add(spec.mirrorUrl)
        }
        Log.i(TAG, "download urls=$urls")
        var lastErr: Exception? = null
        for (url in urls) {
            try {
                onProgress(0f, "连接中…")
                Log.i(TAG, "GET $url")
                downloadFile(url, archive) { p, msg -> onProgress(p * 0.85f, msg) }
                // 粗校验：大小 + bz2 魔数
                if (archive.length() < 1_000_000) {
                    throw IllegalStateException("文件过小 ${archive.length()}B")
                }
                archive.inputStream().use { ins ->
                    val h = ByteArray(3)
                    if (ins.read(h) < 3 || h[0] != 'B'.code.toByte() || h[1] != 'Z'.code.toByte()) {
                        throw IllegalStateException("不是有效的 .tar.bz2")
                    }
                }
                Log.i(TAG, "downloaded ${archive.length()} bytes from $url")
                lastErr = null
                break
            } catch (e: Exception) {
                Log.e(TAG, "FAIL $url: ${e.javaClass.simpleName}: ${e.message}", e)
                lastErr = e
                onProgress(0f, "该源失败：${e.message}")
                // 失败清理 .part / 残缺包
                runCatching { archive.delete() }
                File(archive.parentFile, archive.name + ".part").delete()
            }
        }
        if (lastErr != null) {
            throw IllegalStateException(
                "全部下载源失败。最后错误：${lastErr.message}\n可粘贴 https 直链后重试。",
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
        return target
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
