package com.mimo.ebook2tts.core

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
    private val SPEAKER_BEFORE = Regex(
        """([一-龥]{1,6})(?:说道|道|问道|答道|喊道|叫道|笑道|说|问|答)[:：]"""
    )

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
        for (ch in text) {
            sb.append(ch)
            if (ch in "。！？!?；;\n") {
                out += sb.toString()
                sb.clear()
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private fun classify(sentence: String): TextSegment {
        val m = DIALOGUE_EDGE.find(sentence.trim())
        if (m != null) {
            return TextSegment(SegmentKind.DIALOGUE, sentence.trim(), speakerHint = null)
        }
        val sp = SPEAKER_BEFORE.find(sentence)
        val hint = sp?.groupValues?.get(1)
        if (hint != null) {
            return TextSegment(SegmentKind.DIALOGUE, sentence.trim(), speakerHint = hint)
        }
        return TextSegment(SegmentKind.NARRATION, sentence.trim())
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
