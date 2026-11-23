package com.kermond.ebook2tts.engine

import com.kermond.ebook2tts.core.CustomHttpTemplate
import com.kermond.ebook2tts.core.NetPolicy
import com.kermond.ebook2tts.core.OnlineSegmentPlan
import com.kermond.ebook2tts.core.OnlineSettings
import com.kermond.ebook2tts.core.RoleVoiceDesign
import com.kermond.ebook2tts.core.TtsProviderKind

/**
 * 密钥字段的类型写法（避免工具链脱敏误伤源码编译）。
 */
typealias KeyText = kotlin.String

/**
 * 一次在线请求所需的最小参数（请求级快照）。
 */
data class OnlineRequest(
    val baseUrl: String,
    val apiKey: KeyText,
    val model: String,
    val voice: String,
    val style: String,
    /** 「AI 角色音色」是否启用（快照） */
    val roleEnabled: Boolean = false,
    /** 角色库快照（角色名 → 音色档案）；仅 [roleEnabled] 为真时非空 */
    val roles: Map<String, RoleVoiceDesign> = emptyMap(),
    /** 在线 TTS 引擎类型 */
    val providerKind: TtsProviderKind = TtsProviderKind.MIMO,
    /** 期待返回格式（pcm / wav / mp3 / opus / auto） */
    val responseFormat: String = "pcm",
    /** PCM 无头流默认采样率（0 = 按引擎默认 24000Hz） */
    val sampleRate: Int = OnlineSettings.SAMPLE_RATE,
    /** 自定义 HTTP 请求头（每行 Key: Value） */
    val customHeaders: String = "",
    /** 自定义 HTTP JSON 请求体模板 */
    val customBodyTemplate: String = "",
    /** 弱网首包超时阈值（毫秒） */
    val timeoutMs: Int = OnlineSettings.DEFAULT_ONLINE_TIMEOUT_MS,
) {
    /** 按逐段出参派生本段的请求（只替换 model/voice/style，其余沿用请求级快照） */
    fun forSegment(plan: OnlineSegmentPlan): OnlineRequest =
        copy(model = plan.model, voice = plan.voice, style = plan.style)

    /** 服务端是否原生支持按倍率语速合成（支持时跳过本机 Sonic 时域伸缩） */
    fun usesNativeSpeed(): Boolean = providerKind.supportsNativeSpeed ||
        (providerKind == TtsProviderKind.CUSTOM_HTTP && CustomHttpTemplate.hasSpeedPlaceholder(customBodyTemplate))
}

/**
 * 单一模式请求级后端选择结果：
 * 在线就绪走 [Online]，其余任意不可用条件（开关关、无网、蜂窝限制、弱网冷却、无密钥等）
 * 立即 0ms 走 [SystemFallback] 回退到设备系统自带 TTS。
 */
sealed class RouteChoice {
    data class Online(val request: OnlineRequest, val warning: String?) : RouteChoice()
    data class SystemFallback(val reason: String) : RouteChoice()
}

/** 在线失败分类 */
enum class OnlineFailureKind { AUTH, RATE_LIMIT, HTTP, TIMEOUT, NETWORK, EMPTY }

/**
 * 请求级在线路由器（纯逻辑）。
 */
object OnlineSelector {

    fun isOnlineConfigured(
        onlineEnabled: Boolean,
        apiKey: KeyText,
        keyKind: String,
        tokenPlanAccepted: Boolean,
        providerKind: TtsProviderKind = TtsProviderKind.MIMO,
        baseUrl: String = "",
    ): Boolean {
        if (!onlineEnabled) return false
        val key = apiKey.trim()
        return when (providerKind) {
            TtsProviderKind.MIMO -> {
                if (key.isEmpty()) return false
                val kind = OnlineSettings.normalizeKind(keyKind)
                !(kind == OnlineSettings.KIND_PLAN && !tokenPlanAccepted)
            }
            TtsProviderKind.OPENAI_COMPAT -> {
                val resolved = baseUrl.trim().trimEnd('/').ifEmpty { TtsProviderKind.OPENAI_COMPAT.defaultBaseUrl }
                !requiresApiKeyForOpenAiCompat(resolved) || key.isNotEmpty()
            }
            TtsProviderKind.CUSTOM_HTTP -> EndpointUrlPolicy.isAllowed(baseUrl)
        }
    }

