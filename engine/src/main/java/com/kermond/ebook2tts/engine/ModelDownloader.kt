package com.kermond.ebook2tts.engine

import android.content.Context
import android.util.Log
import com.kermond.ebook2tts.core.MirrorResolver
import com.kermond.ebook2tts.core.ModelCatalog
import com.kermond.ebook2tts.core.ModelLayout
import com.kermond.ebook2tts.core.ModelLimits
import com.kermond.ebook2tts.core.ModelSpec
import com.kermond.ebook2tts.core.NetPolicy
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * 模型下载与生命周期（IM-201/202/204/205、RQ-201/202/206）。
 *
 * - **清单驱动**：模型元数据与体积一律取自 `ModelRegistry`（远程清单 → 缓存 → 内置兜底）；
 * - **多源竞速与手动指定**：支持自动测速选最快与下拉指定镜像节点，失败按序回退并透传当前通道；
 * - **断点续传**：OkHttp `Range`（IM-202），服务器不支持 / 返回 416 / 返回 HTML 错页时自愈重下；
 * - **秒级取消**：覆盖清单拉取、测速、传输、SHA-256 校验、安全解压全阶段；
 * - **原子落盘 & SAF 导入**：支持 `.tar.bz2`/`.tar.gz`/`.zip` 与 `.onnx` 独立声码器导入。
 */
class ModelDownloader(private val context: Context) {

    interface Progress {
        fun onProgress(bytes: Long, total: Long, stage: String)
        fun onSuccess(modelId: String)
        fun onError(message: String)

        /** 用户取消：默认按「非错误」处理，保留断点供续传。 */
        fun onCancelled(bytes: Long, total: Long) {}

        /** 当前正在使用的下载镜像节点主机名（如 `gh-proxy.com` / `github.com`）。 */
        fun onSourceChanged(sourceLabel: String) {}
    }

    @Volatile
    private var cancelled = false

    internal val http = HttpDownloader()

    /** 正在下载/导入的模型 id（删除保护，IM-204） */
    @Volatile
    private var activeId: String? = null

    /**
     * 取消：置标志 + **立即中断在途 IO**。
     * `.part` 保留 ⇒ 下次仍可断点续传。
     */
    fun cancel() {
        cancelled = true
        http.abort()
    }

    /** 当前是否处于"用户已取消"状态 */
    fun isCancelled(): Boolean = cancelled

    fun modelsDir(): File = File(context.filesDir, "models")

    fun stagingDir(modelId: String): File =
        File(context.filesDir, "models-staging/$modelId.tmp")

    fun partFile(spec: ModelSpec): File =
        File(context.filesDir, "models-staging/${spec.archiveName}.part")

    fun isInstalled(spec: ModelSpec): Boolean {
        val dir = File(modelsDir(), spec.id)
        if (!File(dir, ".completed").exists() || !File(dir, spec.files.modelName).exists()) return false
        if (spec.files.vocoder.isNotEmpty()) {
            val voc = File(dir, spec.files.vocoder)
            if (!voc.exists() || voc.length() <= 0L) return false
        }
        return true
    }

    fun availableBytes(): Long = context.filesDir.usableSpace

    /** 空间预检（RQ-206 / ERR-001）：可用空间 ≥ 2.5 × 清单实测解压体积（含独立声码器文件）。 */
    fun checkSpace(spec: ModelSpec): Boolean {
        val need = ModelCatalog.requiredSpaceBytes(spec.extractedBytes + spec.files.vocoderBytes)
        val avail = availableBytes()
        Log.i(
            TAG,
            "space|${spec.id}|manifest_dl=${spec.downloadBytes}|manifest_ext=${spec.extractedBytes}" +
                "|vocoder=${spec.files.vocoderBytes}|need=$need|avail=$avail|ok=${avail >= need}"
        )
        return avail >= need
    }

