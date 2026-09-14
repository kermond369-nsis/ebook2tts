package com.kermond.ebook2tts.core

/**
 * 模型清单（IM-201 / AR-§6.2）：**远程可更新的源清单 + 内置兜底 + 自定义镜像**。
 *
 * 解析与校验由 `:engine` 的 `ManifestCodec` 负责（Android 平台提供 org.json）；
 * 本类保持纯 JVM，可单测、可被 `:engine` 与 UI 共用。
 */
data class ModelManifest(
    /** 单调递增的清单版本；远程版本 ≥ 缓存版本才被采纳 */
    val version: Long,
    val updatedAt: String,
    val models: List<ModelSpec>,
) {
    fun byId(id: String?): ModelSpec? = models.firstOrNull { it.id == id }
}

/** 清单与体积的硬边界（防呆；超限即判非法，避免伪造清单造成爆盘）。 */
object ModelLimits {
    /** 单模型下载包 / 解压体积上限：8 GiB */
    const val MAX_BYTES: Long = 8L * 1024L * 1024L * 1024L

    /** sha256 十六进制小写 64 位 */
    val SHA256: Regex = Regex("^[0-9a-f]{64}$")

    /** 空间预检倍数：可用空间 ≥ 2.5 × 实测解压体积（RQ-206 / ERR-001） */
    const val SPACE_FACTOR_NUM: Long = 25L
    const val SPACE_FACTOR_DEN: Long = 10L
}