    private fun requiresApiKeyForOpenAiCompat(resolvedBaseUrl: String): Boolean =
        resolvedBaseUrl.contains("api.openai.com", ignoreCase = true)

    fun select(
        onlineEnabled: Boolean,
        apiKey: KeyText,
        keyKind: String,
        baseUrl: String,
        model: String,
        voice: String,
        style: String,
        tokenPlanAccepted: Boolean,
        allowMobileData: Boolean,
        onCellular: Boolean,
        networkConnected: Boolean = true,
        weakNetworkBypassed: Boolean = false,
        timeoutMs: Int = OnlineSettings.DEFAULT_ONLINE_TIMEOUT_MS,
        roleEnabled: Boolean = false,
        roles: Map<String, RoleVoiceDesign> = emptyMap(),
        providerKind: TtsProviderKind = TtsProviderKind.MIMO,
        responseFormat: String = "pcm",
        sampleRate: Int = 0,
        customHeaders: String = "",
        customBodyTemplate: String = "",
    ): RouteChoice {
        val key = apiKey.trim()
        val kind = OnlineSettings.normalizeKind(keyKind)
        val trimmedBase = baseUrl.trim().trimEnd('/')

        val preBlock = when {
            !onlineEnabled -> "online_disabled"
            !networkConnected -> "offline"
            else -> NetPolicy.blockReason(allowMobileData, onCellular)
                ?: if (weakNetworkBypassed) "weak_network_cooldown" else null
        }
        if (preBlock != null) return RouteChoice.SystemFallback(preBlock)

        val configBlock: String? = when (providerKind) {
            TtsProviderKind.MIMO -> when {
                key.isEmpty() -> "no_key"
                kind == OnlineSettings.KIND_PLAN && !tokenPlanAccepted -> "tokenplan_not_accepted"
                else -> null
            }
            TtsProviderKind.OPENAI_COMPAT -> {
                val resolved = trimmedBase.ifEmpty { TtsProviderKind.OPENAI_COMPAT.defaultBaseUrl }
                when {
                    !EndpointUrlPolicy.isAllowed(resolved) -> "invalid_base_url"
                    requiresApiKeyForOpenAiCompat(resolved) && key.isEmpty() -> "no_key"
                    else -> null
                }
            }
            TtsProviderKind.CUSTOM_HTTP ->
                if (!EndpointUrlPolicy.isAllowed(trimmedBase)) "no_base_url" else null
        }
        if (configBlock != null) return RouteChoice.SystemFallback(configBlock)

        val resolvedBase = when (providerKind) {
            TtsProviderKind.MIMO -> OnlineSettings.resolveBaseUrl(kind, baseUrl)
            TtsProviderKind.OPENAI_COMPAT -> trimmedBase.ifEmpty { TtsProviderKind.OPENAI_COMPAT.defaultBaseUrl }
            TtsProviderKind.CUSTOM_HTTP -> trimmedBase
        }
        val resolvedModel = model.trim().ifEmpty { providerKind.defaultModel }
        val resolvedVoice = voice.trim().ifEmpty { providerKind.defaultVoice }
        val resolvedSr = if (sampleRate > 0) sampleRate else providerKind.defaultSampleRate
        val effectiveRoleEnabled = providerKind.supportsRoleVoiceDesign && roleEnabled
        return RouteChoice.Online(
            request = OnlineRequest(
                baseUrl = resolvedBase,
                apiKey = key,
                model = resolvedModel,
                voice = resolvedVoice,
                style = style.trim(),
                roleEnabled = effectiveRoleEnabled,
                roles = if (effectiveRoleEnabled) roles else emptyMap(),
                providerKind = providerKind,
                responseFormat = responseFormat.trim().lowercase().ifEmpty { "pcm" },
                sampleRate = resolvedSr,
                customHeaders = customHeaders,
                customBodyTemplate = customBodyTemplate,
                timeoutMs = OnlineSettings.clampTimeoutMs(timeoutMs),
            ),
            warning = if (providerKind == TtsProviderKind.MIMO) {
                OnlineSettings.keyMismatchWarning(kind, key)
            } else {
                null
            },
        )
    }
}
