package com.mimo.ebook2tts.local.analysis

/**
 * 标准 TTS 管线下，系统一次只递给一段 utterance。
 * 因此需要跨 utterance 维护说话人/情绪上下文，并从文本形态推断角色。
 *
 * 小说软件常见三种输入：
 *  1) 整段旁白
 *  2) 带引号的对白（可能含「XX说：」「XX：」提示）
 *  3) 已用 speak(text) 分句的短句
 */
class TextAnalyzer(
    private val smartCharacter: Boolean = true,
    private val emotionEnabled: Boolean = true,
) {
    data class Analysis(
        val text: String,
        val speakText: String,
        val speakerId: String,
        val emotion: Emotion,
        val styleTag: String,
    )

    /** 一段可朗读单元：对白或旁白 */
    data class SpeakUnit(
        val speakerId: String,
        val text: String,
        val emotion: Emotion,
        val isDialogue: Boolean,
    )

    private val knownSpeakers = linkedSetOf<String>()
    private val speakerLines = linkedMapOf<String, MutableList<String>>()
    private var lastSpeaker: String = SpeakerIds.NARRATOR
    private var lastEmotion: Emotion = Emotion.CALM

    fun knownSpeakerIds(): Set<String> = knownSpeakers.toSet()

    fun genderOf(name: String): String? {
        val joined = speakerLines[name]?.joinToString(" ") ?: return guessGenderFromName(name)
        val femaleHits = Regex("她").findAll(joined).count()
        val maleHits = Regex("他").findAll(joined).count()
        val near = Regex("$name.{0,12}(她|他)").find(joined)
            ?: Regex("(她|他).{0,12}$name").find(joined)
        if (near != null) {
            return if (near.groupValues[1] == "她") "female" else "male"
        }
        if (femaleHits > maleHits + 1) return "female"
        if (maleHits > femaleHits + 1) return "male"
        return guessGenderFromName(name)
    }

    private fun guessGenderFromName(name: String): String? {
        if (Regex("[女娘姑姐妹妇妃婷娜娟芳丽静雅琳雪梅兰英珍倩晓婉嫣]").containsMatchIn(name)) return "female"
        if (Regex("[男兄弟哥父爷叔伟强军磊涛鹏浩杰明超]").containsMatchIn(name)) return "male"
        return null
    }

    fun analyze(raw: String): Analysis {
        val text = TextClean.normalize(raw)
        if (text.isEmpty() || TextClean.isJunkLine(text)) {
            return Analysis(text, "", lastSpeaker, lastEmotion, lastEmotion.styleTag)
        }

        var body = text.removePrefix("旁白：").removePrefix("旁白:").trim()
        val quote = extractQuote(TextClean.stripBookTitles(body))

        // 「整段几乎就是一句对白」才继承上一说话人；
        // 旁白里嵌的引号词（如「麦霸」）不能把全文砍成只剩引号
        val startsWithQuote = body.startsWith("“") || body.startsWith("\"") || body.startsWith("「")
        val isBareDialogue = quote != null && (
            startsWithQuote || body.length <= quote!!.length + 8
            )

        val speaker = if (smartCharacter) {
            val sp = guessSpeaker(text)
            when {
                sp != null -> {
                    if (sp != SpeakerIds.NARRATOR) {
                        knownSpeakers += sp
                        speakerLines.getOrPut(sp) { mutableListOf() }.add(text)
                    }
                    lastSpeaker = sp
                    sp
                }
                // 「笑声，道：“…”」这种提示语
                Regex("[，,][说道问答][：:]“").containsMatchIn(body) &&
                    lastSpeaker != SpeakerIds.NARRATOR -> lastSpeaker
                isBareDialogue && lastSpeaker != SpeakerIds.NARRATOR -> lastSpeaker
                else -> SpeakerIds.NARRATOR
            }
        } else {
            SpeakerIds.NARRATOR
        }

        // 对白：只读引号内容；旁白：保留全文（含嵌套引号，不要剥首尾）
        val speakText = if (quote != null && speaker != SpeakerIds.NARRATOR) {
            TextClean.forTts(quote)
        } else {
            TextClean.normalize(body)
        }

        val emotion = if (emotionEnabled) {
            if (speaker == SpeakerIds.NARRATOR) Emotion.CALM
            else detectEmotion(speakText + " " + text).also { lastEmotion = it }
        } else {
            Emotion.CALM
        }

        return Analysis(
            text = text,
            speakText = speakText,
            speakerId = speaker,
            emotion = emotion,
            styleTag = emotion.styleTag,
        )
    }

    /**
     * 拆成「对白 / 旁白 / 对白…」多段，避免整段只念第一处引号。
     * 例：“我鄙视你…”庞博看了看…道：“凭着男人的直觉…”
     */
    fun analyzeSegments(raw: String): List<SpeakUnit> {
        val text = TextClean.normalize(raw)
        if (text.isEmpty() || TextClean.isJunkLine(text)) return emptyList()

        val quoteRe = Regex("[“\"]([^”\"]+)[”\"]")
        val quotes = quoteRe.findAll(text).toList()
        if (quotes.isEmpty()) {
            val a = analyze(raw)
            return if (a.speakText.isBlank()) emptyList()
            else listOf(
                SpeakUnit(a.speakerId, a.speakText, a.emotion, isDialogue = false)
            )
        }

        val units = mutableListOf<SpeakUnit>()
        var cursor = 0
        // 不使用 wholeSpeaker 遮蔽：每个引号用局部窗口单独猜人

        for (m in quotes) {
            val qStart = m.range.first
            val qEnd = m.range.last + 1
            val inner = m.groupValues[1].trim()

            val before = text.substring(cursor, qStart).trim()
            if (before.isNotEmpty() && hasCjkOrWord(before)) {
                units.add(
                    SpeakUnit(
                        speakerId = SpeakerIds.NARRATOR,
                        text = TextClean.normalize(before),
                        emotion = Emotion.CALM,
                        isDialogue = false,
                    )
                )
            }

            if (inner.isNotEmpty() && hasCjkOrWord(inner)) {
                // 局部窗口：引号前 24 字 + 引号后 24 字
                val wStart = (qStart - 24).coerceAtLeast(0)
                val wEnd = (qEnd + 24).coerceAtMost(text.length)
                val window = text.substring(wStart, wEnd)
                val sp = guessSpeaker(window, knownSpeakers)
                    ?: lastSpeaker.takeIf { it != SpeakerIds.NARRATOR }
                    ?: SpeakerIds.NARRATOR
                if (sp != SpeakerIds.NARRATOR) {
                    knownSpeakers += sp
                    speakerLines.getOrPut(sp) { mutableListOf() }.add(text.take(200))
                    // 截断：每角色最多 30 条
                    val lines = speakerLines[sp]!!
                    if (lines.size > 30) {
                        speakerLines[sp] = lines.takeLast(30).toMutableList()
                    }
                    lastSpeaker = sp
                }
                val emo = if (emotionEnabled && sp != SpeakerIds.NARRATOR) {
                    detectEmotion(inner).also { lastEmotion = it }
                } else Emotion.CALM
                units.add(
                    SpeakUnit(sp, TextClean.forTts(inner), emo, isDialogue = true)
                )
            }
            cursor = qEnd
        }

        // 末尾剩余旁白
        val tail = text.substring(cursor).trim()
        if (tail.isNotEmpty() && hasCjkOrWord(tail)) {
            units.add(
                SpeakUnit(
                    speakerId = SpeakerIds.NARRATOR,
                    text = TextClean.normalize(tail),
                    emotion = Emotion.CALM,
                    isDialogue = false,
                )
            )
        }

        return units
    }

    private fun hasCjkOrWord(s: String): Boolean =
        Regex("[一-龥A-Za-z0-9]").containsMatchIn(s)

    fun resetSession() {
        lastSpeaker = SpeakerIds.NARRATOR
        lastEmotion = Emotion.CALM
    }

    companion object {
        private val PRONOUNS = setOf(
            "我", "你", "您", "他", "她", "它", "我们", "你们", "他们", "她们", "谁", "旁白"
        )
        private val NARR_HINTS = listOf("旁白", "只见", "此时", "这时", "忽然", "随后", "夜色")

        // 名字后必须是动作词；不要裸引号，避免「最后“麦霸”」把「最后」当人名
        private const val AFTER =
            "(?:说道|道|问|答|喊|叫|吼|说|靠在|走上|走上前|站定|站住|抬眼|沉声|低声道|冷冷|笑着|哭着|叹道|怒道|喝道)[：:，“\"]"

        // 引号后：人名 + 可选副词 + 动作（覆盖「叶凡合上」「叶凡轻松反击」）
        private const val AFTER_QUOTE =
            "(?:轻松|淡淡|冷冷|苦笑|微笑|急忙|立刻|马上)?" +
                "(?:说道|道|问|答|说|靠在|走上|走上前|站定|站住|抬眼|沉声|低声道|冷冷|笑着|哭着|叹道|怒道|喝道|合上|反击|笑了笑|接口|应道|答道|问道|点头|摇头)"

        fun extractQuote(paragraph: String): String? {
            val cleaned = TextClean.stripBookTitles(paragraph)
            val m = Regex("[“\"]([^”\"]+)[”\"]").find(cleaned) ?: return null
            val q = m.groupValues[1].trim()
            // 过短或纯省略号不当对白主体（如「那是……」可保留，但过滤空/纯标点）
            if (!Regex("[一-龥A-Za-z0-9]").containsMatchIn(q)) return null
            return q
        }

        fun isPlausibleName(name: String): Boolean {
            if (name.isBlank() || name.length > 4) return false
            if (name in PRONOUNS) return false
            if (NARR_HINTS.any { name.contains(it) }) return false
            return Regex("^[一-龥]{1,4}$").matches(name) ||
                Regex("^[A-Za-z]{2,12}$").matches(name)
        }

        fun guessSpeaker(paragraph: String, known: Set<String> = emptySet()): String? {
            val p = paragraph.trim()
            if (p.startsWith("旁白：") || p.startsWith("旁白:")) return SpeakerIds.NARRATOR

            var m = Regex("^([一-龥A-Za-z]{2,3})$AFTER").find(p)
            if (m != null && isPlausibleName(m.groupValues[1])) return m.groupValues[1]

            m = Regex("^([一-龥A-Za-z])(?:道|说|问)[：:，“\"]").find(p)
            if (m != null && isPlausibleName(m.groupValues[1])) return m.groupValues[1]

            m = Regex("[”\"]([一-龥A-Za-z]{2,3})$AFTER_QUOTE").find(p)
            if (m != null && isPlausibleName(m.groupValues[1])) return m.groupValues[1]

            // 「林晓站定，说道：“…”」— 引号前的名字 + 说/道
            m = Regex("([一-龥A-Za-z]{2,3})[^“\"]{0,16}(?:说道|道|问|答|说)[：:，,]?[“\"]").find(p)
            if (m != null && isPlausibleName(m.groupValues[1])) return m.groupValues[1]

            // 已知角色近邻（需传入 knownSpeakers）
            if (extractQuote(p) != null && known.isNotEmpty()) {
                val q = p.indexOfFirst { it == '“' || it == '"' }
                val close = p.indexOfFirst { it == '”' || it == '"' }
                return known
                    .mapNotNull { name ->
                        val pos = p.indexOf(name)
                        if (pos < 0) return@mapNotNull null
                        val nearQuote = q >= 0 && (pos <= q + 20 || (close >= 0 && pos >= close - 20))
                        if (nearQuote) name to pos else null
                    }
                    .minByOrNull { it.second }
                    ?.first
            }
            return null
        }

        fun detectEmotion(t: String): Emotion {
            if (Regex("[!！]{2,}|怒|吼|咆哮|滚|混账").containsMatchIn(t)) return Emotion.ANGER
            if (Regex("[?？]|惊|吓|慌|怕|战战").containsMatchIn(t)) return Emotion.FEAR
            if (Regex("[笑喜乐]|太好了|真好").containsMatchIn(t) &&
                !Regex("[哭泪]").containsMatchIn(t)
            ) return Emotion.JOY
            if (Regex("哭|泪|悲|伤|痛|舍不得|再也").containsMatchIn(t)) return Emotion.SADNESS
            if (Regex("惊|诧|愣|没想到|居然").containsMatchIn(t)) return Emotion.SURPRISE
            if (Regex("轻声|耳语|悄悄|低声道").containsMatchIn(t)) return Emotion.WHISPER
            return Emotion.CALM
        }
    }
}
