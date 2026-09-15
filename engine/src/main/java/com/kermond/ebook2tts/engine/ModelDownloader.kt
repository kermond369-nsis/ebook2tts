package com.kermond.ebook2tts.engine

import android.content.Context
import android.util.Log
import com.kermond.ebook2tts.core.ModelCatalog
import com.kermond.ebook2tts.core.ModelLayout
import com.kermond.ebook2tts.core.ModelSpec
import com.kermond.ebook2tts.core.ModelLimits
import com.kermond.ebook2tts.core.NetPolicy
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * 模型下载与生命周期（IM-201/202/204/205、RQ-201/202/206）。
 *
 * - **清单驱动**：模型元数据与体积一律取自 `ModelRegistry`（远程清单 → 缓存 → 内置兜底），
 *   字节值为 ERR-001 逐字节实测值，全程 Long；
 * - **多源**：官方主源 → 官方镜像 → 附加源（含自定义镜像），先测速选优、失败顺序回退；
 * - **断点续传**：OkHttp `Range`（IM-202），服务器不支持则整包重下；
 * - **原子落盘**：解压进暂存区 → 校验 → `SafeExtractor.promote` + `.completed` 哨兵（IM-204）；
 * - **删除保护**：下载进行中的模型不可删除（IM-204）。
 */
class ModelDownloader(private val context: Context) {

    interface Progress {
        fun onProgress(bytes: Long, total: Long, stage: String)
        fun onSuccess(modelId: String)
        fun onError(message: String)

        /** 用户取消（P6 / IM-521）：默认按"非错误"处理，实现方可覆写以更新 UI 文案 */
        fun onCancelled(bytes: Long, total: Long) {}
    }

    @Volatile
    private var cancelled = false

    private val http = HttpDownloader()

    /** 正在下载/导入的模型 id（删除保护，IM-204） */
    @Volatile
    private var activeId: String? = null

    /**
     * 取消（P6 / IM-520）：置标志 + **立即中断在途 IO**（否则最长等一个 read timeout 才生效）。
     * `.part` 保留 ⇒ 下次仍可断点续传（甲方要求"保留进度"）。
     */
    fun cancel() {
        cancelled = true
        http.abort()
    }

    /** 当前是否处于"用户已取消"状态（供前台服务收尾判断，P6 / IM-520） */
    fun isCancelled(): Boolean = cancelled

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

    /** 空间预检（RQ-206 / ERR-001）：可用空间 ≥ 2.5 × 清单实测解压体积。 */
    fun checkSpace(spec: ModelSpec): Boolean {
        val need = ModelCatalog.requiredSpaceBytes(spec.extractedBytes)
        val avail = availableBytes()
        Log.i(
            TAG,
            "space|${spec.id}|manifest_dl=${spec.downloadBytes}|manifest_ext=${spec.extractedBytes}" +
                "|need=$need|avail=$avail|ok=${avail >= need}"
        )
        return avail >= need
    }

