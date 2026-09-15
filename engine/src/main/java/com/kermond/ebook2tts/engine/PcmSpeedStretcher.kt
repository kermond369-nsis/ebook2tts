package com.kermond.ebook2tts.engine

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 在线语速：**客户端时域伸缩（变速不变调）** —— RQ-508 / ADR-014。
 *
 * 背景（官方 API 文档实查，2026-09-15）：MiMo TTS 请求体只有
 * `messages` / `model` / `audio{format,voice,optimize_text_preview}` / `stream`，**没有 `speed` 参数**；
 * 官方所称"支持语速"仅指 `user` 消息里的**自然语言指令**（定性、无标称倍率），无法满足
 * RQ-107 的「阅读器 0.5~2.0× 线性、阅读器优先」。故在线路径的语速在**本机**做。
 *
 * 选型（"成熟项目优先"铁律）：`androidx.media3 SonicAudioProcessor`（Apache-2.0，AOSP/ExoPlayer 同源，
 * 底层即 Sonic = WSOLA 时域压扩），**不自造 DSP**（不引入 FFmpeg：移动端体积/许可/维护成本不划算）。
 *
 * 纪律：
 * - **采样率与声道不变**（`SAMPLE_RATE_NO_CHANGE`）→ `callback.start` 契约与既有采样率纪律完全不变；
 * - 语速 ≈ 1.0 时**完全不启用**（`isNoop`，零开销，红线：不给本地/默认路径添负担）；
 * - 只在**后处理（推手）线程**使用，绝不在 native 守卫锁内、绝不在合成线程做重活（红线 7）；
 * - 任何异常 → 原样透传（绝不让语速把朗读搞停，红线 1）。
 *
 * 语义与本地一致：本地走 `SpeedMapper.actualSpeed()` 交给 native；在线走同一映射值交给本类。
 *
 * 注：media3 的 `@UnstableApi` 是 **Java/AndroidX 标记**，必须用 `androidx.annotation.OptIn`
 * （Kotlin 的 `kotlin.OptIn` 会被编译器判为"非 opt-in 标记"并忽略 —— agy P4 复核指出）。
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PcmSpeedStretcher(private val speed: Float) {

    private val processor = SonicAudioProcessor()
    private var configured = false
    private var dead = false

    /** 处理一块 PCM16（单声道，`sampleRate` 采样率）；返回处理后的 PCM（可能为空） */
    fun process(pcm: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray {
        if (dead || pcm.isEmpty() || isNoop(speed)) return pcm
        return try {
            ensureConfigured(sampleRate, channels)
            val input = ByteBuffer.allocateDirect(pcm.size).order(ByteOrder.nativeOrder())
            input.put(pcm).flip()
            processor.queueInput(input)
            drain()
        } catch (t: Throwable) {
            dead = true
            Log.w(TAG, "stretch failed, passthrough: ${t.javaClass.simpleName}: ${t.message}")
            pcm
        }
    }

    /**
     * 收尾：通知输入结束并榨出 Sonic 内部残余样本（否则末段会被截断）。
     * 调用方应在**最后一块之后**调用一次。
     */
    fun end(): ByteArray {
        if (dead || isNoop(speed) || !configured) return ByteArray(0)
        return try {
            processor.queueEndOfStream()
            drain()
        } catch (t: Throwable) {
            dead = true
            Log.w(TAG, "stretch end failed: ${t.javaClass.simpleName}: ${t.message}")
            ByteArray(0)
        }
    }

    private fun ensureConfigured(sampleRate: Int, channels: Int) {
        if (configured) return
        // 注意：media3 1.9.x 的 `AudioProcessor.flush()`（无参）在接口默认实现里**直接抛异常**
        // （"AudioProcessor must implement at least one #flush() overload."），必须用带
        // StreamMetadata 的重载；SonicAudioProcessor 实现的正是后者。
        processor.setSpeed(speed)
        processor.setOutputSampleRateHz(SonicAudioProcessor.SAMPLE_RATE_NO_CHANGE)
        processor.configure(AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        configured = true
    }

    private fun drain(): ByteArray {
        val out = processor.getOutput()
        if (out.remaining() <= 0) return ByteArray(0)
        val bytes = ByteArray(out.remaining())
        out.get(bytes)
        return bytes
    }

    companion object {
        private const val TAG = "PcmSpeed"

        /** 语速否为"免处理"（1.0 附近，阈值与 media3 内部 CLOSE_THRESHOLD 同量级） */
        fun isNoop(speed: Float): Boolean = kotlin.math.abs(speed - 1f) < 0.001f

        /** 把倍数换算成"时长比"（供诊断/测试：0.5× → 时长 ×2） */
        fun durationRatio(speed: Float): Float = 1f / speed.coerceAtLeast(0.01f)
    }
}
