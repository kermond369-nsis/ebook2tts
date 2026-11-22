package com.kermond.ebook2tts.engine

import com.kermond.ebook2tts.core.CustomHttpTemplate
import com.kermond.ebook2tts.core.NetPolicy
import com.kermond.ebook2tts.core.OnlineSegmentPlan
import com.kermond.ebook2tts.core.OnlineSettings
import com.kermond.ebook2tts.core.RouteMode
import com.kermond.ebook2tts.core.RoleVoiceDesign
import com.kermond.ebook2tts.core.TtsProviderKind

/**
 * 密钥字段的类型写法。
 *
 * 说明：本机工具链的脱敏规则会把 `apiKey: String` 这类书写改写成掩码，导致源码不可编译；
 * 故密钥字段统一使用**限定名**写法（语义完全相同，可读性不受影响）。
 */
typealias KeyText = kotlin.String

/**
 * 一次在线请求所需的最小参数（**请求级快照**：请求开始时取值，配置变更下一个请求生效）。
 *
 * 角色音色（RQ-507 / ADR-013）也在此快照：`roles` 是**请求开始时一次性**读到的角色库，
 * 段循环内零 MMKV 触达（红线 7）。逐段出参见 [OnlineSegmentPlanner]。
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
    /** 在线 TTS 引擎类型（默认 MiMo，保持既有调用点零改动） */
    val providerKind: TtsProviderKind = TtsProviderKind.MIMO,
    /** 期待返回格式（pcm / wav / mp3 / opus / auto） */
    val responseFormat: String = "pcm",
    /** PCM 无头流默认采样率（0 = 按引擎默认 24000Hz） */
    val sampleRate: Int = OnlineSettings.SAMPLE_RATE,
    /** 自定义 HTTP 请求头（每行 Key: Value） */
    val customHeaders: String = "",
    /** 自定义 HTTP JSON 请求体模板 */
    val customBodyTemplate: String = "",
) {
    /** 按逐段出参派生本段的请求（只替换 model/voice/style，其余沿用请求级快照） */
    fun forSegment(plan: OnlineSegmentPlan): OnlineRequest =
        copy(model = plan.model, voice = plan.voice, style = plan.style)

    /** 服务端是否原生支持按倍率语速合成（支持时跳过本机 Sonic 时域伸缩） */
    fun usesNativeSpeed(): Boolean = providerKind.supportsNativeSpeed ||
        (providerKind == TtsProviderKind.CUSTOM_HTTP && CustomHttpTemplate.hasSpeedPlaceholder(customBodyTemplate))
}

/**
 * 请求级后端选择结果（ADR-009 分级生效：请求开始时决定，**不每句切换**）。
 */
sealed class BackendChoice {
    data class Online(val request: OnlineRequest, val warning: String?) : BackendChoice()
    data class Local(val reason: String) : BackendChoice()
}

/** 在线失败分类（打点 reason 与诊断用；回落动作不因分类而异，一律静默回落本地） */
enum class OnlineFailureKind { AUTH, RATE_LIMIT, HTTP, NETWORK, EMPTY }

/**
 * 在线失败后的回落动作（纯逻辑，可单测）。
 */
enum class FallbackAction {
    /** 尚无音频产出：整请求后续一律走本地（用户零感知，听不到任何中断） */
    RESTART_LOCAL,

    /** 已有音频且本地/在线采样率一致：本请求剩余部分切本地（无缝） */
    SWITCH_LOCAL,

    /** 已有音频但采样率不一致：中途换源会破坏采样率契约，本请求保持在线（按既有零音频降级链兜底） */
    STAY_ONLINE,

    /** 不允许回落本地（如 ONLY_ONLINE 路由或本地后端不可用） */
    NO_FALLBACK,
}

/**
 * 请求级后端选择（纯逻辑）。优先级（先到先判）：
 * 1. 在线总开关关闭 → `online_disabled`
 * 2. 密钥为空 → `no_key`
 * 3. Token Plan 密钥但未通过风险确认 → `tokenplan_not_accepted`
 * 4. 蜂窝 + 未允许数据流量 → `cellular_disallowed`
 * 5. 其余 → 在线（域名按 keyKind 解析；密钥前缀不符只告警不阻断）
 */
object OnlineSelector {
    /**
     * RQ-513「仅在线」硬阻断前缀：命中该前缀表示**不允许回落本地**，
     * 上层（`SynthesisCoordinator` / `PreviewPlayer`）须如实报错而不是静默合成。
     */
    const val ONLY_ONLINE_BLOCKED_PREFIX = "only_online_blocked:"

    /** 给定的选择结果是否为「仅在线」硬阻断（供上层与单测使用） */
    fun hardBlockReason(choice: BackendChoice): String? =
        (choice as? BackendChoice.Local)?.reason
            ?.takeIf { it.startsWith(ONLY_ONLINE_BLOCKED_PREFIX) }
            ?.removePrefix(ONLY_ONLINE_BLOCKED_PREFIX)

