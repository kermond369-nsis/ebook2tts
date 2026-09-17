package com.kermond.ebook2tts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RQ-513 / RQ-514 路由模式（P7 第二批）。
 * 覆盖：解析容错、两步选择归约、反解、以及"仅在线不得回落本地 / 仅本地不触网"这两条硬约束。
 */
class RouteModeTest {

    @Test
    fun `解析 合法值 未知值 空值`() {
        assertEquals(RouteMode.ONLY_ONLINE, RouteMode.parse("only_online"))
        assertEquals(RouteMode.ONLY_LOCAL, RouteMode.parse("ONLY_LOCAL"))   // 大小写容错
        assertEquals(RouteMode.PREFER_LOCAL, RouteMode.parse(" prefer_local "))
        assertEquals(RouteMode.PREFER_ONLINE, RouteMode.parse("nonsense"))  // 未知 ⇒ 默认
        assertEquals(RouteMode.PREFER_ONLINE, RouteMode.parse(null))
        assertEquals(RouteMode.PREFER_ONLINE, RouteMode.parse(""))
    }

    @Test
    fun `两步选择归约四种组合`() {
        assertEquals(RouteMode.ONLY_ONLINE, RouteMode.of(onlineMode = true, only = true))
        assertEquals(RouteMode.PREFER_ONLINE, RouteMode.of(onlineMode = true, only = false))
        assertEquals(RouteMode.ONLY_LOCAL, RouteMode.of(onlineMode = false, only = true))
        assertEquals(RouteMode.PREFER_LOCAL, RouteMode.of(onlineMode = false, only = false))
    }

    @Test
    fun `反解与归约互逆`() {
        for (m in RouteMode.values()) {
            val (onlineMode, only) = RouteMode.presetsOf(m)
            assertEquals("反解后归约必须还原：$m", m, RouteMode.of(onlineMode, only))
        }
    }

    @Test
    fun `硬约束 仅在线不得回落本地`() {
        assertFalse("仅在线 ⇒ 不允许本地", RouteMode.ONLY_ONLINE.allowsLocal)
        assertFalse("仅在线 ⇒ 不允许回落", RouteMode.ONLY_ONLINE.allowsFallback)
        assertTrue("仅在线 ⇒ 允许在线", RouteMode.ONLY_ONLINE.allowsOnline)
    }

    @Test
    fun `硬约束 仅本地不触网`() {
        assertFalse("仅本地 ⇒ 不允许在线", RouteMode.ONLY_LOCAL.allowsOnline)
        assertFalse("仅本地 ⇒ 不允许回落", RouteMode.ONLY_LOCAL.allowsFallback)
        assertTrue(RouteMode.ONLY_LOCAL.allowsLocal)
    }

    @Test
    fun `优先型 允许回落`() {
        assertTrue(RouteMode.PREFER_ONLINE.allowsFallback)
        assertTrue(RouteMode.PREFER_LOCAL.allowsFallback)
        assertTrue(RouteMode.PREFER_ONLINE.prefersOnline)
        assertFalse(RouteMode.PREFER_LOCAL.prefersOnline)
    }
}
