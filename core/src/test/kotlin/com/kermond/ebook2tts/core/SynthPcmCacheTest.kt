package com.kermond.ebook2tts.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-019 / RQ-518 合成结果缓存（P7 第二批 / IM-545）。
 * 覆盖：命中/未命中、四种键差异、LRU 淘汰（按字节）、逐字节一致性、超限单条不缓存。
 */
class SynthPcmCacheTest {

    private fun pcm(n: Int, seed: Byte = 1): ByteArray = ByteArray(n) { (it + seed).toByte() }

    private fun key(text: String = "你好", model: String = "kokoro-int8", spk: Int = 1, speed: Float = 1.0f) =
        SynthPcmCache.keyFor(model, spk, speed, text)

    @Test
    fun `未命中后写入 再取即命中且逐字节一致`() {
        val c = SynthPcmCache()
        val k = key(); val v = pcm(1024)
        assertNull(c.get(k))
        c.put(k, v)
        val got = c.get(k)
        assertArrayEquals("命中结果必须与首次逐字节一致", v, got)
        assertSame("命中应返回同一份实例（引擎只读使用）", v, got)
        assertEquals(1, c.hits); assertEquals(1, c.misses)
    }

    @Test
    fun `键差异四种：文本 音色 语速 模型`() {
        val base = key()
        assertNotEquals(base, key(text = "你好啊"))
        assertNotEquals(base, key(spk = 2))
        assertNotEquals(base, key(speed = 1.1f))
        assertNotEquals(base, key(model = "vits-zh"))
    }

    @Test
    fun `语速折算 0_01 精度 避免浮点噪声假未命中`() {
        // 1.0000001f 与 1.0f 应视为同一档
        assertEquals(SynthPcmCache.keyFor("m", 1, 1.0000001f, "t"), SynthPcmCache.keyFor("m", 1, 1.0f, "t"))
        // 1.05f 与 1.04f 必须区分
        assertNotEquals(SynthPcmCache.keyFor("m", 1, 1.05f, "t"), SynthPcmCache.keyFor("m", 1, 1.04f, "t"))
    }

    @Test
    fun `LRU 按字节淘汰 最久未用者先出`() {
        val c = SynthPcmCache(maxBytes = 300)
        val k1 = key(text = "a"); val k2 = key(text = "b"); val k3 = key(text = "c")
        c.put(k1, pcm(100)); c.put(k2, pcm(100))
        c.get(k1)                                   // 让 k1 变为最近使用
        c.put(k3, pcm(150))                         // 总 350 > 300 ⇒ 应淘汰 k2（最久未用）
        assertNull("最久未用的 k2 应被淘汰", c.get(k2))
        assertTrue("k1 应仍在", c.get(k1) != null)
        assertEquals("容量不超过上限", 250, c.sizeBytes())
    }

    @Test
    fun `单条大于总容量时不缓存 且不破坏已有内容`() {
        val c = SynthPcmCache(maxBytes = 200)
        c.put(key(text = "keep"), pcm(150))
        c.put(key(text = "huge"), pcm(500))          // 单条超限 ⇒ 应被忽略
        assertTrue(c.get(key(text = "keep")) != null)
        assertNull(c.get(key(text = "huge")))
        assertEquals(150, c.sizeBytes())
    }

    @Test
    fun `clear 清空并使统计归零前状态可继续使用`() {
        val c = SynthPcmCache()
        c.put(key(), pcm(64))
        c.clear()
        assertEquals(0, c.size()); assertEquals(0, c.sizeBytes())
        assertNull(c.get(key()))
        c.put(key(), pcm(64))
        assertTrue(c.get(key()) != null)
    }

    @Test
    fun `空音频不写入缓存`() {
        val c = SynthPcmCache()
        c.put(key(), ByteArray(0))
        assertEquals(0, c.size())
    }
}
