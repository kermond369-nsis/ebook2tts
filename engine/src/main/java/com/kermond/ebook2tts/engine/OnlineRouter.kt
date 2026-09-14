package com.kermond.ebook2tts.engine

import com.kermond.ebook2tts.core.NetPolicy
import com.kermond.ebook2tts.core.OnlineSettings

/**
 * 一次在线请求所需的最小参数（**请求级快照**：请求开始时取值，配置变更下一个请求生效）。
 */
data class OnlineRequest(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val voice: String,
    val style: String,
)

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
    fun select(
        onlineEnabled: Boolean,
        apiKey: String,
        keyKind: String,
        baseUrl: String,
        model: String,
        voice: String,
        style: String,
        tokenPlanAccepted: Boolean,
        allowMobileData: Boolean,
        onCellular: Boolean,
    ): BackendChoice {
        if (!onlineEnabled) return BackendChoice.Local("online_disabled")
        val key = apiKey.trim()
        if (key.isEmpty()) return BackendChoice.Local("no_key")
        val kind = OnlineSettings.normalizeKind(keyKind)
        if (kind == OnlineSettings.KIND_PLAN && !tokenPlanAccepted) {
            return BackendChoice.Local("tokenplan_not_accepted")
        }
        val blocked = NetPolicy.blockReason(allowMobileData, onCellular)
        if (blocked != null) return BackendChoice.Local(blocked)
        return BackendChoice.Online(
            request = OnlineRequest(
                baseUrl = OnlineSettings.resolveBaseUrl(kind, baseUrl),
                apiKey = key,
                model = model.trim().ifEmpty { OnlineSettings.DEFAULT_MODEL },
                voice = voice.trim().ifEmpty { OnlineSettings.DEFAULT_VOICE },
                style = style.trim(),
            ),
            warning = OnlineSettings.keyMismatchWarning(kind, key),
        )
    }
}

/**
 * 在线失败 → 回落决策（纯逻辑，见 AR-§4.8.3 红线：绝不为内部原因中止在途朗读）。
 *
 * 决策只取决于「是否已产出音频」与「采样率是否一致」——故障分类不影响动作，
 * 只影响日志 `ONLINE|fallback=local|reason=..` 的 reason。
 */
object OnlineFallback {
    fun decide(
        anyAudioEnqueued: Boolean,
        localSampleRate: Int,
        onlineSampleRate: Int,
    ): FallbackAction = when {
        !anyAudioEnqueued -> FallbackAction.RESTART_LOCAL
        localSampleRate == onlineSampleRate -> FallbackAction.SWITCH_LOCAL
        else -> FallbackAction.STAY_ONLINE
    }
}
