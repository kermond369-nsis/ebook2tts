package com.mimo.ebook2tts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun multiLine_keepsTitleAndDropsPunctuationTail() {
        // 示例朗读的实际文本：首行章节标题 + 三句
        val text = "第十二章 风起\n" +
            "风从很远的地方吹过来，带着海的味道。\n" +
            "他说：「今天天气不错。」\n" +
            "她笑了笑：「是啊，我们走吧。」"
        val segs = TextAnalyzer.analyze(text)
        assertEquals("首行必须识别为章节标题（行结构不得被压平）", SegmentKind.TITLE, segs.first().kind)
        assertFalse("不得出现无可读字符的碎片段", segs.any { !TextClean.hasSpeakable(it.text) })
        assertFalse("行尾残留的 」 不得单独成段", segs.any { it.text.trim() == "」" })
    }

    @Test
    fun singleCharLine_keptWhenSpeakable() {
        val segs = TextAnalyzer.analyze("嗯")
        assertTrue("可读的单字不得被判为噪声行吞掉", segs.any { TextClean.hasSpeakable(it.text) })
    }

    @Test
    fun punctuationOnlyFragment_isNotSpeakable() {
        assertFalse(TextClean.hasSpeakable("」"))
        assertFalse(TextClean.hasSpeakable(" …… "))
        assertTrue(TextClean.hasSpeakable("第十二章"))
        assertTrue(TextClean.hasSpeakable("5"))
    }
}
