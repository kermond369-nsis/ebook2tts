package com.kermond.ebook2tts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 在线设置：域名解析 / 前缀识别 / 不匹配告警（纯逻辑）。 */
class OnlineSettingsTest {

    @Test
    fun defaultBaseUrl_byKind() {
        assertEquals(OnlineSettings.BILLING_BASE_URL, OnlineSettings.defaultBaseUrl("billing"))
        assertEquals(OnlineSettings.PLAN_BASE_URL, OnlineSettings.defaultBaseUrl("plan"))
        // 未知/大小写 → 保守按按量计费
        assertEquals(OnlineSettings.BILLING_BASE_URL, OnlineSettings.defaultBaseUrl(""))
        assertEquals(OnlineSettings.BILLING_BASE_URL, OnlineSettings.defaultBaseUrl("weird"))
        assertEquals(OnlineSettings.PLAN_BASE_URL, OnlineSettings.defaultBaseUrl("PLAN"))
    }

    @Test
    fun resolveBaseUrl_blankFollowsKind() {
        assertEquals(OnlineSettings.BILLING_BASE_URL, OnlineSettings.resolveBaseUrl("billing", ""))
        assertEquals(OnlineSettings.PLAN_BASE_URL, OnlineSettings.resolveBaseUrl("plan", "   "))
    }

    @Test
    fun resolveBaseUrl_customTrimmed() {
        assertEquals(
            "https://custom.example.com/v1",
            OnlineSettings.resolveBaseUrl("billing", "  https://custom.example.com/v1/  "),
        )
        // 自定义只去尾斜杠，不改前缀
        assertEquals(
            "http://legacy.example.com/v1",
            OnlineSettings.resolveBaseUrl("plan", "http://legacy.example.com/v1"),
        )
    }

    @Test
    fun kindOfKey_prefix() {
        assertEquals(OnlineSettings.KIND_BILLING, OnlineSettings.kindOfKey("sk-abc123"))
        assertEquals(OnlineSettings.KIND_PLAN, OnlineSettings.kindOfKey("tp-abc123"))
        assertEquals(OnlineSettings.KIND_PLAN, OnlineSettings.kindOfKey("  tp-x  "))
        assertNull(OnlineSettings.kindOfKey(""))
        assertNull(OnlineSettings.kindOfKey("abc"))
    }

    @Test
    fun mismatchWarning_reportsBothKinds() {
        // 选按量计费却给了 tp- 密钥
        val w1 = OnlineSettings.keyMismatchWarning("billing", "tp-xxx")
        assertNotNull(w1)
        assertTrue(w1!!.contains("Token Plan"))

        // 选 Token Plan 却给了 sk- 密钥
        val w2 = OnlineSettings.keyMismatchWarning("plan", "sk-xxx")
        assertNotNull(w2)
        assertTrue(w2!!.contains("按量计费"))
    }

    @Test
    fun mismatchWarning_nullWhenConsistentOrUnknown() {
        assertNull(OnlineSettings.keyMismatchWarning("billing", "sk-xxx"))
        assertNull(OnlineSettings.keyMismatchWarning("plan", "tp-xxx"))
        assertNull(OnlineSettings.keyMismatchWarning("billing", "no-prefix"))
        assertNull(OnlineSettings.keyMismatchWarning("billing", ""))
    }
}
