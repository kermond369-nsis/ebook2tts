package com.kermond.ebook2tts.core

/**
 * 镜像预设节点：供下载下拉选择与多源并发测速共用（纯逻辑，零 Android 依赖）。
 */
data class MirrorPreset(
    val id: String,
    val label: String,
    val prefix: String,
)

/**
 * 自定义与预设镜像解析（IM-201 / RQ-201）：
 * 支持自动测速选优、下拉手动指定镜像节点、GitHub 官方直连、自定义镜像（加速前缀 / 基址 / 完整直链）。
 */
object MirrorResolver {

    const val MIRROR_AUTO = "auto"
    const val MIRROR_DIRECT = "direct"
    const val MIRROR_CUSTOM = "custom"

    val PRESETS: List<MirrorPreset> = listOf(
        MirrorPreset(MIRROR_AUTO, "自动测速选最快（推荐）", ""),
        MirrorPreset(ModelCatalog.ACC_PRIMARY, "gh-proxy.com（国内加速）", ModelCatalog.ACC_PRIMARY),
        MirrorPreset(ModelCatalog.ACC_ORG, "gh-proxy.org（备用加速）", ModelCatalog.ACC_ORG),
        MirrorPreset(ModelCatalog.ACC_EDGEONE, "EdgeOne 加速镜像", ModelCatalog.ACC_EDGEONE),
        MirrorPreset(ModelCatalog.ACC_CDN, "CDN 加速镜像", ModelCatalog.ACC_CDN),
        MirrorPreset(MIRROR_DIRECT, "GitHub 官方直连", ""),
        MirrorPreset(MIRROR_CUSTOM, "自定义镜像 / 直链", ""),
    )

    private val KNOWN_PROXY_HOSTS: Set<String> =
        ModelCatalog.MIRROR_PREFIXES.map { hostLabel(it) }.toSet()

    /** 归档包 / 单模型扩展名（用于区分「直链」与「基址」） */
    private val ARCHIVE_EXT = listOf(".tar.bz2", ".tar.gz", ".tgz", ".tar", ".zip", ".onnx")

    /**
     * 生成自定义镜像 URL；配置非法时返回 null。
     *
     * 支持三种填法：
     * 1. **直链**：完整 URL（以归档名或 `.tar.bz2/.tar.gz/.zip/.onnx` 等扩展名结尾）→ 原样使用；
     * 2. **GitHub 加速前缀**：匹配内置/常见加速根域名 → 拼 `<prefix>/<primaryUrl>`；
     * 3. **基址**：目录前缀 → 自动拼 `/<archiveName>`。
     */
    fun customUrl(spec: ModelSpec, customBase: String?): String? {
        val raw = customBase?.trim() ?: return null
        val base = raw.trimEnd('/')
        if (base.isEmpty()) return null
        if (!base.startsWith("https://") || base.length <= "https://".length) return null
        if (spec.archiveName.isBlank()) return null
        if (base.endsWith("/${spec.archiveName}")) return base
        val last = base.substringAfterLast('/')
        if (ARCHIVE_EXT.any { last.endsWith(it) }) return base
        return "$base/${spec.archiveName}"
    }

    /** 支持「内置镜像前缀 / GitHub 代理前缀 / 目录基址 / 完整直链」的统一解析。 */
    fun resolveProxyOrBaseUrl(spec: ModelSpec, mirrorOrBase: String?): String? {
        val raw = mirrorOrBase?.trim() ?: return null
        val base = raw.trimEnd('/')
        if (!base.startsWith("https://") || base.length <= "https://".length) return null
        val isKnownPrefix = ModelCatalog.MIRROR_PREFIXES.any { it.equals(base, ignoreCase = true) } ||
            (hostLabel(base) in KNOWN_PROXY_HOSTS && !base.substringAfter("https://").contains('/'))
        if (isKnownPrefix && spec.primaryUrl.startsWith("https://")) {
            return "$base/${spec.primaryUrl}"
        }
        return customUrl(spec, base)
    }

    /** 把自定义镜像作为附加源并入模型（保留官方首位的现有契约）。 */
    fun withCustomMirror(spec: ModelSpec, customBase: String?): ModelSpec {
        val url = customUrl(spec, customBase) ?: return spec
        if (spec.sources.contains(url)) return spec
        return spec.copy(extraMirrors = spec.extraMirrors + url)
    }

    /** 参与测速/回退的完整源列表（既有顺序：官方主源 → 官方镜像 → 附加源）。 */
    fun sources(spec: ModelSpec, customBase: String?): List<String> =
        withCustomMirror(spec, customBase).sources

    /**
     * 按用户选择的下拉策略排列下载源（首项为首选，后续依次为回退链路）。
     */
    fun orderedSources(
        spec: ModelSpec,
        selectedMirror: String,
        customBase: String?,
    ): List<String> {
        val allBase = spec.sources
        val mirrorsOnly = allBase.filter { it != spec.primaryUrl }
        val customResolved = resolveProxyOrBaseUrl(spec, customBase)
        val mirrorFirstFallback = (listOfNotNull(customResolved) + mirrorsOnly + listOf(spec.primaryUrl))
            .filter { it.isNotBlank() }
            .distinct()

        return when (selectedMirror.trim()) {
            "", MIRROR_AUTO -> mirrorFirstFallback
            MIRROR_DIRECT -> (listOf(spec.primaryUrl) + mirrorsOnly + listOfNotNull(customResolved))
                .filter { it.isNotBlank() }
                .distinct()
            MIRROR_CUSTOM -> if (customResolved != null) {
                (listOf(customResolved) + mirrorsOnly + listOf(spec.primaryUrl)).distinct()
            } else {
                mirrorFirstFallback
            }
            else -> {
                val chosenUrl = resolveProxyOrBaseUrl(spec, selectedMirror)
                if (chosenUrl != null) {
                    (listOf(chosenUrl) + mirrorFirstFallback).distinct()
                } else {
                    mirrorFirstFallback
                }
            }
        }
    }

    /** 从 HTTPS URL 提取可读节点名（如 `gh-proxy.com` / `github.com`）。 */
    fun hostLabel(url: String): String {
        val trimmed = url.trim()
        val afterProto = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed.substring(8)
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed.substring(7)
            else -> trimmed
        }
        return afterProto.substringBefore('/').substringBefore(':').lowercase().ifEmpty { trimmed }
    }

    /** 当前是否处于“自动测速选最快”策略（空串或 `auto`）。 */
    fun isAuto(selectedMirror: String): Boolean =
        selectedMirror.trim().let { it.isEmpty() || it == MIRROR_AUTO }

    /** 下载启动首阶段：自动模式为 `probe`（测速选源），手动指定源直接进入 `download`。 */
    fun initialPhase(selectedMirror: String): String =
        if (isAuto(selectedMirror)) "probe" else "download"
}
