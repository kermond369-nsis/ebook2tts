package com.kermond.ebook2tts.core

/** 合成段类型 */
enum class SegmentKind { NARRATION, DIALOGUE, TITLE, EMPTY }

/** 文本段 */
data class TextSegment(
    val kind: SegmentKind,
    val text: String,
    val speakerHint: String? = null,
)

/**
 * 小说文本分析：分句 + 旁白/对白粗分（M1 规则实现，M2 可插 NER）。
 */
object TextAnalyzer {

    private val DIALOGUE_EDGE = Regex("""^[“"「『](.+?)[”"」』][，。！？…]*$""")
    /**
     * 「xxx说/道/笑道/提醒道：“…”」式说话人抽取。
     *
     * 修复（2026-09-15 真机发现）：捕获组必须**非贪婪**且动词按**长优先**排列，
     * 否则「苏岑笑道：」会被贪婪吃成 `苏岑笑`、「吴伯提醒道：」会被吃成 `吴伯提醒`。
     */
    private val SPEAKER_BEFORE = Regex(
        """([一-龥]{1,6}?)(?:低声说|轻声说|大声说|冷冷地说|笑着说道|提醒道|说道|问道|答道|喊道|叫道|笑道|叹道|道|说|问|答|提醒|开口)[:：]"""
    )

    /** 后置说话人抽取：「“……”林安笑道。」 */
    private val SPEAKER_AFTER = Regex(
        """[”"」』][，,]?\s*([一-龥]{1,6}?)(?:低声说|轻声说|大声说|冷冷地说|笑着说道|提醒道|说道|问道|答道|喊道|叫道|笑道|叹道|道|说|问|答|提醒|开口)[。！？!?]?$"""
    )

    /** 兜底清洗：去掉仍残留在名字尾部的言说动词（含副词修饰），避免角色库出现「吴伯提醒」这类脏键 */
    private val HINT_TAIL = Regex(
        """(?:低声|轻声|大声|冷冷地|笑着|急忙|连忙|开口|又|才)*(?:说道|提醒道|笑道|喊道|问道|答道|叹道|提醒|开口|道|说|问|答)+$"""
    )

    /** 泛指代词/虚词不入说话人档案，避免“他/她/众人”污染多角色库 */
    private val IGNORED_SPEAKERS = setOf(
        "他", "她", "它", "我", "你", "您", "他们", "她们", "我们", "你们",
        "大家", "众人", "有人", "旁人", "那人", "此人",
    )

    /** 归一化说话人提示；清洗后为空则返回 null（宁可不建档，也不要脏键） */
    fun cleanSpeakerHint(hint: String?): String? {
        val h = hint?.trim().orEmpty()
        if (h.isEmpty()) return null
        val cleaned = h.replace(HINT_TAIL, "").trim().take(6).ifEmpty { null } ?: return null
        return if (cleaned in IGNORED_SPEAKERS) null else cleaned
    }

    fun analyze(raw: String): List<TextSegment> {
        // 逐行归一化：normalize() 会把换行压成空格；若整体先归一化，行结构丢失，
        // 章节标题识别（RQ-108/DQ-6）与分句边界随之失效。
        val lines = raw.split('\n').map { TextClean.normalize(it).trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return listOf(TextSegment(SegmentKind.EMPTY, ""))
        if (lines.size == 1 && TextClean.isChapterTitle(lines[0])) {
            return listOf(TextSegment(SegmentKind.TITLE, TextClean.chapterSpeakText(lines[0])))
        }
        val out = mutableListOf<TextSegment>()
        for (line in lines) {
            if (TextClean.isJunkLine(line)) continue
            if (TextClean.isChapterTitle(line)) {
                out += TextSegment(SegmentKind.TITLE, TextClean.chapterSpeakText(line))
                continue
            }
            splitSentences(line).forEach { sentence ->
                val s = sentence.trim()
                if (s.isEmpty()) return@forEach
                // 纯标点碎片（如行尾残留的 」）无音可出：丢弃，避免无效合成与 DROP_UNIT
                if (!TextClean.hasSpeakable(s)) return@forEach
                out += classify(s)
            }
        }
        return out.ifEmpty { listOf(TextSegment(SegmentKind.EMPTY, "")) }
    }

    fun splitSentences(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoteDepth = 0
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            sb.append(ch)
            when (ch) {
                '“', '「', '『' -> quoteDepth++
                '”', '」', '』' -> if (quoteDepth > 0) quoteDepth--
                '"' -> quoteDepth = if (quoteDepth > 0) quoteDepth - 1 else quoteDepth + 1
            }

            if (ch == '\n') {
                out += sb.toString()
                sb.clear()
                quoteDepth = 0
                i++
                continue
            }

            if (ch in "。！？!?；;") {
                // 若紧跟收引号，将其并入本句；若后续是后置说话人（如“……”林安笑道。），暂不在此切断
                val next = text.getOrNull(i + 1)
                if (next != null && next in "”」』\"") {
                    val restAfterClose = text.substring(i + 1)
                    val trailingRearSpeaker = SPEAKER_AFTER.find(restAfterClose)?.range?.first == 0
                    if (trailingRearSpeaker) {
                        i++
                        continue
                    }
                    sb.append(next)
                    if (quoteDepth > 0) quoteDepth--
                    out += sb.toString()
                    sb.clear()
                    i += 2
                    continue
                }
                if (quoteDepth == 0 || sb.length >= 80) {
                    out += sb.toString()
                    sb.clear()
                }
            }
            i++
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private fun classify(sentence: String): TextSegment {
        val trimmed = sentence.trim()
        val spBefore = SPEAKER_BEFORE.find(trimmed)
        if (spBefore != null) {
            val hint = cleanSpeakerHint(spBefore.groupValues[1])
            return TextSegment(SegmentKind.DIALOGUE, trimmed, speakerHint = hint)
        }
        val spAfter = SPEAKER_AFTER.find(trimmed)
        if (spAfter != null) {
            val hint = cleanSpeakerHint(spAfter.groupValues[1])
            return TextSegment(SegmentKind.DIALOGUE, trimmed, speakerHint = hint)
        }
        val m = DIALOGUE_EDGE.find(trimmed)
        if (m != null) {
            return TextSegment(SegmentKind.DIALOGUE, trimmed, speakerHint = null)
        }
        return TextSegment(SegmentKind.NARRATION, trimmed)
    }

    /** 首段微切（AR-§4.4）：优先先出声 */
    fun firstSegmentMicroCut(sentence: String): Pair<String, String> {
        val t = sentence.trim()
        if (t.length <= 16) return t to ""
        val window = t.substring(0, minOf(16, t.length))
        val minIdx = 6
        val maxIdx = minOf(16, t.length)
        var cut = -1
        for (i in maxIdx - 1 downTo minIdx) {
            val c = window[i]
            if (c == '，' || c == '、' || c == '；' || c == '：' || c == ',') {
                cut = i + 1 // 标点归首段
                break
            }
        }
        if (cut <= 0) {
            cut = minOf(14, t.length)
        }
        return t.substring(0, cut) to t.substring(cut)
    }
}
