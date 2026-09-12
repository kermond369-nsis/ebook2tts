package com.mimo.ebook2tts.local.analysis

/**
 * 小说文本滚动缓存：上限 200K 字符，超出后丢弃最旧内容。
 * 用于新人名出现时把上下文喂给 mimo-v2.5 精标。
 */
class NovelTextBuffer(private val maxChars: Int = 200_000) {
    private val sb = StringBuilder()
    private val lock = Any()

    /** 历史累计写入字数（含已滚动丢弃部分） */
    @Volatile
    var totalAppended: Long = 0
        private set

    fun append(text: String) {
        if (text.isBlank()) return
        synchronized(lock) {
            sb.append(text)
            totalAppended += text.length
            if (!text.endsWith("\n")) {
                sb.append('\n')
                totalAppended += 1
            }
            if (sb.length > maxChars) {
                // 从头部裁掉，尽量在段落边界截断
                val drop = sb.length - maxChars
                var cut = drop
                val limit = minOf(drop + 2000, sb.length)
                val nl = sb.indexOf("\n", drop)
                if (nl in drop until limit) cut = nl + 1
                sb.delete(0, cut)
            }
        }
    }

    fun snapshot(maxTake: Int = 12_000): String {
        synchronized(lock) {
            return if (sb.length <= maxTake) sb.toString()
            else sb.substring(sb.length - maxTake)
        }
    }

    fun length(): Int = synchronized(lock) { sb.length }

    fun clear() = synchronized(lock) { sb.setLength(0) }
}
