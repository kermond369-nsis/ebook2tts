package com.kermond.ebook2tts.engine

import com.kermond.ebook2tts.core.OnlineSettings
import com.kermond.ebook2tts.core.RouteMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 请求级后端选择（[OnlineSelector]）与在线失败回落决策（[OnlineFallback]）纯逻辑验证。
 * 不触网、不依赖设备——故障分类的来源（401/500/超时）见 [OnlineBackendTest]。
 */
class OnlineRouterTest {

    private fun select(
        onlineEnabled: Boolean = true,
        apiKey: String = "sk-abc123",
        keyKind: String = "billing",
        baseUrl: String = "",
        model: String = "",
        voice: String = "",
        style: String = "",
        tokenPlanAccepted: Boolean = false,
        allowMobileData: Boolean = false,
        onCellular: Boolean = false,
        routeMode: RouteMode = RouteMode.DEFAULT,
        localModelAvailable: Boolean = true,
    ): BackendChoice = OnlineSelector.select(
        onlineEnabled = onlineEnabled,
        apiKey = apiKey,
        keyKind = keyKind,
        baseUrl = baseUrl,
        model = model,
        voice = voice,
        style = style,
        tokenPlanAccepted = tokenPlanAccepted,
        allowMobileData = allowMobileData,
        onCellular = onCellular,
        routeMode = routeMode,
        localModelAvailable = localModelAvailable,
    )

    @Test
    fun disabled_goesLocal() {
        assertEquals(BackendChoice.Local("online_disabled"), select(onlineEnabled = false))
    }

    @Test
    fun blankKey_goesLocal() {
        assertEquals(BackendChoice.Local("no_key"), select(apiKey = "   "))
    }

    @Test
    fun planKey_requiresTokenPlanAcceptance() {
        assertEquals(
            BackendChoice.Local("tokenplan_not_accepted"),
            select(apiKey = "tp-xyz", keyKind = "plan", tokenPlanAccepted = false),
        )
        val online = select(apiKey = "tp-xyz", keyKind = "plan", tokenPlanAccepted = true) as BackendChoice.Online
        assertEquals(OnlineSettings.PLAN_BASE_URL, online.request.baseUrl)
        assertNull(online.warning)
    }

    @Test
    fun cellularWithoutPermission_goesLocal() {
        assertEquals(
            BackendChoice.Local("cellular_disallowed"),
            select(allowMobileData = false, onCellular = true),
        )
        assertTrue(select(allowMobileData = true, onCellular = true) is BackendChoice.Online)
        assertTrue(select(allowMobileData = false, onCellular = false) is BackendChoice.Online)
    }

    @Test
    fun defaultsAndBaseUrlResolution() {
        val def = select(apiKey = "sk-1") as BackendChoice.Online
        assertEquals(OnlineSettings.BILLING_BASE_URL, def.request.baseUrl)
        assertEquals(OnlineSettings.DEFAULT_MODEL, def.request.model)
        assertEquals(OnlineSettings.DEFAULT_VOICE, def.request.voice)

        val custom = select(apiKey = "sk-1", baseUrl = "https://proxy.example.com/v1/") as BackendChoice.Online
        assertEquals("https://proxy.example.com/v1", custom.request.baseUrl)
    }

    @Test
    fun keyKindMismatch_warnsButProceeds() {
        val online = select(apiKey = "tp-xxx", keyKind = "billing") as BackendChoice.Online
        assertNotNull("前缀与所选类型不符必须给出告警", online.warning)
        assertTrue(online.warning!!.contains("Token Plan"))
        // 域名仍按所选 keyKind 解析（混用由服务端最终裁决）
        assertEquals(OnlineSettings.BILLING_BASE_URL, online.request.baseUrl)
    }

    @Test
    fun fallbackDecision_matrix() {
        // 尚无音频产出 → 整请求后续走本地（用户零感知）
        assertEquals(FallbackAction.RESTART_LOCAL, OnlineFallback.decide(false, 24000, 24000))
        assertEquals(FallbackAction.RESTART_LOCAL, OnlineFallback.decide(false, 16000, 24000))
        // 已有音频且采样率一致 → 本请求剩余部分切本地（无缝）
        assertEquals(FallbackAction.SWITCH_LOCAL, OnlineFallback.decide(true, 24000, 24000))
        // 已有音频但采样率不一致 → 起播后无法换源，本请求保持在线（降级链兜底）
        assertEquals(FallbackAction.STAY_ONLINE, OnlineFallback.decide(true, 22050, 24000))
    }

    // ───────── RQ-513 路由模式（P7 第二批）─────────

    @Test
    fun only_local_never_touches_online() {
        // 在线侧全部就绪（开关开 + 合法密钥）也不得走在线：仅本地 = 不触网
        val c = select(routeMode = RouteMode.ONLY_LOCAL, tokenPlanAccepted = true)
        assertEquals(BackendChoice.Local("only_local"), c)
    }

    @Test
    fun prefer_local_uses_local_when_model_available() {
        assertEquals(BackendChoice.Local("prefer_local"), select(routeMode = RouteMode.PREFER_LOCAL))
    }

    @Test
    fun prefer_local_falls_back_to_online_when_local_unavailable() {
        val c = select(routeMode = RouteMode.PREFER_LOCAL, localModelAvailable = false)
        assertTrue("本地不可用 ⇒ 回落在线", c is BackendChoice.Online)
    }

    @Test
    fun prefer_online_keeps_legacy_behavior() {
        assertTrue("缺省（未设置）必须与旧行为一致", select() is BackendChoice.Online)
        assertTrue(select(routeMode = RouteMode.PREFER_ONLINE) is BackendChoice.Online)
        assertEquals(BackendChoice.Local("online_disabled"), select(onlineEnabled = false, routeMode = RouteMode.PREFER_ONLINE))
    }

    @Test
    fun only_online_blocks_instead_of_silent_local_fallback() {
        // 在线不可用（密钥为空）⇒ 硬阻断并携带原始原因，供上层如实报错
        val blocked = select(routeMode = RouteMode.ONLY_ONLINE, apiKey = "   ")
        assertEquals("only_online_blocked:no_key", (blocked as BackendChoice.Local).reason)
        assertEquals("no_key", OnlineSelector.hardBlockReason(blocked))
        // 在线可用 ⇒ 正常在线且非阻断
        val ok = select(routeMode = RouteMode.ONLY_ONLINE)
        assertTrue(ok is BackendChoice.Online)
        assertNull(OnlineSelector.hardBlockReason(ok))
        // 开关关闭同样是阻断（而非回落本地）
        assertEquals("online_disabled", OnlineSelector.hardBlockReason(select(onlineEnabled = false, routeMode = RouteMode.ONLY_ONLINE)))
    }
}
