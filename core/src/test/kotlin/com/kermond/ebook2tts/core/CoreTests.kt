package com.kermond.ebook2tts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessNamesTest {
    @Test
    fun engineProcess() {
        assertTrue(ProcessNames.isEngineProcess("com.kermond.ebook2tts:tts_service"))
        assertFalse(ProcessNames.isEngineProcess("com.kermond.ebook2tts"))
        assertFalse(ProcessNames.isEngineProcess(null))
    }
}

class SpeedMapperTest {
    @Test
    fun aospUnits() {
        assertEquals(1.0f, SpeedMapper.readerSpeed(100), 1e-4f)
        assertEquals(0.5f, SpeedMapper.actualSpeed(50), 1e-4f)
        assertEquals(2.0f, SpeedMapper.actualSpeed(300), 1e-4f)
        // emotion limited ±10%
        assertEquals(1.1f, SpeedMapper.actualSpeed(100, 0.5f), 1e-4f)
        assertEquals(0.9f, SpeedMapper.actualSpeed(100, -0.5f), 1e-4f)
    }

    @Test
    fun resolveSpeed_respectsModeAndAppFallback() {
        // readerFirst 且阅读器未调速(100) ⇒ 采用 App 滑杆语速 1.4x
        assertEquals(1.4f, SpeedMapper.resolveSpeed(100, 1.4f, "readerFirst"), 1e-4f)
        // readerFirst 且阅读器显式调速(150) ⇒ 跟随阅读器 1.5x
        assertEquals(1.5f, SpeedMapper.resolveSpeed(150, 1.2f, "readerFirst"), 1e-4f)
        // appFirst ⇒ 忽略阅读器 150，采用 App 1.2x
        assertEquals(1.2f, SpeedMapper.resolveSpeed(150, 1.2f, "appFirst"), 1e-4f)
    }
}

class PcmChunkerTest {
    @Test
    fun chunk_limits() {
        val pcm = ByteArray(20_000) { 1 }
        val chunks = PcmChunker.chunk(pcm)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.isNotEmpty() && it.size <= PcmChunker.MAX_CHUNK_BYTES })
    }

    @Test
    fun silence_positive() {
        val s = PcmChunker.silenceMs(120, 1.0f, 24000)
        assertTrue(s.size > 1000)
    }

    @Test
    fun fade_onlyAtRequestEdges() {
        // PCM16 LE 常量样本 100（帧 = 低字节 100、高字节 0）；3ms@24kHz = 144 字节淡化区
        val pcm = ByteArray(2_000) { if (it % 2 == 0) 100 else 0 }
        val head = PcmChunker.fadeHead(pcm, 24000, 3)
        assertEquals(pcm.size, head.size)
        assertEquals(0, head[0].toInt())
        assertEquals(100, head[200].toInt())
        val tail = PcmChunker.fadeTail(pcm, 24000, 3)
        assertTrue((tail[tail.size - 2].toInt() and 0xff) < 100)
        assertEquals(100, tail[200].toInt())
        val both = PcmChunker.fadeEdges(pcm, 24000, 3)
        assertEquals(0, both[0].toInt())
        assertEquals(100, both[1_000].toInt())
    }

    @Test
    fun fade_shortInput_untouched() {
        val tiny = ByteArray(4) { 7 }
        assertSame(tiny, PcmChunker.fadeHead(tiny, 24000, 3))
        assertSame(tiny, PcmChunker.fadeTail(tiny, 24000, 3))
    }

    @Test
    fun floatToPcm16_guardsNonFiniteSamples() {
        val pcm = PcmChunker.floatToPcm16(
            floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0.5f),
        )
        assertEquals(8, pcm.size)
        for (i in 0 until 6) {
            assertEquals(0.toByte(), pcm[i])
        }
        val sample = (pcm[7].toInt() shl 8) or (pcm[6].toInt() and 0xff)
        assertTrue("0.5f 应转换为正 PCM 采样：$sample", sample in 16000..16500)
    }
}
