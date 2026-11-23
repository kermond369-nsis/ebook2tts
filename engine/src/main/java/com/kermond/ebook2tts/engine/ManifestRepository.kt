package com.kermond.ebook2tts.engine

import android.content.Context
import android.util.Log
import com.kermond.ebook2tts.core.MirrorResolver
import com.kermond.ebook2tts.core.ModelCatalog
import com.kermond.ebook2tts.core.ModelManifest
import com.kermond.ebook2tts.core.ModelSpec
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** 清单远程来源（可注入，便于单测与替换传输层） */
interface ManifestSource {
    fun fetch(url: String): String?
}

/**
 * 清单仓库（IM-201：远程可更新 + 内置兜底 + 自定义镜像）。
 *
 * 取用顺序（任一步失败即降级，绝不抛给调用方）：
 * 1. **远程** `manifest.json`（官方 raw → 国内可达加速前缀，逐个试；见 [URLS]）；
 * 2. **本地缓存**（上次成功的远程清单，6h TTL）；
 * 3. **内置兜底** `ModelCatalog.manifest()`（编译期常量，必定可用）。
 *
 * 安全口径：
 * - 远程清单写入缓存前必须通过 `ManifestCodec` 全字段校验；校验不过的清单不落盘、不采纳；
 * - **版本下限**＝内置清单版本，拒绝回滚（防降级投毒）；加速通道属第三方透传，
 *   清单签名校验列入后续里程碑（当前残余风险已在《勘误单 ERR-003》记录）。
 */
