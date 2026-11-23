package com.kermond.ebook2tts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineVoiceCatalogTest {

    @Test
    fun presets_coverOfficialEightVoices() {
        val ids = OnlineVoiceCatalog.MIMO_PRESETS.map { it.id }.toSet()
        assertEquals(
            setOf("白桦", "苏打", "冰糖", "茉莉", "Mia", "Chloe", "Milo", "Dean"),
            ids,
        )
        assertTrue(OnlineVoiceCatalog.isPreset("冰糖"))
        assertTrue(OnlineVoiceCatalog.isPreset("mia"))
        assertTrue(OnlineVoiceCatalog.isPreset("冰糖 · 中文女声"))
        assertFalse(OnlineVoiceCatalog.isPreset("未知音色"))
    }

    @Test
    fun resolveMimoVoice_prefersRequestedThenDefaultThenFallback() {
        assertEquals("茉莉", OnlineVoiceCatalog.resolveMimoVoice("茉莉", "白桦"))
        assertEquals("Mia", OnlineVoiceCatalog.resolveMimoVoice("mia", "白桦"))
        assertEquals("冰糖", OnlineVoiceCatalog.resolveMimoVoice("冰糖 · 中文女声", "白桦"))
        assertEquals("苏打", OnlineVoiceCatalog.resolveMimoVoice("未知阅读器音色", "苏打"))
        assertEquals("custom_voice", OnlineVoiceCatalog.resolveMimoVoice("", "custom_voice"))
        assertEquals(OnlineSettings.DEFAULT_VOICE, OnlineVoiceCatalog.resolveMimoVoice(null, "   "))
    }
}