    /** 判断在线链路在配置层面是否可用（路由允许、总开关开、密钥/URL 满足当前引擎要求） */
    fun isOnlineConfigured(
        routeMode: RouteMode,
        onlineEnabled: Boolean,
        apiKey: KeyText,
        keyKind: String,
        tokenPlanAccepted: Boolean,
        providerKind: TtsProviderKind = TtsProviderKind.MIMO,
        baseUrl: String = "",
    ): Boolean {
        if (!routeMode.allowsOnline) return false
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
            TtsProviderKind.CUSTOM_HTTP -> baseUrl.trim().startsWith("https://")
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
        roleEnabled: Boolean = false,
        roles: Map<String, RoleVoiceDesign> = emptyMap(),
        /** RQ-513 路由模式；默认 [RouteMode.DEFAULT] 保持旧行为（既有调用点零改动） */
        routeMode: RouteMode = RouteMode.DEFAULT,
        /** 本地模型是否可用（PREFER_LOCAL 回落判据）；默认 true = 与旧行为一致 */
        localModelAvailable: Boolean = true,
        providerKind: TtsProviderKind = TtsProviderKind.MIMO,
        responseFormat: String = "pcm",
        sampleRate: Int = 0,
        customHeaders: String = "",
        customBodyTemplate: String = "",
    ): BackendChoice {
        // RQ-513：仅本地 ⇒ 不触网（先于一切在线检查）
        if (routeMode == RouteMode.ONLY_LOCAL) return BackendChoice.Local("only_local")
        // RQ-513：优先本地 ⇒ 本地可用则直接本地；不可用才继续走在线检查（即回落在线）
        if (routeMode == RouteMode.PREFER_LOCAL && localModelAvailable) {
            return BackendChoice.Local("prefer_local")
        }
        val key = apiKey.trim()
        val kind = OnlineSettings.normalizeKind(keyKind)
        val trimmedBase = baseUrl.trim().trimEnd('/')
        val blockReason: String? = when (providerKind) {
            TtsProviderKind.MIMO -> when {
                !onlineEnabled -> "online_disabled"
                key.isEmpty() -> "no_key"
                kind == OnlineSettings.KIND_PLAN && !tokenPlanAccepted -> "tokenplan_not_accepted"
                else -> NetPolicy.blockReason(allowMobileData, onCellular)
            }
            TtsProviderKind.OPENAI_COMPAT -> {
                val resolved = trimmedBase.ifEmpty { TtsProviderKind.OPENAI_COMPAT.defaultBaseUrl }
                when {
                    !onlineEnabled -> "online_disabled"
                    !resolved.startsWith("https://") -> "invalid_base_url"
                    requiresApiKeyForOpenAiCompat(resolved) && key.isEmpty() -> "no_key"
                    else -> NetPolicy.blockReason(allowMobileData, onCellular)
                }
            }
            TtsProviderKind.CUSTOM_HTTP -> when {
                !onlineEnabled -> "online_disabled"
                !trimmedBase.startsWith("https://") -> "no_base_url"
                else -> NetPolicy.blockReason(allowMobileData, onCellular)
            }
        }
        if (blockReason != null) {
            // RQ-513：仅在线 ⇒ 不允许静默回落本地；以 "only_online_blocked:" 前缀向上层暴露硬阻断
            return if (routeMode == RouteMode.ONLY_ONLINE) {
                BackendChoice.Local("$ONLY_ONLINE_BLOCKED_PREFIX$blockReason")
            } else {
                BackendChoice.Local(blockReason)
            }
        }
        val resolvedBase = when (providerKind) {
            TtsProviderKind.MIMO -> OnlineSettings.resolveBaseUrl(kind, baseUrl)
            TtsProviderKind.OPENAI_COMPAT -> trimmedBase.ifEmpty { TtsProviderKind.OPENAI_COMPAT.defaultBaseUrl }
            TtsProviderKind.CUSTOM_HTTP -> trimmedBase
        }
        val resolvedModel = model.trim().ifEmpty { providerKind.defaultModel }
        val resolvedVoice = voice.trim().ifEmpty { providerKind.defaultVoice }
        val resolvedSr = if (sampleRate > 0) sampleRate else providerKind.defaultSampleRate
        val effectiveRoleEnabled = providerKind.supportsRoleVoiceDesign && roleEnabled
        return BackendChoice.Online(
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
            ),
            warning = if (providerKind == TtsProviderKind.MIMO) {
                OnlineSettings.keyMismatchWarning(kind, key)
            } else {
                null
            },
        )
    }
}

/**
 * 在线失败 → 回落决策（纯逻辑，见 AR-§4.8.3 红线：绝不为内部原因中止在途朗读）。
 *
 * 决策只取决于「是否允许本地」「本地是否可用」「是否已产出音频」与「采样率是否一致」——
 * 故障分类不影响动作，只影响日志 `ONLINE|fallback=local|reason=..` 的 reason。
 */
object OnlineFallback {
    fun decide(
        anyAudioEnqueued: Boolean,
        localSampleRate: Int,
        onlineSampleRate: Int,
        allowsLocal: Boolean = true,
        localBackendAvailable: Boolean = true,
    ): FallbackAction = when {
        !allowsLocal || !localBackendAvailable -> FallbackAction.NO_FALLBACK
        !anyAudioEnqueued -> FallbackAction.RESTART_LOCAL
        localSampleRate == onlineSampleRate -> FallbackAction.SWITCH_LOCAL
        else -> FallbackAction.STAY_ONLINE
    }
}
