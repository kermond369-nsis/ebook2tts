package com.mimo.ebook2tts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextAnalyzerTest {

    @Test
    fun analyze_segmentsDialogue() {
        val segs = TextAnalyzer.analyze("他说：「今天天气不错。」随后离开了。")
        assertTrue(segs.isNotEmpty())
        assertTrue(segs.any { it.kind == SegmentKind.DIALOGUE })
        assertTrue(segs.any { it.kind == SegmentKind.NARRATION })
    }

    @Test
    fun microCut_shortSentence() {
        val (a, b) = TextAnalyzer.firstSegmentMicroCut("你好。")
        assertEquals("你好。", a)
        assertEquals("", b)
    }

    @Test
    fun microCut_longWithComma() {
        val long = "风从很远的地方吹过来，带着海的味道和旧日的回忆。"
        val (first, rest) = TextAnalyzer.firstSegmentMicroCut(long)
        assertTrue(first.length <= 16)
        assertTrue(rest.isNotEmpty() || first == long)
    }

    @Test
    fun microCut_hardCutNoPunct() {
        val long = "这是一段完全没有标点符号的很长很长的中文句子用来测试硬切兜底规则"
        val (first, rest) = TextAnalyzer.firstSegmentMicroCut(long)
        assertEquals(14, first.length)
        assertEquals(long.length - 14, rest.length)
    }
}