    fun downloadAndInstall(spec: ModelSpec, progress: Progress) {
        cancelled = false
        if (!NetPolicy.allowsNetwork(ConfigStore.netAllowMobileData(), NetState.onCellular(context))) {
            Log.w(TAG, "DOWNLOAD_BLOCKED|${spec.id}|reason=${NetPolicy.REASON_CELLULAR_DISALLOWED}")
            progress.onError("当前使用移动数据，已暂停下载。请在 Wi-Fi 下重试，或在设置中开启「允许使用移动数据流量」")
            return
        }
        val s = resolve(spec)
        val totalAll = s.downloadBytes + s.files.vocoderBytes
        val finalDir = File(modelsDir(), s.id)
        val part = partFile(s)
        try {
            if (!checkSpace(s)) {
                val needMb = ModelCatalog.requiredSpaceBytes(s.extractedBytes + s.files.vocoderBytes) / 1_000_000
                progress.onError("空间不足：约需 ${needMb}MB 可用空间")
                return
            }
            activeId = s.id

            // 若主模型已存在（例如用户先离线导入了主包），只需补齐独立声码器即可
            if (s.files.vocoder.isNotEmpty() &&
                s.files.vocoderUrl.isNotEmpty() &&
                File(finalDir, s.files.modelName).exists() &&
                !isInstalled(s)
            ) {
                val vocPart = File(context.filesDir, "models-staging/${s.id}-${s.files.vocoder}.part")
                val vocErr = downloadCompanionFile(s, vocPart, progress, 0L, s.files.vocoderBytes)
                if (vocErr != null) {
                    if (cancelled) {
                        progress.onCancelled(vocPart.length(), s.files.vocoderBytes)
                    } else {
                        progress.onError(vocErr)
                    }
                    return
                }
                val vocTarget = File(finalDir, s.files.vocoder)
                vocTarget.parentFile?.mkdirs()
                if (!vocPart.renameTo(vocTarget)) {
                    vocPart.copyTo(vocTarget, overwrite = true)
                    vocPart.delete()
                }
                File(finalDir, ".completed").writeText("ok")
                ConfigStore.setModelId(s.id)
                ConfigStore.notifyReload(context, "model_installed")
                progress.onProgress(s.files.vocoderBytes, s.files.vocoderBytes, "done")
                progress.onSuccess(s.id)
                return
            }

            part.parentFile?.mkdirs()
            val dlResult = downloadWithSources(s, part, progress, 0L, totalAll)
            if (!dlResult.ok) {
                if (cancelled) {
                    Log.i(TAG, "CANCELLED|${s.id}|part=${part.length()} 保留进度供续传")
                    progress.onCancelled(part.length(), totalAll)
                } else {
                    val hint = dlResult.error?.let { "下载失败（$it），可在顶部「下载源」切换其他镜像或测速后重试" }
                        ?: "下载失败，请在顶部「下载源」测速切换镜像或使用本地导入"
                    progress.onError(hint)
                }
                return
            }
            if (part.length() != s.downloadBytes) {
                Log.w(TAG, "SIZE_MISMATCH|${s.id}|got=${part.length()}|manifest=${s.downloadBytes}")
            }

            progress.onProgress(s.downloadBytes, totalAll, "verify")
            val sha = sha256(part)
            if (sha == null || cancelled) {
                Log.i(TAG, "CANCELLED_VERIFY|${s.id}|保留已下载整包供续传")
                progress.onCancelled(part.length(), totalAll)
                return
            }
            if (!sha.equals(s.sha256, ignoreCase = true)) {
                part.delete()
                progress.onError("SHA-256 校验失败（可能源站文件已更新或传输损坏），已清理残包")
                return
            }

            progress.onProgress(s.downloadBytes, totalAll, "extract")
            val staging = stagingDir(s.id)
            part.inputStream().use { input ->
                SafeExtractor.extract(
                    input = input,
                    stagingDir = staging,
                    archiveName = s.archiveName,
                    maxExtractedBytes = s.extractedBytes,
                    whitelist = null,
                    isCancelled = { cancelled },
                )
            }
            if (cancelled) {
                staging.deleteRecursively()
                progress.onCancelled(part.length(), totalAll)
                return
            }
            val root = ModelLayout.resolveRoot(staging, s.files.modelName)
            if (root == null) {
                Log.w(TAG, "LAYOUT_REJECT|${s.id}|model=${s.files.modelName}|staging=${staging.list()?.joinToString(",")}")
                staging.deleteRecursively()
                progress.onError("解压产物缺少模型文件（${s.files.modelName}）")
                return
            }
            if (s.files.vocoder.isNotEmpty() && s.files.vocoderUrl.isNotEmpty()) {
                val vocPart = File(context.filesDir, "models-staging/${s.id}-${s.files.vocoder}.part")
                val vocErr = downloadCompanionFile(s, vocPart, progress, s.downloadBytes, totalAll)
                if (vocErr != null) {
                    staging.deleteRecursively()
                    if (cancelled) {
                        progress.onCancelled(part.length() + vocPart.length(), totalAll)
                    } else {
                        progress.onError(vocErr)
                    }
                    return
                }
                val vocTarget = File(root, s.files.vocoder)
                if (!vocPart.renameTo(vocTarget)) {
                    vocPart.copyTo(vocTarget, overwrite = true)
                    vocPart.delete()
                }
            }
            progress.onProgress(totalAll, totalAll, "promote")
            SafeExtractor.promote(root, finalDir, "ok")
            ModelLayout.cleanupScaffold(staging, root)
            part.delete()
            ConfigStore.setModelId(s.id)
            ConfigStore.notifyReload(context, "model_installed")
            progress.onSuccess(s.id)
        } catch (t: Throwable) {
            if (cancelled) {
                Log.i(TAG, "CANCELLED|${s.id}|reason=${t.message}")
                progress.onCancelled(part.length(), totalAll)
            } else {
                Log.e(TAG, "download failed", t)
                progress.onError(ErrorSanitizer.sanitize(t.message ?: "下载安装失败", 160))
            }
        } finally {
            activeId = null
        }
    }

