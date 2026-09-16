package com.kermond.ebook2tts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RQ-515 / RQ-516 本地模式性能警告判定（P7 / IM-543）。
 *
 * 判据来源：甲方 2026-09-16 口径 —— 低于骁龙 8 Gen 1+ / 低于天玑 9300 / 同等以下 ⇒ 告警；
 * 例外：小米玄戒（XRING）O1/O3 不告警。
 */
class LocalModeAdvisorTest {

    @Test
    fun `本机真实案例 SDM845 属于低配 应告警`() {
        val v = LocalModeAdvisor.judge(socModel = "SDM845", manufacturer = "Qualcomm", cores = 8)
        assertTrue("SDM845 低于骁龙 8 Gen 1，应告警", v.warn)
        assertEquals("snapdragon_before_8gen1", v.reason)
    }

    @Test
    fun `骁龙 8 Gen 1 及以上 不告警`() {
        listOf("SM8450", "SM8475", "SM8550", "SM8650", "SM8750").forEach {
            assertFalse("$it 应视为达阈值", LocalModeAdvisor.judge(it, "Qualcomm", 8).warn)
        }
        assertFalse(LocalModeAdvisor.judge("Snapdragon 8 Gen 3", "Qualcomm", 8).warn)
    }

    @Test
    fun `天玑 9300 及以上 不告警 更低代际 告警`() {
        assertFalse(LocalModeAdvisor.judge("Dimensity 9300", "MediaTek", 8).warn)
        assertFalse(LocalModeAdvisor.judge("MT6989", "MediaTek", 8).warn)
        assertTrue("天玑 1200 应告警", LocalModeAdvisor.judge("Dimensity 1200", "MediaTek", 8).warn)
        assertTrue("天玑 8100 应告警", LocalModeAdvisor.judge("MT6895", "MediaTek", 8).warn)
    }

    @Test
    fun `玄戒 XRING 族一律豁免（RQ-515 例外）`() {
        listOf("XRING O1", "XRING O3", "Xring O1", "玄戒 O1", "玄戒O3").forEach {
            val v = LocalModeAdvisor.judge(it, "Xiaomi", 10)
            assertFalse("$it 属例外，不应告警", v.warn)
            assertEquals("xring_exempt", v.reason)
        }
    }

    @Test
    fun `核数低于门槛 一律告警（与 SoC 型号无关）`() {
        assertTrue(LocalModeAdvisor.judge("Dimensity 9300", "MediaTek", 2).warn)
        assertEquals("cores_below_4", LocalModeAdvisor.judge("SM8750", "Qualcomm", 3).reason)
    }

    @Test
    fun `信息不足时 fail-open 不误伤`() {
        assertFalse(LocalModeAdvisor.judge("", "", 8).warn)                        // 取不到型号
        assertEquals("unknown_soc", LocalModeAdvisor.judge("", "", 8).reason)
        assertFalse(LocalModeAdvisor.judge("Tensor G3", "Google", 9).warn)         // 其它厂商
        assertFalse(LocalModeAdvisor.judge("Exynos 2400", "Samsung", 10).warn)
    }

    @Test
    fun `警告文案与需求书一致`() {
        assertEquals("性能不足，可能延迟极大", LocalModeAdvisor.WARNING_TEXT)
    }
    @Test
    fun `非小米厂商含 O1 的串不得被当作玄戒豁免（agy 复核收紧项）`() {
        // ① 低端骁龙 + 串含 O1：不得豁免，且应按老骁龙告警
        val v1 = LocalModeAdvisor.judge(socModel = "SM6115-O1", manufacturer = "Qualcomm", cores = 8)
        assertNotEquals("xring_exempt", v1.reason)
        assertTrue("低端骁龙含 O1 串应告警", v1.warn)

        // ② 非小米厂商、无法判定型号的串：绝不能豁免为玄戒（fail-open 不等于"玄戒"）
        val v2 = LocalModeAdvisor.judge(socModel = "POCO1", manufacturer = "Qualcomm", cores = 8)
        assertNotEquals("xring_exempt", v2.reason)

        // ③ 高性能骁龙 + 串含 O1：不告警（因为它是 8 Gen 1，而非因为"玄戒"）
        val v3 = LocalModeAdvisor.judge(socModel = "SM8450-O1", manufacturer = "Qualcomm", cores = 8)
        assertFalse(v3.warn)
        assertNotEquals("xring_exempt", v3.reason)

        // ④ 小米厂商 + 型号 O1 ⇒ 豁免
        val v4 = LocalModeAdvisor.judge(socModel = "O1", manufacturer = "Xiaomi", cores = 10)
        assertFalse(v4.warn)
        assertEquals("xring_exempt", v4.reason)
    }
}
