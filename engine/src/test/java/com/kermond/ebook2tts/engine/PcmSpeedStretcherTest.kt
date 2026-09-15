package com.kermond.ebook2tts.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * 在线语速时域伸缩单测（RQ-508 / ADR-014）。
 *
 * 目的：证明"语速 = 客户端本地处理"这条约定**真的能动**（而不是只写进文档）：
 * - 1.0 时零开销直通（字节完全不变）；
 * - 0.5× / 2.0× 时输出样本数与理论比例同量级（Sonic 有算法时延，故给容差）；
 * - 采样率不变（调用方传入 24kHz，输出仍是 24kHz 的 PCM，只是样本数变了）。
 */
class PcmSpeedStretcherTest {

    private val sr = 24000

    private fun sinePcm(seconds: Double, freq: Double = 220.0): ByteArray {
        val n = (sr * seconds).toInt()
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (sin(2 * PI * freq * i / sr) * 12000).toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun noop_at_speed_one() {
        assertTrue(PcmSpeedStretcher.isNoop(1.0f))
        assertTrue(PcmSpeedStretcher.isNoop(1.0005f))
        assertFalse(PcmSpeedStretcher.isNoop(1.5f))
    }

    @Test
    fun passthrough_is_byte_identical_at_speed_one() {
        val pcm = sinePcm(0.2)
        val st = PcmSpeedStretcher(1.0f)
        // 语速 1.0：调用方根本不会创建处理器；即便创建，也必须原样返回
        assertArrayEqualsSafe(pcm, st.process(pcm, sr))
    }

    @Test
    fun speed_two_halves_the_duration() {
        val pcm = sinePcm(1.0)
        val st = PcmSpeedStretcher(2.0f)
        val out = st.process(pcm, sr) + st.end()
        val ratio = out.size.toDouble() / pcm.size
        println("stretch speed=2.0 ratio=$ratio bytes=${out.size}/${pcm.size}")
        assertTrue("ratio=$ratio 应在 1/2 附近", ratio in 0.35..0.65)
        assertEquals(0, out.size % 2) // PCM16 帧对齐
    }

    @Test
    fun speed_half_doubles_the_duration() {
        val pcm = sinePcm(1.0)
        val st = PcmSpeedStretcher(0.5f)
        val out = st.process(pcm, sr) + st.end()
        val ratio = out.size.toDouble() / pcm.size
        println("stretch speed=0.5 ratio=$ratio bytes=${out.size}/${pcm.size}")
        assertTrue("ratio=$ratio 应在 2.0 附近", ratio in 1.5..2.6)
    }

    @Test
    fun chunked_input_equals_whole_input_in_size() {
        val pcm = sinePcm(1.0)
        val whole = PcmSpeedStretcher(2.0f)
        val wholeOut = whole.process(pcm, sr) + whole.end()

        val chunked = PcmSpeedStretcher(2.0f)
        var acc = ByteArray(0)
        var i = 0
        // 模拟流式：按 4096 字节分块喂入（与推手线程的分块一致）
        while (i < pcm.size) {
            val end = minOf(i + 4096, pcm.size)
            acc += chunked.process(pcm.copyOfRange(i, end), sr)
            i = end
        }
        acc += chunked.end()
        val diff = kotlin.math.abs(acc.size - wholeOut.size).toDouble() / wholeOut.size
        println("chunked=${acc.size} whole=${wholeOut.size} diff=${diff}")
        assertTrue("分块与整块输出长度应一致（diff=$diff）", diff < 0.02)
    }

    private fun assertArrayEqualsSafe(a: ByteArray, b: ByteArray) {
        assertEquals("长度", a.size, b.size)
        for (i in a.indices) {
            if (a[i] != b[i]) throw AssertionError("第 $i 字节不同")
        }
    }
}
