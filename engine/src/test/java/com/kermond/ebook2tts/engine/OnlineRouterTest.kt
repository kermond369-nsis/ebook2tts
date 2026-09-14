package com.kermond.ebook2tts.engine

import com.kermond.ebook2tts.core.OnlineSettings
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
}
