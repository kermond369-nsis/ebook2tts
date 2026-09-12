package com.mimo.ebook2tts.local.buffer

import java.security.MessageDigest
import java.util.LinkedHashMap

/**
 * 合成结果本地缓冲。
 * key = hash(文本 + 音色 + 情绪 + 模型 + 语速档)，value = PCM。
 * 回滚重听时命中缓存，避免重复调用 MiMo API。
 */
class SegmentCache(maxEntries: Int = 5) {

    data class Entry(
        val pcm: ByteArray,
        val sampleRate: Int,
        val channels: Int,
        val createdAt: Long = System.currentTimeMillis(),
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Entry) return false
            return pcm.contentEquals(other.pcm) &&
                sampleRate == other.sampleRate &&
                channels == other.channels
        }

        override fun hashCode(): Int {
            var r = pcm.contentHashCode()
            r = 31 * r + sampleRate
            r = 31 * r + channels
            return r
        }
    }

    @Volatile
    private var maxEntries: Int = maxEntries.coerceIn(1, 64)

    private val lock = Any()
    private val map = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean {
            return size > this@SegmentCache.maxEntries
        }
    }

    var hits: Long = 0
        private set
    var misses: Long = 0
        private set

    fun resize(newMax: Int) {
        synchronized(lock) {
            maxEntries = newMax.coerceIn(1, 64)
            while (map.size > maxEntries) {
                val it = map.entries.iterator()
                if (!it.hasNext()) break
                it.next()
                it.remove()
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            map.clear()
            hits = 0
            misses = 0
        }
    }

    fun get(key: String): Entry? {
        synchronized(lock) {
            val e = map[key]
            if (e != null) hits++ else misses++
            return e
        }
    }

    fun put(key: String, entry: Entry) {
        synchronized(lock) {
            map[key] = entry
        }
    }

    fun size(): Int = synchronized(lock) { map.size }

    fun keyOf(
        text: String,
        voiceId: String,
        emotionId: String,
        model: String,
        rateBucket: Int,
    ): String {
        val raw = listOf(text, voiceId, emotionId, model, rateBucket.toString())
            .joinToString("|")
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
