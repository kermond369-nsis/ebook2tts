package com.kermond.ebook2tts.engine

import com.kermond.ebook2tts.core.OnlineSettings
import com.kermond.ebook2tts.core.TtsProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单一模式在线路由器（[OnlineSelector]）纯逻辑验证：
 * 在线优先；弱网冷却 / 断网 / 蜂窝限制 / 开关关 / 配置未就绪均立即 0ms 回退系统自带 TTS。
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
        networkConnected: Boolean = true,
        weakNetworkBypassed: Boolean = false,
        timeoutMs: Int = OnlineSettings.DEFAULT_ONLINE_TIMEOUT_MS,
    ): RouteChoice = OnlineSelector.select(
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
        networkConnected = networkConnected,
        weakNetworkBypassed = weakNetworkBypassed,
        timeoutMs = timeoutMs,
    )

    @Test
    fun disabled_fallsBackToSystemTts() {
        assertEquals(RouteChoice.SystemFallback("online_disabled"), select(onlineEnabled = false))
    }

    @Test
    fun offline_fallsBackToSystemTtsImmediately() {
        assertEquals(RouteChoice.SystemFallback("offline"), select(networkConnected = false))
    }

    @Test
    fun weakNetworkCooldown_bypassesOnlineImmediately() {
        assertEquals(
            RouteChoice.SystemFallback("weak_network_cooldown"),
            select(weakNetworkBypassed = true),
        )
    }

    @Test
    fun blankKey_fallsBackToSystemTts() {
        assertEquals(RouteChoice.SystemFallback("no_key"), select(apiKey = "   "))
    }

    @Test
    fun planKey_requiresTokenPlanAcceptance() {
        assertEquals(
            RouteChoice.SystemFallback("tokenplan_not_accepted"),
            select(apiKey = "tp-xyz", keyKind = "plan", tokenPlanAccepted = false),
        )
        val online = select(apiKey = "tp-xyz", keyKind = "plan", tokenPlanAccepted = true) as RouteChoice.Online
        assertEquals(OnlineSettings.PLAN_BASE_URL, online.request.baseUrl)
        assertNull(online.warning)
    }

    @Test
    fun cellularWithoutPermission_fallsBackToSystemTts() {
        assertEquals(
            RouteChoice.SystemFallback("cellular_disallowed"),
            select(allowMobileData = false, onCellular = true),
        )
        assertTrue(select(allowMobileData = true, onCellular = true) is RouteChoice.Online)
        assertTrue(select(allowMobileData = false, onCellular = false) is RouteChoice.Online)
    }

    @Test
    fun defaultsBaseUrlAndTimeoutClamping() {
        val def = select(apiKey = "sk-1", timeoutMs = 500) as RouteChoice.Online
        assertEquals(OnlineSettings.BILLING_BASE_URL, def.request.baseUrl)
        assertEquals(OnlineSettings.DEFAULT_MODEL, def.request.model)
        assertEquals(OnlineSettings.DEFAULT_VOICE, def.request.voice)
        assertEquals(OnlineSettings.MIN_ONLINE_TIMEOUT_MS, def.request.timeoutMs)

        val custom = select(
            apiKey = "sk-1",
            baseUrl = "https://proxy.example.com/v1/",
            timeoutMs = 8_000,
        ) as RouteChoice.Online
        assertEquals("https://proxy.example.com/v1", custom.request.baseUrl)
        assertEquals(8_000, custom.request.timeoutMs)
    }

    @Test
    fun keyKindMismatch_warnsButProceeds() {
        val online = select(apiKey = "tp-xxx", keyKind = "billing") as RouteChoice.Online
        assertNotNull("前缀与所选类型不符必须给出告警", online.warning)
        assertTrue(online.warning!!.contains("Token Plan"))
        assertEquals(OnlineSettings.BILLING_BASE_URL, online.request.baseUrl)
    }

    @Test
    fun isOnlineConfigured_matrix() {
        assertFalse(OnlineSelector.isOnlineConfigured(false, "sk-1", "billing", false))
        assertFalse(OnlineSelector.isOnlineConfigured(true, "   ", "billing", false))
        assertFalse(OnlineSelector.isOnlineConfigured(true, "tp-1", "plan", false))
        assertTrue(OnlineSelector.isOnlineConfigured(true, "tp-1", "plan", true))
        assertTrue(OnlineSelector.isOnlineConfigured(true, "sk-1", "billing", false))

        assertTrue(
            OnlineSelector.isOnlineConfigured(
                true,
                "",
                "billing",
                false,
                providerKind = TtsProviderKind.OPENAI_COMPAT,
                baseUrl = "https://tts.example.com/v1",
            ),
        )
        assertFalse(
            OnlineSelector.isOnlineConfigured(
                true,
                "",
                "billing",
                false,
                providerKind = TtsProviderKind.OPENAI_COMPAT,
                baseUrl = "https://api.openai.com/v1",
            ),
        )
        assertTrue(
            OnlineSelector.isOnlineConfigured(
                true,
                "",
                "billing",
                false,
                providerKind = TtsProviderKind.CUSTOM_HTTP,
                baseUrl = "https://tts.example.com/synth",
            ),
        )
    }

    @Test
    fun openAiAndCustomProviders_selectCorrectly() {
        val openAi = OnlineSelector.select(
            onlineEnabled = true,
            apiKey = "",
            keyKind = "billing",
            baseUrl = "https://tts.example.com/v1/",
            model = "",
            voice = "",
            style = "",
            tokenPlanAccepted = false,
            allowMobileData = true,
            onCellular = false,
            roleEnabled = true,
            providerKind = TtsProviderKind.OPENAI_COMPAT,
            responseFormat = "wav",
            timeoutMs = 12_000,
        ) as RouteChoice.Online
        assertEquals("https://tts.example.com/v1", openAi.request.baseUrl)
        assertEquals("tts-1", openAi.request.model)
        assertEquals("alloy", openAi.request.voice)
        assertFalse(openAi.request.roleEnabled)
        assertTrue(openAi.request.usesNativeSpeed())
        assertEquals(12_000, openAi.request.timeoutMs)

        val missingCustomUrl = OnlineSelector.select(
            onlineEnabled = true,
            apiKey = "",
            keyKind = "billing",
            baseUrl = "http://insecure.example.com/tts",
            model = "m",
            voice = "v",
            style = "",
            tokenPlanAccepted = false,
            allowMobileData = true,
            onCellular = false,
            providerKind = TtsProviderKind.CUSTOM_HTTP,
        )
        assertEquals(RouteChoice.SystemFallback("no_base_url"), missingCustomUrl)
    }
}
