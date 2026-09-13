package com.mimo.ebook2tts.core

/** 中文数字/章节头读法（RQ-108） */
object NumberReader {

    private val DIGITS = charArrayOf('零', '一', '二', '三', '四', '五', '六', '七', '八', '九')

    /** 「第128章」→「第一百二十八章」 */
    fun readChapterHead(head: String): String {
        val m = Regex("""^第([0-9]+)([章回节卷集部篇])$""").find(head.trim())
            ?: return head
        val num = m.groupValues[1]
        val unit = m.groupValues[2]
        return "第${readNumber(num.toLong())}$unit"
    }

    fun readNumber(n: Long): String {
        if (n < 0) return "负${readNumber(-n)}"
        if (n < 10) return DIGITS[n.toInt()].toString()
        if (n < 20) return if (n == 10L) "十" else "十${DIGITS[(n % 10).toInt()]}"
        if (n < 100) {
            val tens = (n / 10).toInt()
            val ones = (n % 10).toInt()
            return if (ones == 0) "${DIGITS[tens]}十"
            else "${DIGITS[tens]}十${DIGITS[ones]}"
        }
        if (n < 10_000) {
            val hundreds = (n / 100).toInt()
            val rest = n % 100
            val sb = StringBuilder()
            sb.append(DIGITS[hundreds]).append('百')
            when {
                rest == 0L -> Unit
                rest < 10 -> sb.append('零').append(DIGITS[rest.toInt()])
                rest < 20 -> sb.append('一').append(readNumber(rest))
                else -> sb.append(readNumber(rest))
            }
            return sb.toString()
        }
        // 简化：万及以上按位读
        val wan = n / 10_000
        val rest = n % 10_000
        val head = readNumber(wan) + "万"
        return if (rest == 0L) head
        else if (rest < 1000) head + "零" + readNumber(rest)
        else head + readNumber(rest)
    }

    /** 行内阿拉伯数字基础读法：128 → 一百二十八；2.5 → 二点五 */
    fun speakDigits(text: String): String {
        val sb = StringBuilder(text.length * 2)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isDigit()) {
                val start = i
                while (i < text.length && text[i].isDigit()) i++
                if (i < text.length && text[i] == '.' && i + 1 < text.length && text[i + 1].isDigit()) {
                    i++
                    while (i < text.length && text[i].isDigit()) i++
                    val token = text.substring(start, i)
                    sb.append(readDecimal(token))
                } else {
                    val token = text.substring(start, i)
                    sb.append(readNumber(token.toLongOrNull() ?: 0L))
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun readDecimal(token: String): String {
        val parts = token.split('.')
        val intPart = parts[0].toLongOrNull() ?: 0L
        val frac = parts.getOrNull(1).orEmpty()
        val sb = StringBuilder(readNumber(intPart))
        if (frac.isNotEmpty()) {
            sb.append('点')
            for (ch in frac) {
                if (ch.isDigit()) sb.append(DIGITS[ch - '0'])
            }
        }
        return sb.toString()
    }
}
