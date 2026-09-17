package com.kermond.ebook2tts.core

/**
 * 朗读路由模式（RQ-513 / RQ-514，P7 第二批）。
 *
 * 开局流程：先选「在线模式 / 本地模式」，再选「仅 X / 谁优先」⇒ 归约为本枚举四种取值。
 *
 * 语义约束（与 RQ-514「本地与在线**等价**」一致，**不得**把本地当兜底）：
 * - [ONLY_ONLINE]：只用在线；在线不可用时**不得**回落本地 —— 由上层如实报错（`allowsLocal=false`）；
 * - [ONLY_LOCAL]：只用本地；**不触网**（`allowsOnline=false`）；
 * - [PREFER_ONLINE]：优先在线，不可用回落本地（历史默认行为，向后兼容）；
 * - [PREFER_LOCAL]：优先本地，本地不可用回落在线。
 *
 * 本类为**纯逻辑**（无 Android 依赖），供引擎路由（`OnlineSelector`）与界面共用，
 * 判定口径不得在界面侧复刻。
 */
enum class RouteMode(val id: String) {
    ONLY_ONLINE("only_online"),
    ONLY_LOCAL("only_local"),
    PREFER_ONLINE("prefer_online"),
    PREFER_LOCAL("prefer_local"),
    ;

    /** 是否允许走在线链路 */
    val allowsOnline: Boolean get() = this != ONLY_LOCAL

    /** 是否允许走本地链路（false ⇒ 在线不可用时必须报错，禁止静默回落） */
    val allowsLocal: Boolean get() = this != ONLY_ONLINE

    /** 是否允许在首选链路不可用时回落到另一条链路 */
    val allowsFallback: Boolean get() = this == PREFER_ONLINE || this == PREFER_LOCAL

    /** 首选链路是否为在线 */
    val prefersOnline: Boolean get() = this == ONLY_ONLINE || this == PREFER_ONLINE

    companion object {
        /** 兼容旧行为：默认「优先在线，回落本地」 */
        val DEFAULT: RouteMode = PREFER_ONLINE

        /** 容错解析：未知/空 ⇒ [DEFAULT]（老版本写入或缺省时不炸） */
        fun parse(raw: String?): RouteMode =
            values().firstOrNull { it.id == raw?.trim()?.lowercase() } ?: DEFAULT

        /**
         * 由开局两步选择归约：
         * @param onlineMode true=在线模式、false=本地模式
         * @param only true=仅 X、false=谁优先
         */
        fun of(onlineMode: Boolean, only: Boolean): RouteMode = when {
            onlineMode && only -> ONLY_ONLINE
            onlineMode && !only -> PREFER_ONLINE
            !onlineMode && only -> ONLY_LOCAL
            else -> PREFER_LOCAL
        }

        /** 反解开局两步选择（供界面回显已保存的模式） */
        fun presetsOf(mode: RouteMode): Pair<Boolean, Boolean> =
            mode.prefersOnline to !mode.allowsFallback
    }
}
