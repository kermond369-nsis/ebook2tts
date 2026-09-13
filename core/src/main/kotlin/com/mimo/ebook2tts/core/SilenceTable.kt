package com.mimo.ebook2tts.core

/** 停顿基线（ms），M2 可分级 */
object SilenceTable {
    const val COMMA = 180
    const val PERIOD = 350
    const val PARAGRAPH = 500
    const val INTER_SEGMENT = 120

    fun forPunct(ch: Char): Int = when (ch) {
        '，', '、', '：', ',', '；', ';' -> COMMA
        '。', '！', '？', '!', '?', '…' -> PERIOD
        '\n' -> PARAGRAPH
        else -> INTER_SEGMENT
    }
}
