package com.kermond.ebook2tts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SystemTtsPolicyTest {

    @Test
    fun rankCandidates_excludesSelf_andOrdersPreferredAndSystemEngines() {
        val list = listOf(
            TtsEngineCandidate("com.kermond.ebook2tts", "书声", isSystemApp = false),
            TtsEngineCandidate("com.third.party", "第三方语音", isSystemApp = false),
            TtsEngineCandidate("com.google.android.tts", "Google 语音服务", isSystemApp = true),
            TtsEngineCandidate("com.xiaomi.mibrain.speech", "小爱语音引擎", isSystemApp = true),
            TtsEngineCandidate("com.xiaomi.mibrain.speech", "重复条目", isSystemApp = true),
            TtsEngineCandidate("   ", "空包名", isSystemApp = true),
        )
        val ranked = SystemTtsSelector.rankCandidates(
            installed = list,
            selfPackage = "com.kermond.ebook2tts",
        )
        assertEquals(
            listOf("com.xiaomi.mibrain.speech", "com.google.android.tts", "com.third.party"),
            ranked.map { it.packageName },
        )

        val preferred = SystemTtsSelector.rankCandidates(
            installed = list,
            selfPackage = "com.kermond.ebook2tts",
            preferredPackage = "com.third.party",
        )
        assertEquals("com.third.party", preferred.first().packageName)
        assertFalse(preferred.any { it.packageName == "com.kermond.ebook2tts" })
    }

    @Test
    fun weakNetworkBreaker_fullStateMachineLifecycle() {
        val breaker = WeakNetworkBreaker(failureThreshold = 2, cooldownMs = 10_000L)
        assertEquals(WeakNetworkBreaker.State.CLOSED, breaker.currentState())
        assertFalse(breaker.shouldBypassOnline(1_000L))

        breaker.recordFailure(1_000L)
        assertEquals(WeakNetworkBreaker.State.CLOSED, breaker.currentState())
        assertFalse(breaker.shouldBypassOnline(2_000L))

        // 第二次失败触发 OPEN
        breaker.recordFailure(2_000L)
        assertEquals(WeakNetworkBreaker.State.OPEN, breaker.currentState())
        assertTrue(breaker.shouldBypassOnline(5_000L))
        assertTrue(breaker.shouldBypassOnline(11_999L))

        // 冷却期满进入 HALF_OPEN 放行探测
        assertFalse(breaker.shouldBypassOnline(12_000L))
        assertEquals(WeakNetworkBreaker.State.HALF_OPEN, breaker.currentState())

        // 探测失败立刻重新进入 OPEN
        breaker.recordFailure(12_500L)
        assertEquals(WeakNetworkBreaker.State.OPEN, breaker.currentState())
        assertTrue(breaker.shouldBypassOnline(15_000L))

        // 冷却期满后探测成功复位 CLOSED
        assertFalse(breaker.shouldBypassOnline(22_500L))
        breaker.recordSuccess()
        assertEquals(WeakNetworkBreaker.State.CLOSED, breaker.currentState())
        assertFalse(breaker.shouldBypassOnline(23_000L))

        // reset 可随时清零
        breaker.recordFailure(30_000L)
        breaker.recordFailure(30_100L)
        assertTrue(breaker.shouldBypassOnline(31_000L))
        breaker.reset()
        assertEquals(WeakNetworkBreaker.State.CLOSED, breaker.currentState())
        assertFalse(breaker.shouldBypassOnline(31_100L))
    }

    @Test
    fun audioConverter_normalizes8Bit16BitAndFloatStereo() {
        // 8-bit unsigned stereo: [128 (0), 128 (0)] and [255 (+32512), 129 (+256)] -> avg ~ +16384
        val pcm8 = byteArrayOf(128.toByte(), 128.toByte(), 255.toByte(), 129.toByte())
        val out8 = SystemTtsAudioConverter.toMonoPcm16(
            pcm8,
            SystemTtsAudioConverter.ENCODING_PCM_8BIT,
            channels = 2,
        )
        assertEquals(4, out8.size)
        val bb8 = ByteBuffer.wrap(out8).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, bb8.short.toInt())
        assertTrue(bb8.short.toInt() in 16000..16600)

        // 32-bit float mono: [0.5f, NaN, -0.5f]
        val fBuf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(0.5f).putFloat(Float.NaN).putFloat(-0.5f).array()
        val outF = SystemTtsAudioConverter.toMonoPcm16(
            fBuf,
            SystemTtsAudioConverter.ENCODING_PCM_FLOAT,
            channels = 1,
        )
        assertEquals(6, outF.size)
        val bbF = ByteBuffer.wrap(outF).order(ByteOrder.LITTLE_ENDIAN)
        assertTrue(bbF.short.toInt() in 16000..16500)
        assertEquals(0, bbF.short.toInt())
        assertTrue(bbF.short.toInt() in -16500..-16000)

        // 16-bit stereo unaligned trailing byte dropped safely
        val s16 = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(200).putShort(400).put(99).array()
        val out16 = SystemTtsAudioConverter.toMonoPcm16(
            s16,
            SystemTtsAudioConverter.ENCODING_PCM_16BIT,
            channels = 2,
        )
        assertEquals(2, out16.size)
        val sample16 = ByteBuffer.wrap(out16).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        assertEquals(300, sample16)
    }
}
