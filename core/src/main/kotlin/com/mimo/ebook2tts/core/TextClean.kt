package com.mimo.ebook2tts.core

/**
 * 文本清洗与基础规范化（RQ-108 / AR-§4.6）。
 * 不打印正文到日志（隐私，由调用方保证）。
 */
object TextClean {

    private val CHAPTER_TITLE = Regex(
        """^第[0-9零一二三四五六七八九十百千万两]+[章回节卷集部篇](?:[ 　:：·、\-—].{0,20})?$"""
    )

    private val CHAPTER_PREFIX = Regex(
        """^第[0-9零一二三四五六七八九十百千万两]+[章回节卷集部篇]"""
    )

    /** 基础归一化：去 BOM/控制字符、统一空白 */
    fun normalize(raw: String): String {
        if (raw.isEmpty()) return raw
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when {
                ch == '﻿' || ch == '​' || ch == '‌' -> Unit
                ch.code in 0..8 || ch.code == 11 || ch.code == 12 ||
                    ch.code in 14..31 -> Unit
                ch == '\n' || ch == '\r' || ch == '\t' -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.toString().trim()
    }

    /** 广告/平台噪声行（保守，不误吞正文） */
    fun isJunkLine(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (t.length <= 1) return true
        val junkHints = listOf(
            "最新网址", "手机用户请浏览", "记住本书", "天才一秒",
            "本书首发", "请收藏", "笔趣", "www.", "http://", "https://",
        )
        return junkHints.any { t.contains(it, ignoreCase = true) }
    }

    /** 章节标题行（应朗读，RQ-108 / DQ-6） */
    fun isChapterTitle(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 24) return false
        return CHAPTER_TITLE.matches(t)
    }

    /** 从行首提取章节号文本，例如「第128章」→「第 一百二十八 章」交给数字读法 */
    fun chapterSpeakText(text: String): String {
        val t = text.trim()
        if (!isChapterTitle(t)) return t
        val m = CHAPTER_PREFIX.find(t) ?: return t
        val head = m.value
        val rest = t.substring(head.length).trim(' ', '　', ':', '：', '·', '、', '-', '—')
        val spokenHead = NumberReader.readChapterHead(head)
        return if (rest.isEmpty()) spokenHead else "$spokenHead，$rest"
    }

    /** 字符强清洗：剔除非汉字/英文/数字/常用标点（AR-§4.8.6） */
    fun hardClean(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val ok = ch.isLetterOrDigit() ||
                ch in "，。、；：？！“”‘’（）《》…—-,.!?;:()\"' \n\t" ||
                ch.code in 0x4E00..0x9FFF ||
                ch.code in 0x3400..0x4DBF
            if (ok) sb.append(ch)
        }
        return sb.toString().trim()
    }
}
