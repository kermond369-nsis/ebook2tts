package com.kermond.ebook2tts.core

import java.security.MessageDigest

/**
 * 合成结果缓存（P7 第二批 / ADR-019 / RQ-518）。
 *
 * 纯 JVM 逻辑（可单测）：**键构造** + **按字节计量的 LRU**。
 * 引擎侧负责在 native 调用前查询、成功后写入，并遵守：
 * - **不做磁盘缓存**（不扩大隐私面）；
 * - **不得改变外部行为**：命中路径不得影响回调时序、分段与微淡化规则（红线 3/6）；
 * - 模型/音色/语速变更或后端释放 ⇒ 由引擎侧调用 [clear] 整体失效。
 *
 * 线程模型：所有公开方法 `@Synchronized`（引擎的 native 调用点本身已由 NativeGate 串行）。
 */
class SynthPcmCache(private val maxBytes: Int = DEFAULT_MAX_BYTES) {

    /** 缓存键：只包含影响音频结果的可变量（不含任何展示态） */
    data class Key(
        val modelId: String,
        val speakerId: Int,
        val speed: Double,
        val textHash: String,
    )

    private val lru = LinkedHashMap<Key, ByteArray>(16, 0.75f, true)
    private var bytes = 0

    var hits: Int = 0; private set
    var misses: Int = 0; private set

    /** 命中返回**同一份**PCM 字节（调用方不得原地修改；引擎侧只读使用） */
    @Synchronized
    fun get(key: Key): ByteArray? {
        val v = lru[key]
        if (v == null) { misses++; return null }
        hits++
        return v
    }

    @Synchronized
    fun put(key: Key, pcm: ByteArray) {
        if (pcm.isEmpty()) return
        if (pcm.size > maxBytes) return                      // 单条超过总容量：不缓存（避免挤掉全部）
        val old = lru.put(key, pcm)
        if (old != null) bytes -= old.size
        bytes += pcm.size
        evictIfNeeded()
    }

    private fun evictIfNeeded() {
        while (bytes > maxBytes && lru.isNotEmpty()) {
            val it = lru.entries.iterator()
            if (!it.hasNext()) break
            val e = it.next()                                // accessOrder=true ⇒ 首个即最久未用
            bytes -= e.value.size
            it.remove()
        }
    }

    @Synchronized
    fun clear() {
        lru.clear()
        bytes = 0
    }

    @Synchronized
    fun sizeBytes(): Int = bytes

    @Synchronized
    fun size(): Int = lru.size

    companion object {
        /** 默认上限 32 MB（ADR-019 §16.2） */
        const val DEFAULT_MAX_BYTES = 32 * 1024 * 1024

        /** 语速折算精度：0.01（避免浮点噪声造成假未命中） */
        private const val SPEED_SCALE = 100.0

        fun keyFor(modelId: String, speakerId: Int, speed: Float, text: String): Key =
            Key(
                modelId = modelId,
                speakerId = speakerId,
                speed = kotlin.math.round(speed * SPEED_SCALE) / SPEED_SCALE,
                textHash = sha256(text),
            )

        fun sha256(s: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            return d.joinToString("") { "%02x".format(it) }
        }
    }
}
