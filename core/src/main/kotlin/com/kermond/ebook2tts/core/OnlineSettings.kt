package com.kermond.ebook2tts.core

/**
 * 在线朗读设置（纯逻辑：域名解析与密钥类型判定；零 Android 依赖，可 JVM 单测）。
 *
 * 协议事实（复用甲方原项目 `MiMoApiClient`；**两类密钥与域名绑定，不可混用**）：
 * - 按量计费 API Key（`sk-` 前缀）→ `https://api.xiaomimimo.com/v1`
 * - Token Plan 密钥（`tp-` 前缀）→ `https://token-plan-cn.xiaomimimo.com/v1`
 *
 * 约定：密钥前缀与所选类型不一致时**只告警不阻断**（域名按所选类型解析，服务端给出最终判定）。
 */
object OnlineSettings {

    const val KIND_BILLING = "billing"
    const val KIND_PLAN = "plan"

    const val BILLING_BASE_URL = "https://api.xiaomimimo.com/v1"
    const val PLAN_BASE_URL = "https://token-plan-cn.xiaomimimo.com/v1"

    const val DEFAULT_VOICE = "白桦"
    const val DEFAULT_MODEL = "mimo-v2.5-tts"

    /**
     * 定制音色模型（官方文档实查 2026-07-15/09-15）：`user` 消息即音色设计描述，**不接受 `audio.voice`**。
     * 用于在线多角色（RQ-507）：按角色档案逐段造音色。
     */
    const val VOICEDESIGN_MODEL = "mimo-v2.5-tts-voicedesign"

    /** 文本模型（角色精标用；官方模型列表实查：`mimo-v2.5`）。注意：这是文本调用，非 TTS */
    const val DEFAULT_LLM_MODEL = "mimo-v2.5"

    /** 该模型是否为定制音色模型（协议分支依据：含 voicedesign 即 user 消息走音色描述） */
    fun isVoiceDesignModel(model: String): Boolean = model.contains("voicedesign")

    /** MiMo 流式 PCM16 单声道采样率（协议常量，24kHz；与 Kokoro int8 一致） */
    const val SAMPLE_RATE = 24000

    const val KEY_PREFIX_BILLING = "sk-"
    const val KEY_PREFIX_PLAN = "tp-"

    /** 归一枚举：未知一律按「按量计费」处理（保守默认） */
    fun normalizeKind(keyKind: String): String =
        if (keyKind.trim().equals(KIND_PLAN, ignoreCase = true)) KIND_PLAN else KIND_BILLING

    /** keyKind → 默认域名 */
    fun defaultBaseUrl(keyKind: String): String =
        if (normalizeKind(keyKind) == KIND_PLAN) PLAN_BASE_URL else BILLING_BASE_URL

    /** 解析生效 Base URL：自定义为空 → 按 keyKind 取默认域名；统一去首尾空白与尾斜杠 */
    fun resolveBaseUrl(keyKind: String, customBaseUrl: String): String {
        val custom = customBaseUrl.trim()
        val url = if (custom.isEmpty()) defaultBaseUrl(keyKind) else custom
        return url.trimEnd('/')
    }

    /** 依据 Key 前缀识别其所属密钥类型；无法识别返回 null */
    fun kindOfKey(apiKey: String): String? = when {
        apiKey.trim().startsWith(KEY_PREFIX_BILLING) -> KIND_BILLING
        apiKey.trim().startsWith(KEY_PREFIX_PLAN) -> KIND_PLAN
        else -> null
    }

    /**
     * 密钥前缀与所选类型不匹配的可读告警（null = 一致或无法判定）。
     * 不阻断调用：混用最终由服务端拒绝（401），此处仅提示。
     */
    fun keyMismatchWarning(keyKind: String, apiKey: String): String? {
        val kind = normalizeKind(keyKind)
        val actual = kindOfKey(apiKey) ?: return null
        if (actual == kind) return null
        return "密钥前缀与所选类型不一致：所选「${kindLabel(kind)}」，密钥前缀看起来是「${kindLabel(actual)}」"
    }

    fun kindLabel(keyKind: String): String =
        if (normalizeKind(keyKind) == KIND_PLAN) "Token Plan" else "按量计费"
}
