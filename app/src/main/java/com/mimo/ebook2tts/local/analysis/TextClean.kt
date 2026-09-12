package com.mimo.ebook2tts.local.analysis

/**
 * 小说正文清洗：全角缩进、零宽字符、章节头、广告行等
 * 在进入机械分析 / 分句 / TTS 之前统一处理。
 */
object TextClean {

    /** 书名号内文本不当对白引号 */
    fun stripBookTitles(s: String): String =
        Regex("《[^》]{0,40}》").replace(s, "")

    fun normalize(raw: String): String {
        var s = raw
            .replace('\uFEFF', ' ')
            .replace('\u200B', ' ')
            .replace('\u200E', ' ')
            .replace('\u200F', ' ')
            .replace('\u202A', ' ')
            .replace('\u202C', ' ')
            .replace('\u3000', ' ') // 全角空格
            .replace(Regex("[\\t ]+"), " ")
            .trim()
        // 统一常见弯引号，便于规则匹配
        s = s.replace('「', '“').replace('」', '”')
        s = s.replace('『', '“').replace('』', '”')
        return s.trim()
    }

    /** 平台标签 / 简介广告行，不应朗读 */
    fun isJunkLine(s: String): Boolean {
        if (s.isBlank()) return true
        if (s.startsWith("🏷") || s.startsWith("🔖") || s.startsWith("标签：")) return true
        if (s.contains("平台补贴") || s.contains("腾讯视频火热播出")) return true
        if (s.matches(Regex("^第\\s*\\d+\\s*章.*"))) return true
        if (s == "简介：" || s == "简介:") return true
        // 纯省略号 / 纯符号
        if (!Regex("[一-龥A-Za-z0-9]").containsMatchIn(s)) return true
        return false
    }

    /** 交给 TTS 前的最终可读文本 */
    fun forTts(s: String): String {
        return normalize(s)
            .trim('“', '”', '"', '「', '」', '《', '》')
            .trim()
    }
}