    fun downloadAndInstall(spec: ModelSpec, progress: Progress) {
        cancelled = false
        // 网络策略（core.NetPolicy）：蜂窝 + 未允许数据流量 → 禁止模型下载（含清单拉取/测速）
        if (!NetPolicy.allowsNetwork(ConfigStore.netAllowMobileData(), NetState.onCellular(context))) {
            Log.w(TAG, "DOWNLOAD_BLOCKED|${spec.id}|reason=${NetPolicy.REASON_CELLULAR_DISALLOWED}")
            progress.onError("当前使用移动数据，已暂停下载。请在 Wi-Fi 下重试，或在设置中开启「允许使用数据流量」")
            return
        }
        val s = resolve(spec)
        try {
            if (!checkSpace(s)) {
                val needMb = ModelCatalog.requiredSpaceBytes(s.extractedBytes) / 1_000_000
                progress.onError("空间不足：约需 ${needMb}MB 可用空间")
                return
            }
            activeId = s.id
            val part = partFile(s)
            part.parentFile?.mkdirs()

            if (!downloadWithSources(s, part, progress)) {
                // 用户取消 ≠ 下载失败：分开报，避免 UI 把取消显示成错误（IM-521）
                if (cancelled) {
                    Log.i(TAG, "CANCELLED|${s.id}|part=${part.length()} 保留进度供续传")
                    progress.onCancelled(part.length(), s.downloadBytes)
                } else {
                    progress.onError("下载失败，可尝试浏览器手动下载或配置自定义镜像")
                }
                return
            }
            // 清单实测体积对账（ERR-001）：不一致说明清单漂移，交由 SHA-256 兜底判定
            if (part.length() != s.downloadBytes) {
                Log.w(TAG, "SIZE_MISMATCH|${s.id}|got=${part.length()}|manifest=${s.downloadBytes}")
            }

            progress.onProgress(s.downloadBytes, s.downloadBytes, "verify")
            val sha = sha256(part)
            if (!sha.equals(s.sha256, ignoreCase = true)) {
                part.delete()
                progress.onError("SHA-256 校验失败，已清理残包")
                return
            }

            progress.onProgress(s.downloadBytes, s.downloadBytes, "extract")
            val staging = stagingDir(s.id)
            part.inputStream().use { input ->
                SafeExtractor.extract(
                    input = input,
                    stagingDir = staging,
                    archiveName = s.archiveName,
                    maxExtractedBytes = s.extractedBytes,
                    whitelist = null,
                )
            }
            // 官方归档顶层带目录名，落盘前必须先解析真正的模型根（§6.1 约定扁平布局）
            val root = ModelLayout.resolveRoot(staging, s.files.modelName)
            if (root == null) {
                Log.w(TAG, "LAYOUT_REJECT|${s.id}|model=${s.files.modelName}|staging=${staging.list()?.joinToString(",")}")
                staging.deleteRecursively()
                progress.onError("解压产物缺少模型文件")
                return
            }
            val finalDir = File(modelsDir(), s.id)
            SafeExtractor.promote(root, finalDir, "ok")
            ModelLayout.cleanupScaffold(staging, root)
            part.delete()
            ConfigStore.setModelId(s.id)
            ConfigStore.notifyReload(context, "model_installed")
            progress.onSuccess(s.id)
        } catch (t: Throwable) {
            Log.e(TAG, "download failed", t)
            progress.onError(t.message ?: "download failed")
        } finally {
            activeId = null
        }
    }

    /** 多源下载：测速选优 → 顺序回退；返回是否成功落盘。 */
    private fun downloadWithSources(spec: ModelSpec, part: File, progress: Progress): Boolean {
        val sources = spec.sources
        if (sources.isEmpty()) return false
        val fastest = http.pickFastest(sources) { cancelled }
        val ordered = if (fastest == null) sources else listOf(fastest) + sources.filter { it != fastest }
        for (url in ordered) {
            if (cancelled) return false
            try {
                http.download(
                    url = url,
                    part = part,
                    expectedBytes = spec.downloadBytes,
                    onProgress = { b, t -> progress.onProgress(b, t, "download") },
                    isCancelled = { cancelled },
                )
                Log.i(TAG, "downloaded|${spec.id}|src=$url|bytes=${part.length()}")
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "source failed $url: $t")
                if (cancelled) return false
            }
        }
        return false
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

    /** SAF 离线导入（IM-205 / RQ-204）：强制经 SafeExtractor，免存储权限。 */
    fun importFromStream(
        input: InputStream,
        archiveName: String,
        spec: ModelSpec,
        progress: Progress,
    ) {
        val s = resolve(spec)
        try {
            if (!checkSpace(s)) {
                progress.onError("空间不足")
                return
            }
            activeId = s.id
            val staging = stagingDir(s.id)
            SafeExtractor.extract(input, staging, archiveName, s.extractedBytes, null)
            val root = ModelLayout.resolveRoot(staging, s.files.modelName)
            if (root == null) {
                staging.deleteRecursively()
                progress.onError("导入包缺少模型文件")
                return
            }
            SafeExtractor.promote(root, File(modelsDir(), s.id), "imported")
            ModelLayout.cleanupScaffold(staging, root)
            ConfigStore.notifyReload(context, "model_imported")
            progress.onSuccess(s.id)
        } catch (t: Throwable) {
            progress.onError(t.message ?: "import failed")
        } finally {
            activeId = null
        }
    }

    /** 以清单为准解析模型（远程清单 → 缓存 → 内置兜底）；异常时退回调用方传入的 spec。 */
    private fun resolve(spec: ModelSpec): ModelSpec =
        runCatching { ModelRegistry.byId(context, spec.id) }.getOrDefault(spec)

    companion object {
        private const val TAG = "ModelDownloader"

        /** 参考体积上限（清单字段防呆复用） */
        const val MAX_DECLARED_BYTES: Long = ModelLimits.MAX_BYTES
    }
}
