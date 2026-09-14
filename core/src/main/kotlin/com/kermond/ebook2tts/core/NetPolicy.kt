package com.kermond.ebook2tts.core

/**
 * 网络策略（纯函数，零 Android 依赖，可 JVM 单测）。
 *
 * 规则：**「允许使用数据流量」关闭时，蜂窝网络下禁止一切联网行为**
 * （在线朗读与模型下载共用同一判定）。
 */
object NetPolicy {

    /** 打点/日志用原因标记 */
    const val REASON_CELLULAR_DISALLOWED = "cellular_disallowed"

    /** 是否允许联网：允许流量，或当前不在蜂窝网络 */
    fun allowsNetwork(allowMobileData: Boolean, onCellular: Boolean): Boolean =
        allowMobileData || !onCellular

    /** 被禁止时返回原因；允许时返回 null */
    fun blockReason(allowMobileData: Boolean, onCellular: Boolean): String? =
        if (allowsNetwork(allowMobileData, onCellular)) null else REASON_CELLULAR_DISALLOWED
}
