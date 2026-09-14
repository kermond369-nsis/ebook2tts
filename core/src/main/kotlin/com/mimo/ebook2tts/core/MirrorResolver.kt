package com.mimo.ebook2tts.core

/**
 * 自定义镜像（IM-201 / RQ-201）：用户可配置一个镜像基地址，
 * 作为官方主源与官方镜像之外的**附加源**参与多源测速；非法配置一律忽略（回退官方源）。
 *
 * 纯逻辑、零依赖，便于单测。
 */
object MirrorResolver {

    /** 归档包扩展名（用于区分「直链」与「基址」） */
    private val ARCHIVE_EXT = listOf(".tar.bz2", ".tar.gz", ".tgz", ".tar", ".zip")

    /**
     * 生成自定义镜像 URL；配置非法时返回 null。
     *
     * 支持两种填法（用户决策 2026-09-14：允许自行填写**下载加速直链**）：
     * 1. **直链**：完整 URL（以归档名结尾，或末段带归档扩展名）→ **原样使用**；
     * 2. **基址**：目录前缀 → 自动拼 `/<archiveName>`。
     *
     * - 必须是 **https** URL（应用级 `usesCleartextTraffic=false`，明文 http 会被系统直接拒绝，
     *   故此处不接受 http，避免"配了却下不动"的坑）；
     * - 末位 `/` 自动清理。
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

    /** 把自定义镜像作为附加源并入模型（非法配置原样返回）。 */
    fun withCustomMirror(spec: ModelSpec, customBase: String?): ModelSpec {
        val url = customUrl(spec, customBase) ?: return spec
        if (spec.sources.contains(url)) return spec
        return spec.copy(extraMirrors = spec.extraMirrors + url)
    }

    /** 参与测速/回退的完整源列表：「官方主源 → 官方镜像 → 附加源（含自定义）」，去重保序。 */
    fun sources(spec: ModelSpec, customBase: String?): List<String> =
        withCustomMirror(spec, customBase).sources
}