    /** 下载并校验独立附属文件（例如 Matcha-TTS 的 Vocos 声码器） */
    private fun downloadCompanionFile(
        spec: ModelSpec,
        targetPart: File,
        progress: Progress,
        progressOffset: Long,
        progressTotal: Long,
    ): String? {
        val rawUrl = spec.files.vocoderUrl
        val prefixes = ModelCatalog.MIRROR_PREFIXES
        val vocSpec = spec.copy(
            archiveName = spec.files.vocoder,
            primaryUrl = rawUrl,
            mirrorUrl = "${prefixes.first()}/$rawUrl",
            extraMirrors = prefixes.drop(1).map { "$it/$rawUrl" },
            downloadBytes = spec.files.vocoderBytes,
            sha256 = spec.files.vocoderSha256,
        )
        targetPart.parentFile?.mkdirs()
        progress.onProgress(progressOffset + targetPart.length(), progressTotal, "download_vocoder")
        val dlResult = downloadWithSources(vocSpec, targetPart, progress, progressOffset, progressTotal)
        if (!dlResult.ok) {
            return if (cancelled) "已取消" else "声码器下载失败：${dlResult.error ?: "网络异常"}"
        }
        val sha = sha256(targetPart) ?: return "已取消"
        if (!sha.equals(spec.files.vocoderSha256, ignoreCase = true)) {
            targetPart.delete()
            return "声码器 SHA-256 校验失败"
        }
        return null
    }

    private data class TransferResult(val ok: Boolean, val error: String? = null)