class ManifestRepository(
    private val context: Context,
    private val source: ManifestSource = HttpDownloader(),
) {

    /** 仅从本地缓存或内置兜底读取清单，绝不触发网络请求（供 UI 首屏与下载起手即时响应）。 */
    fun loadLocalOnly(): ModelManifest {
        val cachedJson = cacheFile().takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
        val cached = cachedJson?.let { ManifestCodec.parse(it) }?.takeIf { accepted(it.version) }
        if (cached != null) return cached
        return ModelCatalog.manifest()
    }

    /** 自定义镜像叠加后的纯本地可用模型列表（零网络阻塞）。 */
    fun specsLocalOnly(): List<ModelSpec> {
        val custom = ConfigStore.mirrorBase()
        return loadLocalOnly().models.map { MirrorResolver.withCustomMirror(it, custom) }
    }

    fun load(force: Boolean = false): ModelManifest {
        val cachedJson = cacheFile().takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
        val cached = cachedJson?.let { ManifestCodec.parse(it) }?.takeIf { accepted(it.version) }

        if (!force && cached != null && isFresh()) {
            Log.i(TAG, "MANIFEST|source=cache|version=${cached.version}")
            return cached
        }
        val now = System.currentTimeMillis()
        if (!force && lastRemoteAttemptAt > 0L && now - lastRemoteAttemptAt < RETRY_COOLDOWN_MS) {
            if (cached != null) {
                Log.i(TAG, "MANIFEST|source=cache(cooldown)|version=${cached.version}")
                return cached
            }
            val builtin = ModelCatalog.manifest()
            Log.i(TAG, "MANIFEST|source=builtin(cooldown)|version=${builtin.version}")
            return builtin
        }
        lastRemoteAttemptAt = now
        val remoteJson = fetchFirstValid()
        val remote = remoteJson?.let { ManifestCodec.parse(it) }
        if (remote != null && (cached == null || remote.version >= cached.version)) {
            writeCache(remoteJson)
            ConfigStore.setManifestFetchedAt(System.currentTimeMillis())
            Log.i(TAG, "MANIFEST|source=remote|version=${remote.version}|models=${remote.models.size}")
            return remote
        }
        if (cached != null) {
            Log.w(TAG, "MANIFEST|source=cache(stale)|version=${cached.version}")
            return cached
        }
        val builtin = ModelCatalog.manifest()
        Log.w(TAG, "MANIFEST|source=builtin|version=${builtin.version}")
        return builtin
    }

    /** 逐个来源尝试，返回首个「可取回且校验通过」的清单原文。 */
    private fun fetchFirstValid(): String? = pick(source, URLS)?.first

    /** 版本下限：拒绝低于内置清单版本的清单（防回滚投毒）。 */
    private fun accepted(version: Long): Boolean = version >= MIN_VERSION

    /** 自定义镜像（用户配置）叠加后的可用模型列表 */
    fun specs(force: Boolean = false): List<ModelSpec> {
        val custom = ConfigStore.mirrorBase()
        return load(force).models.map { MirrorResolver.withCustomMirror(it, custom) }
    }

    private fun isFresh(): Boolean {
        val at = ConfigStore.manifestFetchedAt()
        return at > 0L && System.currentTimeMillis() - at < TTL_MS
    }

    private fun cacheFile(): File = File(context.filesDir, "manifest-cache.json")

    private fun writeCache(json: String?) {
        if (json == null) return
        runCatching {
            cacheFile().parentFile?.mkdirs()
            cacheFile().writeText(json)
        }.onFailure { Log.w(TAG, "cache write failed: $it") }
    }

    companion object {
        private const val TAG = "ManifestRepo"

        /** 官方清单地址（仓库根 manifest.json，随版本可在线更新） */
        const val RAW_URL: String =
            "https://raw.githubusercontent.com/kermond369-nsis/ebook2tts/main/manifest.json"

        /**
         * 加速前缀：国内直连 `raw.githubusercontent.com` 易超时，优先走经 Range 206 实测可用的镜像节点。
         */
        val ACCELERATORS: List<String> = com.kermond.ebook2tts.core.ModelCatalog.MIRROR_PREFIXES

        /** 逐个尝试的清单地址 */
        val URLS: List<String> = listOf(RAW_URL) + ACCELERATORS.map { "$it/$RAW_URL" }

        /** 版本下限＝内置清单版本（防回滚） */
        val MIN_VERSION: Long = ModelCatalog.BUILTIN_MANIFEST_VERSION

        /** 缓存有效期：6 小时（避免每次启动都打网络） */
        private const val TTL_MS = 6L * 60L * 60L * 1000L

        /** 远程清单拉取失败后的冷却窗口：10 分钟（避免断网/超时反复阻塞下载与界面） */
        private const val RETRY_COOLDOWN_MS = 10L * 60L * 1000L

        @Volatile
        private var lastRemoteAttemptAt: Long = 0L

        /**
         * 纯逻辑（可单测）：按序尝试 [urls]，返回首个「可取回 + 校验通过 + 版本不低于下限」的清单。
         * 任一环节不合格立即换下一个来源；全部失败返回 null（由调用方降级到缓存/内置）。
         */
        fun pick(source: ManifestSource, urls: List<String>): Pair<String, ModelManifest>? {
            for (url in urls) {
                val json = source.fetch(url) ?: continue
                val parsed = ManifestCodec.parse(json) ?: continue
                if (parsed.version < MIN_VERSION) {
                    Log.w(TAG, "MANIFEST_REJECT|rollback|version=${parsed.version}|min=$MIN_VERSION")
                    continue
                }
                Log.i(TAG, "MANIFEST|url=$url|version=${parsed.version}")
                return json to parsed
            }
            return null
        }
    }
}

/**
 * 模型注册表（IM-201）：进程内缓存清单结果，供下载器 / UI / 迁移校验统一取用。
 * 配置变更（自定义镜像、清单刷新）后调用 `invalidate()`。
 */
object ModelRegistry {

    @Volatile
    private var cached: List<ModelSpec>? = null

    private val refreshInFlight = AtomicBoolean(false)

    fun specsLocalOnly(context: Context): List<ModelSpec> {
        val cur = cached
        if (cur != null) return cur
        val list = ManifestRepository(context).specsLocalOnly()
        cached = list
        return list
    }

    fun refreshAsyncIfNeeded(context: Context) {
        if (!refreshInFlight.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        Thread({
            try {
                cached = ManifestRepository(appCtx).specs(force = false)
            } catch (t: Throwable) {
                Log.w("ModelRegistry", "async manifest refresh skipped: ${t.javaClass.simpleName}")
            } finally {
                refreshInFlight.set(false)
            }
        }, "manifest-refresh").apply { isDaemon = true }.start()
    }

    fun specs(context: Context, force: Boolean = false): List<ModelSpec> {
        val cur = cached
        if (cur != null && !force) return cur
        val list = ManifestRepository(context).specs(force)
        cached = list
        return list
    }

    fun byId(context: Context, id: String?): ModelSpec {
        val list = specs(context)
        return list.firstOrNull { it.id == id } ?: list.first()
    }

    fun invalidate() {
        cached = null
    }
}
