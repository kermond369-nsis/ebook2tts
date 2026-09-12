package com.mimo.ebook2tts.local.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextAnalyzerTest {

    @Test
    fun detectsQuoteSpeakerAfterQuote() {
        val sp = TextAnalyzer.guessSpeaker("“你终于来了。”周远靠在墙边，声音压得很低。")
        assertEquals("周远", sp)
    }

    @Test
    fun detectsSpeakerBeforeQuote() {
        val sp = TextAnalyzer.guessSpeaker("林晓站定，呼吸有些乱：“我答应过的事，就一定会做。”")
        assertEquals("林晓", sp)
    }

    @Test
    fun narratorPrefix() {
        val sp = TextAnalyzer.guessSpeaker("旁白：那一夜之后，城南的雨，仿佛再也没有停过。")
        assertEquals(SpeakerIds.NARRATOR, sp)
    }

    @Test
    fun rejectsPronounAsName() {
        assertFalse(TextAnalyzer.isPlausibleName("我"))
        assertFalse(TextAnalyzer.isPlausibleName("我们"))
    }

    @Test
    fun extractsQuote() {
        val q = TextAnalyzer.extractQuote("叶凡笑了笑：“今天天气不错。”说完转身离开。")
        assertEquals("今天天气不错。", q)
    }
}