    /** 多源下载：自动模式先并发测速选优；指定模式直连首选源并在失败时回退其余源。 */
    private fun downloadWithSources(
        spec: ModelSpec,
        part: File,
        progress: Progress,
        progressOffset: Long,
        progressTotal: Long,
    ): TransferResult {
        val selected = ConfigStore.mirrorSelection()
        val rawCustom = ConfigStore.mirrorBase().trim()
        val isCompanion = spec.archiveName.endsWith(".onnx")
        // 主归档直链不误套用到独立声码器下载上；镜像前缀与目录基址则复用
        val effectiveCustom = if (isCompanion &&
            (rawCustom.endsWith(".tar.bz2") || rawCustom.endsWith(".tar.gz") ||
                rawCustom.endsWith(".tgz") || rawCustom.endsWith(".zip"))
        ) {
            ""
        } else {
            rawCustom
        }
        val candidates = MirrorResolver.orderedSources(spec, selected, effectiveCustom)
        if (candidates.isEmpty()) return TransferResult(false, "无可用下载地址")

        val stageName = if (progressOffset > 0L) "download_vocoder" else "download"
        val ordered = if (MirrorResolver.isAuto(selected)) {
            progress.onProgress(progressOffset + part.length(), progressTotal, "probe")
            val fastest = http.pickFastest(candidates) { cancelled } ?: return TransferResult(false, "已取消")
            listOf(fastest) + candidates.filter { it != fastest }
        } else {
            val chosenHost = MirrorResolver.hostLabel(candidates.first())
            progress.onSourceChanged(chosenHost)
            progress.onProgress(progressOffset + part.length(), progressTotal, stageName)
            candidates
        }

        var lastError: String? = null
        for (url in ordered) {
            if (cancelled) return TransferResult(false, "已取消")
            val host = MirrorResolver.hostLabel(url)
            progress.onSourceChanged(host)
            Log.i(TAG, "trying|${spec.id}|host=$host|url=$url")
            try {
                http.download(
                    url = url,
                    part = part,
                    expectedBytes = spec.downloadBytes,
                    onProgress = { b, _ ->
                        progress.onProgress(progressOffset + b, progressTotal, stageName)
                    },
                    isCancelled = { cancelled },
                )
                Log.i(TAG, "downloaded|${spec.id}|host=$host|bytes=${part.length()}")
                return TransferResult(true)
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "source failed $host ($url): $lastError")
                if (cancelled) return TransferResult(false, "已取消")
            }
        }
        return TransferResult(false, lastError)
    }

    private fun sha256(file: File): String? {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                if (cancelled) return null
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 删除模型（IM-204：下载进行中拒绝删除；当前模型被删则回退默认） */
    fun deleteModel(modelId: String): Boolean {
        if (activeId == modelId) {
            Log.w(TAG, "DELETE_REJECT|$modelId|reason=downloading")
            return false
        }
        val dir = File(modelsDir(), modelId)
        if (ConfigStore.modelId() == modelId) {
            ConfigStore.setModelId(ModelCatalog.DEFAULT_ID)
        }
        val ok = dir.deleteRecursively()
        ConfigStore.notifyReload(context, "model_deleted")
        return ok
    }

    /**
     * SAF 离线导入（IM-205 / RQ-204）：强制经 SafeExtractor，免存储权限。
     *
     * 支持：
     * - `.tar.bz2` / `.tar.gz` / `.tgz` / `.zip` 完整或主模型压缩包；
     * - `.onnx` 独立声码器文件（如 Matcha 的 `vocos-16khz-univ.onnx`）。
     */
    fun importFromStream(
        input: InputStream,
        archiveName: String,
        spec: ModelSpec,
        progress: Progress,
    ) {
        cancelled = false
        val s = resolve(spec)
        val lower = archiveName.lowercase()
        val finalDir = File(modelsDir(), s.id)
        try {
            if (!checkSpace(s)) {
                val needMb = ModelCatalog.requiredSpaceBytes(s.extractedBytes + s.files.vocoderBytes) / 1_000_000
                progress.onError("空间不足：约需 ${needMb}MB 可用空间")
                return
            }
            activeId = s.id

            // 分支 1：单独导入 Matcha 等模型所需的 .onnx 声码器
            if (lower.endsWith(".onnx") && s.files.vocoder.isNotEmpty()) {
                progress.onProgress(0L, s.files.vocoderBytes, "import")
                finalDir.mkdirs()
                val tmpVoc = File(context.filesDir, "models-staging/${s.id}-${s.files.vocoder}.import.part")
                tmpVoc.parentFile?.mkdirs()
                tmpVoc.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        written += n
                        if (written > s.files.vocoderBytes * 2L + 4_000_000L) {
                            throw IOException("声码器文件体积异常")
                        }
                        out.write(buf, 0, n)
                    }
                }
                if (tmpVoc.length() < 1024L) {
                    tmpVoc.delete()
                    progress.onError("导入的声码器文件过小或已损坏")
                    return
                }
                val vocTarget = File(finalDir, s.files.vocoder)
                if (!tmpVoc.renameTo(vocTarget)) {
                    tmpVoc.copyTo(vocTarget, overwrite = true)
                    tmpVoc.delete()
                }
                if (File(finalDir, s.files.modelName).exists()) {
                    File(finalDir, ".completed").writeText("imported")
                    ConfigStore.setModelId(s.id)
                    ConfigStore.notifyReload(context, "model_imported")
                    progress.onSuccess(s.id)
                } else {
                    progress.onError("已导入声码器 ${s.files.vocoder}，请继续导入主模型压缩包")
                }
                return
            }

            // 分支 2：导入归档压缩包
            progress.onProgress(0L, s.extractedBytes, "import")
            val staging = stagingDir(s.id)
            SafeExtractor.extract(input, staging, archiveName, s.extractedBytes, null) { cancelled }
            val root = ModelLayout.resolveRoot(staging, s.files.modelName)
            if (root == null) {
                staging.deleteRecursively()
                progress.onError("导入包缺少主模型文件（期望：${s.files.modelName}）")
                return
            }
            if (s.files.vocoder.isNotEmpty() && !File(root, s.files.vocoder).exists()) {
                val existingVoc = File(finalDir, s.files.vocoder)
                if (existingVoc.exists() && existingVoc.length() > 0L) {
                    existingVoc.copyTo(File(root, s.files.vocoder), overwrite = true)
                } else {
                    // 保留主模型（不写 .completed），提示用户补导入声码器或一键下载声码器
                    if (finalDir.exists()) finalDir.deleteRecursively()
                    finalDir.parentFile?.mkdirs()
                    if (!root.renameTo(finalDir)) {
                        root.copyRecursively(finalDir, overwrite = true)
                    }
                    ModelLayout.cleanupScaffold(staging, root)
                    progress.onError("主模型已导入，还缺少声码器 ${s.files.vocoder}（可点「下载并安装」自动补齐，或再导入 .onnx 文件）")
                    return
                }
            }
            SafeExtractor.promote(root, finalDir, "imported")
            ModelLayout.cleanupScaffold(staging, root)
            ConfigStore.setModelId(s.id)
            ConfigStore.notifyReload(context, "model_imported")
            progress.onSuccess(s.id)
        } catch (t: Throwable) {
            progress.onError(ErrorSanitizer.sanitize(t.message ?: "本地导入失败", 160))
        } finally {
            activeId = null
        }
    }

    /** 以纯本地缓存或内置清单解析模型（零网络阻塞）；异常时退回调用方传入的 spec。 */
    private fun resolve(spec: ModelSpec): ModelSpec =
        runCatching { ModelRegistry.specsLocalOnly(context).firstOrNull { it.id == spec.id } }
            .getOrNull() ?: spec

    companion object {
        private const val TAG = "ModelDownloader"

        /** 参考体积上限（清单字段防呆复用） */
        const val MAX_DECLARED_BYTES: Long = ModelLimits.MAX_BYTES
    }
}
