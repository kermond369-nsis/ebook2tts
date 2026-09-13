package com.mimo.ebook2tts.engine

import android.content.Context
import android.util.Log
import com.mimo.ebook2tts.core.ModelCatalog
import com.mimo.ebook2tts.core.ModelSpec
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** 模型下载（RQ-201/206 / IM-202）：多源、断点续传、SHA-256、空间预检。 */
class ModelDownloader(private val context: Context) {

    interface Progress {
        fun onProgress(bytes: Long, total: Long, stage: String)
        fun onSuccess(modelId: String)
        fun onError(message: String)
    }

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    fun modelsDir(): File = File(context.filesDir, "models")

    fun stagingDir(modelId: String): File =
        File(context.filesDir, "models-staging/$modelId.tmp")

    fun partFile(spec: ModelSpec): File =
        File(context.filesDir, "models-staging/${spec.archiveName}.part")

    fun isInstalled(spec: ModelSpec): Boolean {
        val dir = File(modelsDir(), spec.id)
        return File(dir, ".completed").exists() && File(dir, spec.files.modelName).exists()
    }

    fun availableBytes(): Long = context.filesDir.usableSpace

    fun checkSpace(spec: ModelSpec): Boolean {
        val need = ModelCatalog.requiredSpaceBytes(spec.extractedBytes)
        val avail = availableBytes()
        Log.i(TAG, "space need=$need avail=$avail")
        return avail >= need
    }

    fun downloadAndInstall(spec: ModelSpec, progress: Progress) {
        cancelled = false
        try {
            if (!checkSpace(spec)) {
                val needMb = ModelCatalog.requiredSpaceBytes(spec.extractedBytes) / 1_000_000
                progress.onError("空间不足：约需 ${needMb}MB 可用空间")
                return
            }
            val part = partFile(spec)
            part.parentFile?.mkdirs()

            val urls = listOf(spec.primaryUrl, spec.mirrorUrl)
            var ok = false
            for (url in urls) {
                try {
                    downloadResumable(url, part, spec.downloadBytes) { b, t ->
                        progress.onProgress(b, t, "download")
                    }
                    ok = true
                    break
                } catch (t: Throwable) {
                    Log.w(TAG, "mirror failed $url: $t")
                }
            }
            if (!ok) {
                progress.onError("下载失败，可尝试浏览器手动下载")
                return
            }
            progress.onProgress(spec.downloadBytes, spec.downloadBytes, "verify")
            val sha = sha256(part)
            if (!sha.equals(spec.sha256, ignoreCase = true)) {
                part.delete()
                progress.onError("SHA-256 校验失败，已清理残包")
                return
            }
            progress.onProgress(spec.downloadBytes, spec.downloadBytes, "extract")
            val staging = stagingDir(spec.id)
            part.inputStream().use { input ->
                SafeExtractor.extract(
                    input = input,
                    stagingDir = staging,
                    archiveName = spec.archiveName,
                    maxExtractedBytes = spec.extractedBytes,
                    whitelist = null,
                )
            }
            val modelFile = File(staging, spec.files.modelName)
            if (!modelFile.exists()) {
                staging.deleteRecursively()
                progress.onError("解压产物缺少模型文件")
                return
            }
            val finalDir = File(modelsDir(), spec.id)
            SafeExtractor.promote(staging, finalDir, "ok")
            part.delete()
            ConfigStore.setModelId(spec.id)
            ConfigStore.notifyReload(context, "model_installed")
            progress.onSuccess(spec.id)
        } catch (t: Throwable) {
            Log.e(TAG, "download failed", t)
            progress.onError(t.message ?: "download failed")
        }
    }

    private fun downloadResumable(
        urlString: String,
        part: File,
        total: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        var offset = if (part.exists()) part.length() else 0L
        if (total > 0 && offset >= total) return
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        if (offset > 0) {
            conn.setRequestProperty("Range", "bytes=$offset-")
        }
        conn.connect()
        val code = conn.responseCode
        if (code !in 200..299 && code != 206) {
            conn.disconnect()
            throw IOException("HTTP $code")
        }
        val append = code == 206 && offset > 0
        conn.inputStream.use { input ->
            FileOutputStream(part, append).use { out ->
                val buf = ByteArray(64 * 1024)
                while (!cancelled) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    offset += n
                    onProgress(offset, total)
                }
                if (cancelled) throw IOException("cancelled")
            }
        }
        conn.disconnect()
        if (total > 0 && part.length() < total) {
            throw IOException("incomplete download ${part.length()}/$total")
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun deleteModel(modelId: String): Boolean {
        val dir = File(modelsDir(), modelId)
        if (ConfigStore.modelId() == modelId) {
            ConfigStore.setModelId(ModelCatalog.DEFAULT_ID)
        }
        val ok = dir.deleteRecursively()
        ConfigStore.notifyReload(context, "model_deleted")
        return ok
    }

    fun importFromStream(
        input: InputStream,
        archiveName: String,
        spec: ModelSpec,
        progress: Progress,
    ) {
        try {
            if (!checkSpace(spec)) {
                progress.onError("空间不足")
                return
            }
            val staging = stagingDir(spec.id)
            SafeExtractor.extract(input, staging, archiveName, spec.extractedBytes, null)
            val modelFile = File(staging, spec.files.modelName)
            if (!modelFile.exists()) {
                staging.deleteRecursively()
                progress.onError("导入包缺少模型文件")
                return
            }
            SafeExtractor.promote(staging, File(modelsDir(), spec.id), "imported")
            ConfigStore.notifyReload(context, "model_imported")
            progress.onSuccess(spec.id)
        } catch (t: Throwable) {
            progress.onError(t.message ?: "import failed")
        }
    }

    companion object {
        private const val TAG = "ModelDownloader"
    }
}
