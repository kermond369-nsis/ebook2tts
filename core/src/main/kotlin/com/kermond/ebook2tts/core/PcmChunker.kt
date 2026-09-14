package com.kermond.ebook2tts.core

/**
 * PCM 分块（AR-§4.3/4.4）：0 < len ≤ 8192；聚合 ~2048~4096 samples。
 */
object PcmChunker {
    const val MAX_CHUNK_BYTES = 8192

    fun chunk(pcm: ByteArray, preferredSamples: Int = 3072): List<ByteArray> {
        if (pcm.isEmpty()) return emptyList()
        val preferred = (preferredSamples * 2).coerceIn(512, MAX_CHUNK_BYTES)
        val out = ArrayList<ByteArray>((pcm.size / preferred) + 1)
        var i = 0
        while (i < pcm.size) {
            val end = minOf(i + preferred, pcm.size)
            out += pcm.copyOfRange(i, end)
            i = end
        }
        return out
    }

    /** 段间静音：基线 120ms，随语速缩放；16bit mono */
    fun silenceMs(baseMs: Int = 120, speed: Float = 1.0f, sampleRate: Int = 24000): ByteArray {
        val ms = (baseMs / speed.coerceIn(0.5f, 2.0f)).toInt().coerceIn(40, 400)
        val samples = (sampleRate * ms / 1000).coerceAtLeast(1)
        return ByteArray(samples * 2)
    }

    /**
     * 请求边界微淡化 2~5ms（AR-§4.8.2 A5）。
     * **仅允许用于请求首块头部与末块尾部**，严禁用于流式中间块（红线 6）。
     */
    fun fadeEdges(pcm: ByteArray, sampleRate: Int = 24000, fadeMs: Int = 3): ByteArray {
        if (pcm.size < 8) return pcm
        return fadeTail(fadeHead(pcm, sampleRate, fadeMs), sampleRate, fadeMs)
    }

    /** 仅首块头部淡入（红线 6：只作用于请求首块） */
    fun fadeHead(pcm: ByteArray, sampleRate: Int = 24000, fadeMs: Int = 3): ByteArray {
        if (pcm.size < 8) return pcm
        val out = pcm.copyOf()
        val fadeBytes = fadeLen(out.size, sampleRate, fadeMs)
        for (i in 0 until fadeBytes step 2) {
            applyGain(out, i, i.toFloat() / fadeBytes)
        }
        return out
    }

    /** 仅末块尾部淡出（红线 6：只作用于请求末块） */
    fun fadeTail(pcm: ByteArray, sampleRate: Int = 24000, fadeMs: Int = 3): ByteArray {
        if (pcm.size < 8) return pcm
        val out = pcm.copyOf()
        val fadeBytes = fadeLen(out.size, sampleRate, fadeMs)
        val tailStart = out.size - fadeBytes
        for (i in tailStart until out.size step 2) {
            applyGain(out, i, ((out.size - i).toFloat() / fadeBytes).coerceIn(0f, 1f))
        }
        return out
    }

    private fun fadeLen(size: Int, sampleRate: Int, fadeMs: Int): Int =
        ((sampleRate * fadeMs / 1000) * 2).coerceIn(2, (size / 4).coerceAtLeast(2))

    private fun applyGain(buf: ByteArray, index: Int, gain: Float) {
        if (index + 1 >= buf.size) return
        val lo = buf[index].toInt() and 0xff
        val hi = buf[index + 1].toInt()
        var v = (hi shl 8) or lo
        if (v >= 32768) v -= 65536
        val nv = (v * gain).toInt().coerceIn(-32768, 32767)
        buf[index] = (nv and 0xff).toByte()
        buf[index + 1] = ((nv shr 8) and 0xff).toByte()
    }

    fun floatToPcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var i = 0
        while (i < samples.size) {
            val s = samples[i]
            val v = (s * 32767f).toInt().coerceIn(-32768, 32767)
            out[i * 2] = (v and 0xff).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
            i++
        }
        return out
    }
}
