package com.kermond.ebook2tts.core

/** 中文数字/章节头读法（RQ-108） */
object NumberReader {

    private val DIGITS = charArrayOf('零', '一', '二', '三', '四', '五', '六', '七', '八', '九')

    /** 「第128章」→「第一百二十八章」 */
    fun readChapterHead(head: String): String {
        val m = Regex("""^第([0-9]+)([章回节卷集部篇])$""").find(head.trim())
            ?: return head
        val num = m.groupValues[1]
        val unit = m.groupValues[2]
        val spoken = num.toLongOrNull()?.let(::readNumber) ?: readDigits(num)
        return "第$spoken$unit"
    }

    private fun readDigits(digits: String): String {
        val sb = StringBuilder(digits.length)
        for (ch in digits) {
            if (ch in '0'..'9') sb.append(DIGITS[ch - '0'])
        }
        return sb.toString()
    }

    fun readNumber(n: Long): String {
        if (n == Long.MIN_VALUE) return "负${readDigits("9223372036854775808")}"
        if (n < 0) return "负${readNumber(-n)}"
        if (n < 10) return DIGITS[n.toInt()].toString()
        if (n < 20) return if (n == 10L) "十" else "十${DIGITS[(n % 10).toInt()]}"
        if (n < 100) {
            val tens = (n / 10).toInt()
            val ones = (n % 10).toInt()
            return if (ones == 0) "${DIGITS[tens]}十"
            else "${DIGITS[tens]}十${DIGITS[ones]}"
        }
        if (n < 1_000) {
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
        if (n < 10_000) {
            val thousands = (n / 1_000).toInt()
            val rest = n % 1_000
            val sb = StringBuilder()
            sb.append(DIGITS[thousands]).append('千')
            when {
                rest == 0L -> Unit
                rest < 10 -> sb.append('零').append(DIGITS[rest.toInt()])
                rest < 20 -> sb.append("零一").append(readNumber(rest))
                rest < 100 -> sb.append('零').append(readNumber(rest))
                else -> sb.append(readNumber(rest))
            }
            return sb.toString()
        }
        if (n < 100_000_000L) {
            val wan = n / 10_000
            val rest = n % 10_000
            val head = readNumber(wan) + "万"
            return when {
                rest == 0L -> head
                rest < 10 -> "${head}零${DIGITS[rest.toInt()]}"
                rest < 20 -> "${head}零一${readNumber(rest)}"
                rest < 1_000 -> "${head}零${readNumber(rest)}"
                else -> "$head${readNumber(rest)}"
            }
        }
        val yi = n / 100_000_000L
        val rest = n % 100_000_000L
        val head = readNumber(yi) + "亿"
        return when {
            rest == 0L -> head
            rest < 10 -> "${head}零${DIGITS[rest.toInt()]}"
            rest < 20 -> "${head}零一${readNumber(rest)}"
            rest < 10_000_000L -> "${head}零${readNumber(rest)}"
            else -> "$head${readNumber(rest)}"
        }
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
                    val isFourDigitYear = token.length == 4 &&
                        (token.startsWith("1") || token.startsWith("20")) &&
                        i < text.length && text[i] == '年'
                    val spoken = if (isFourDigitYear) {
                        readDigits(token)
                    } else {
                        token.toLongOrNull()?.let(::readNumber) ?: readDigits(token)
                    }
                    sb.append(spoken)
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
        val rawInt = parts[0]
        val intSpoken = rawInt.toLongOrNull()?.let(::readNumber) ?: readDigits(rawInt)
        val frac = parts.getOrNull(1).orEmpty()
        val sb = StringBuilder(intSpoken)
        if (frac.isNotEmpty()) {
            sb.append('点')
            for (ch in frac) {
                if (ch.isDigit()) sb.append(DIGITS[ch - '0'])
            }
        }
        return sb.toString()
    }
}
