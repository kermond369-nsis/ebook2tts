package com.kermond.ebook2tts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 网络策略：蜂窝 + 未允许流量 → 禁止联网（在线朗读与模型下载共用）。 */
class NetPolicyTest {

    @Test
    fun cellularBlockedWhenMobileDataDisallowed() {
        assertFalse(NetPolicy.allowsNetwork(allowMobileData = false, onCellular = true))
        assertEquals(
            NetPolicy.REASON_CELLULAR_DISALLOWED,
            NetPolicy.blockReason(allowMobileData = false, onCellular = true),
        )
    }

    @Test
    fun cellularAllowedWhenMobileDataAllowed() {
        assertTrue(NetPolicy.allowsNetwork(allowMobileData = true, onCellular = true))
        assertNull(NetPolicy.blockReason(allowMobileData = true, onCellular = true))
    }

    @Test
    fun nonCellularAlwaysAllowed() {
        assertTrue(NetPolicy.allowsNetwork(allowMobileData = false, onCellular = false))
        assertNull(NetPolicy.blockReason(allowMobileData = false, onCellular = false))
    }
}
