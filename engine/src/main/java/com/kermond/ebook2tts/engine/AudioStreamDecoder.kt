package com.kermond.ebook2tts.engine

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import com.kermond.ebook2tts.core.PcmResampler
import com.kermond.ebook2tts.core.WavHeaderParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 通用在线 TTS 音频流解码器：支持 raw PCM16、WAV 与压缩音频（MP3/Opus/AAC/OGG/FLAC）。
 * - PCM 与 WAV 全程流式边收边解（首块即出音，零整包缓冲等待）；
 * - 压缩格式走 Android 原生 MediaExtractor + MediaCodec（基于内存 MediaDataSource）解成单声道 PCM16。
 */
object AudioStreamDecoder {

    enum class Format { PCM, WAV, ENCODED }

    data class DecodeResult(
        val bytes: Int,
        val chunks: Int,
        val firstAtMs: Long,
        val cancelled: Boolean,
        val error: String? = null,
    )

    private const val MAX_HEADER_BYTES = 64 * 1024
    private const val MAX_ENCODED_BYTES = 16 * 1024 * 1024

    fun detectFormat(
        hint: String,
        contentType: String?,
        head: ByteArray,
        headLen: Int,
    ): Format {
        if (headLen >= 12 &&
            head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() &&
            head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte() &&
            head[8] == 'W'.code.toByte() && head[9] == 'A'.code.toByte() &&
            head[10] == 'V'.code.toByte() && head[11] == 'E'.code.toByte()
        ) {
            return Format.WAV
        }
        if (headLen >= 4) {
            val isId3 = head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()
            val isMp3Sync = (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0
            val isOgg = head[0] == 'O'.code.toByte() && head[1] == 'g'.code.toByte() &&
                head[2] == 'g'.code.toByte() && head[3] == 'S'.code.toByte()
            val isFlac = head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() &&
                head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte()
            val isFtyp = headLen >= 8 && head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
                head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()
            if (isId3 || isMp3Sync || isOgg || isFlac || isFtyp) {
                return Format.ENCODED
            }
        }
        val ct = contentType?.lowercase().orEmpty()
        if (ct.contains("wav") || ct.contains("wave")) return Format.WAV
        if (ct.contains("mpeg") || ct.contains("mp3") || ct.contains("ogg") ||
            ct.contains("opus") || ct.contains("aac") || ct.contains("mp4") || ct.contains("flac")
        ) {
            return Format.ENCODED
        }
        if (ct.contains("pcm") || ct.contains("l16")) return Format.PCM

        return when (hint.trim().lowercase()) {
            "wav" -> Format.WAV
            "mp3", "opus", "aac", "ogg", "flac", "m4a" -> Format.ENCODED
            else -> Format.PCM
        }
    }

    fun decodeStream(
        input: InputStream,
        formatHint: String,
        contentType: String?,
        defaultSampleRate: Int,
        onChunk: (TtsAudioChunk) -> Boolean,
        isCancelled: () -> Boolean,
    ): DecodeResult {
        val srFallback = if (defaultSampleRate > 0) defaultSampleRate else 24000
        val head = ByteArray(4096)
        var headLen = 0
        while (headLen < 12) {
            if (isCancelled()) return DecodeResult(0, 0, 0L, cancelled = true)
            val n = input.read(head, headLen, head.size - headLen)
            if (n < 0) break
            headLen += n
        }
        if (headLen == 0) return DecodeResult(0, 0, 0L, cancelled = false)

        return when (detectFormat(formatHint, contentType, head, headLen)) {
            Format.PCM -> streamPcm(head, headLen, input, srFallback, channels = 1, onChunk, isCancelled)
            Format.WAV -> streamWav(head, headLen, input, srFallback, onChunk, isCancelled)
            Format.ENCODED -> decodeEncoded(head, headLen, input, srFallback, onChunk, isCancelled)
        }
    }

    private fun streamPcm(
        initial: ByteArray,
        initialLen: Int,
        input: InputStream,
        sampleRate: Int,
        channels: Int,
        onChunk: (TtsAudioChunk) -> Boolean,
        isCancelled: () -> Boolean,
    ): DecodeResult {
        var firstAtMs = 0L
        var bytes = 0
        var chunks = 0
        var carry = ByteArray(0)
        val frameBytes = (channels.coerceAtLeast(1)) * 2

        fun emitRaw(raw: ByteArray, len: Int): Boolean {
            if (len <= 0) return true
            val combined = if (carry.isEmpty()) {
                if (len == raw.size) raw else raw.copyOf(len)
            } else {
                ByteArray(carry.size + len).also {
                    System.arraycopy(carry, 0, it, 0, carry.size)
                    System.arraycopy(raw, 0, it, carry.size, len)
                }
            }
            val rem = combined.size % frameBytes
            val alignedLen = combined.size - rem
            carry = if (rem > 0) combined.copyOfRange(alignedLen, combined.size) else ByteArray(0)
            if (alignedLen <= 0) return true
            val slice = if (alignedLen == combined.size) combined else combined.copyOf(alignedLen)
            val mono = PcmResampler.downmixToMonoPcm16(slice, channels)
            if (mono.isEmpty()) return true
            if (firstAtMs == 0L) firstAtMs = System.currentTimeMillis()
            bytes += mono.size
            chunks++
            return onChunk(TtsAudioChunk(mono, sampleRate))
        }

        if (initialLen > 0 && !emitRaw(initial, initialLen)) {
            return DecodeResult(bytes, chunks, firstAtMs, cancelled = true)
        }
        val buf = ByteArray(8192)
        while (true) {
            if (isCancelled()) return DecodeResult(bytes, chunks, firstAtMs, cancelled = true)
            val n = input.read(buf)
            if (n < 0) break
            if (n > 0 && !emitRaw(buf, n)) {
                return DecodeResult(bytes, chunks, firstAtMs, cancelled = true)
            }
        }
        return DecodeResult(bytes, chunks, firstAtMs, cancelled = false)
    }

    private fun streamWav(
        head: ByteArray,
        headLen: Int,
        input: InputStream,
        srFallback: Int,
        onChunk: (TtsAudioChunk) -> Boolean,
        isCancelled: () -> Boolean,
    ): DecodeResult {
        val headerBuf = ByteArrayOutputStream(MAX_HEADER_BYTES.coerceAtMost(headLen + 1024))
        headerBuf.write(head, 0, headLen)
        var info = WavHeaderParser.parse(headerBuf.toByteArray())
        val tmp = ByteArray(4096)
        while (info == null && headerBuf.size() < MAX_HEADER_BYTES) {
            if (isCancelled()) return DecodeResult(0, 0, 0L, cancelled = true)
            val n = input.read(tmp)
            if (n < 0) break
            headerBuf.write(tmp, 0, n)
            info = WavHeaderParser.parse(headerBuf.toByteArray())
        }
        val parsed = info ?: return DecodeResult(0, 0, 0L, cancelled = false, error = "WAV 头解析失败或非 PCM16")
        val allHead = headerBuf.toByteArray()
        val initialPcmLen = (allHead.size - parsed.dataOffset).coerceAtLeast(0)
        val initialPcm = if (initialPcmLen > 0) {
            allHead.copyOfRange(parsed.dataOffset, allHead.size)
        } else {
            ByteArray(0)
        }
        return streamPcm(
            initial = initialPcm,
            initialLen = initialPcm.size,
            input = input,
            sampleRate = parsed.sampleRate.takeIf { it > 0 } ?: srFallback,
            channels = parsed.channels.coerceAtLeast(1),
            onChunk = onChunk,
            isCancelled = isCancelled,
        )
    }

    private fun decodeEncoded(
        head: ByteArray,
        headLen: Int,
        input: InputStream,
        srFallback: Int,
        onChunk: (TtsAudioChunk) -> Boolean,
        isCancelled: () -> Boolean,
    ): DecodeResult {
        val baos = ByteArrayOutputStream(headLen + 16384)
        baos.write(head, 0, headLen)
        val buf = ByteArray(8192)
        while (true) {
            if (isCancelled()) return DecodeResult(0, 0, 0L, cancelled = true)
            val n = input.read(buf)
            if (n < 0) break
            if (baos.size() + n > MAX_ENCODED_BYTES) {
                return DecodeResult(0, 0, 0L, cancelled = false, error = "压缩音频响应超过 16MB 上限")
            }
            baos.write(buf, 0, n)
        }
        val data = baos.toByteArray()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var firstAtMs = 0L
        var bytes = 0
        var chunks = 0
        return try {
            extractor.setDataSource(ByteArrayMediaDataSource(data))
            var audioTrack = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    trackFormat = fmt
                    break
                }
            }
            if (audioTrack < 0 || trackFormat == null) {
                return DecodeResult(0, 0, 0L, cancelled = false, error = "未找到音频轨")
            }
            extractor.selectTrack(audioTrack)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)!!
            var sampleRate = trackFormat.integerOrDefault(MediaFormat.KEY_SAMPLE_RATE, srFallback)
            var channels = trackFormat.integerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (isCancelled()) return DecodeResult(bytes, chunks, firstAtMs, cancelled = true)
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                when {
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val raw = ByteArray(info.size)
                            outBuf.get(raw)
                            val aligned = raw.size - (raw.size % (channels.coerceAtLeast(1) * 2))
                            if (aligned > 0) {
                                val trimmed = if (aligned == raw.size) raw else raw.copyOf(aligned)
                                val mono = PcmResampler.downmixToMonoPcm16(trimmed, channels)
                                if (mono.isNotEmpty()) {
                                    if (firstAtMs == 0L) firstAtMs = System.currentTimeMillis()
                                    bytes += mono.size
                                    chunks++
                                    if (!onChunk(TtsAudioChunk(mono, sampleRate))) {
                                        codec.releaseOutputBuffer(outIdx, false)
                                        return DecodeResult(bytes, chunks, firstAtMs, cancelled = true)
                                    }
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = codec.outputFormat
                        sampleRate = fmt.integerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = fmt.integerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                    }
                }
            }
            DecodeResult(bytes, chunks, firstAtMs, cancelled = false)
        } catch (t: Throwable) {
            DecodeResult(bytes, chunks, firstAtMs, cancelled = false, error = t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.integerOrDefault(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    private class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position < 0 || position >= data.size) return -1
            val avail = (data.size - position.toInt()).coerceAtMost(size)
            if (avail <= 0) return 0
            System.arraycopy(data, position.toInt(), buffer, offset, avail)
            return avail
        }

        override fun getSize(): Long = data.size.toLong()

        override fun close() = Unit
    }
}
