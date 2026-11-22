package com.kermond.ebook2tts.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NumberReaderTest {

    @Test
    fun readNumber_basicAndHundreds() {
        assertEquals("零", NumberReader.readNumber(0))
        assertEquals("十", NumberReader.readNumber(10))
        assertEquals("十五", NumberReader.readNumber(15))
        assertEquals("九十九", NumberReader.readNumber(99))
        assertEquals("一百", NumberReader.readNumber(100))
        assertEquals("一百零五", NumberReader.readNumber(105))
        assertEquals("一百一十五", NumberReader.readNumber(115))
        assertEquals("九百九十九", NumberReader.readNumber(999))
    }

    @Test
    fun readNumber_fourDigits_noOutOfBoundsRegression() {
        assertEquals("一千", NumberReader.readNumber(1000))
        assertEquals("一千零五", NumberReader.readNumber(1005))
        assertEquals("一千零一十", NumberReader.readNumber(1010))
        assertEquals("一千零一十五", NumberReader.readNumber(1015))
        assertEquals("一千零二十四", NumberReader.readNumber(1024))
        assertEquals("一千一百", NumberReader.readNumber(1100))
        assertEquals("一千二百三十四", NumberReader.readNumber(1234))
        assertEquals("二千零二十六", NumberReader.readNumber(2026))
        assertEquals("九千九百九十九", NumberReader.readNumber(9999))
    }

    @Test
    fun readNumber_wanAndYiAndExtremes() {
        assertEquals("一万", NumberReader.readNumber(10_000))
        assertEquals("一万零五", NumberReader.readNumber(10_005))
        assertEquals("一万零一十五", NumberReader.readNumber(10_015))
        assertEquals("一万零五百", NumberReader.readNumber(10_500))
        assertEquals("一万二千三百四十五", NumberReader.readNumber(12_345))
        assertEquals("一亿", NumberReader.readNumber(100_000_000L))
        assertEquals("一亿二千三百四十五万六千七百八十九", NumberReader.readNumber(123_456_789L))
        assertEquals("负二千零二十六", NumberReader.readNumber(-2026))
        assertEquals("负九二二三三七二零三六八五四七七五八零八", NumberReader.readNumber(Long.MIN_VALUE))
    }

    @Test
    fun speakDigits_andChapterHeads() {
        assertEquals("第十二章", NumberReader.readChapterHead("第12章"))
        assertEquals("第一百二十八章", NumberReader.readChapterHead("第128章"))
        assertEquals("第一千零二十四章", NumberReader.readChapterHead("第1024章"))
        assertEquals(
            "二零二六年二点零五版本与二千零二十六块",
            NumberReader.speakDigits("2026年2.05版本与2026块"),
        )
        assertEquals(
            "一九九八年",
            NumberReader.speakDigits("1998年"),
        )
        // >19 位超大数字串按逐位回退，不抛异常也不错读成“零”
        assertEquals(
            "编号一二三四五六七八九零一二三四五六七八九零一",
            NumberReader.speakDigits("编号123456789012345678901"),
        )
        // 超大章节号不抛异常
        assertEquals(
            "第一二三四五六七八九零一二三四五六七八九零一章",
            NumberReader.readChapterHead("第123456789012345678901章"),
        )
    }
}
