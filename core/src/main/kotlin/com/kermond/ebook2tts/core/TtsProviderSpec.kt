package com.kermond.ebook2tts.core

import java.util.Locale

/**
 * 通用在线 TTS 引擎类型（纯逻辑，零 Android 依赖）。
 */
enum class TtsProviderKind(
    val id: String,
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val defaultVoice: String,
    val defaultSampleRate: Int,
    val supportsNativeSpeed: Boolean,
    val supportsRoleVoiceDesign: Boolean,
    val requiresApiKey: Boolean,
) {
    MIMO(
        id = "mimo",
        displayName = "小米 MiMo（含 AI 角色造音色）",
        defaultBaseUrl = OnlineSettings.BILLING_BASE_URL,
        defaultModel = OnlineSettings.DEFAULT_MODEL,
        defaultVoice = OnlineSettings.DEFAULT_VOICE,
        defaultSampleRate = OnlineSettings.SAMPLE_RATE,
        supportsNativeSpeed = false,
        supportsRoleVoiceDesign = true,
        requiresApiKey = true,
    ),
    OPENAI_COMPAT(
        id = "openai",
        displayName = "OpenAI 兼容（/v1/audio/speech）",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "tts-1",
        defaultVoice = "alloy",
        defaultSampleRate = 24000,
        supportsNativeSpeed = true,
        supportsRoleVoiceDesign = false,
        requiresApiKey = false,
    ),
    CUSTOM_HTTP(
        id = "custom_http",
        displayName = "自定义 HTTP 模板（通用转接）",
        defaultBaseUrl = "",
        defaultModel = "",
        defaultVoice = "",
        defaultSampleRate = 24000,
        supportsNativeSpeed = false,
        supportsRoleVoiceDesign = false,
        requiresApiKey = false,
    ),
    ;

    companion object {
        val DEFAULT: TtsProviderKind = MIMO

        fun parse(raw: String?): TtsProviderKind {
            val norm = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return values().firstOrNull { it.id == norm } ?: DEFAULT
        }
    }
}

/** 常用在线 TTS 接入预设（纯数据） */
data class TtsProviderPreset(
    val id: String,
    val name: String,
    val kind: TtsProviderKind,
    val baseUrl: String,
    val model: String,
    val voice: String,
    val responseFormat: String,
)

object TtsProviderPresets {
    val MIMO_BILLING = TtsProviderPreset(
        id = "mimo_billing",
        name = "小米 MiMo",
        kind = TtsProviderKind.MIMO,
        baseUrl = OnlineSettings.BILLING_BASE_URL,
        model = OnlineSettings.DEFAULT_MODEL,
        voice = OnlineSettings.DEFAULT_VOICE,
        responseFormat = "pcm",
    )

    val OPENAI_OFFICIAL = TtsProviderPreset(
        id = "openai_official",
        name = "OpenAI 官方",
        kind = TtsProviderKind.OPENAI_COMPAT,
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4o-mini-tts",
        voice = "alloy",
        responseFormat = "pcm",
    )

    val SILICONFLOW_COSYVOICE2 = TtsProviderPreset(
        id = "siliconflow_cosyvoice2",
        name = "硅基流动 CosyVoice2",
        kind = TtsProviderKind.OPENAI_COMPAT,
        baseUrl = "https://api.siliconflow.cn/v1",
        model = "FunAudioLLM/CosyVoice2-0.5B",
        voice = "FunAudioLLM/CosyVoice2-0.5B:alex",
        responseFormat = "pcm",
    )

    val CUSTOM_OPENAI_COMPAT = TtsProviderPreset(
        id = "custom_openai_compat",
        name = "通用兼容网关",
        kind = TtsProviderKind.OPENAI_COMPAT,
        baseUrl = "https://your-tts-gateway.example.com/v1",
        model = "tts-1",
        voice = "alloy",
        responseFormat = "wav",
    )

    val ALL = listOf(
        MIMO_BILLING,
        OPENAI_OFFICIAL,
        SILICONFLOW_COSYVOICE2,
        CUSTOM_OPENAI_COMPAT,
    )
}

/**
 * 自定义 HTTP 模板渲染（支持 `{{text}}` / `{{voice}}` / `{{model}}` / `{{speed}}` / `{{apiKey}}`）。
 */
object CustomHttpTemplate {

    fun escapeJsonString(raw: String): String {
        if (raw.isEmpty()) return ""
        val sb = StringBuilder(raw.length + 8)
        for (ch in raw) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        return sb.toString()
    }

    fun renderBody(
        template: String,
        text: String,
        voice: String,
        model: String,
        speed: Float,
        apiKey: String,
    ): String {
        val escText = escapeJsonString(text)
        val escVoice = escapeJsonString(voice)
        val escModel = escapeJsonString(model)
        val escKey = escapeJsonString(apiKey)
        val speedStr = String.format(Locale.US, "%.2f", speed.coerceIn(0.25f, 4.0f))

        if (template.isBlank()) {
            return buildString {
                append("{")
                append("\"model\":\"").append(escModel).append("\",")
                append("\"input\":\"").append(escText).append("\",")
                append("\"voice\":\"").append(escVoice).append("\",")
                append("\"speed\":").append(speedStr)
                append("}")
            }
        }
        return template
            .replace("{{text}}", escText)
            .replace("{{voice}}", escVoice)
            .replace("{{model}}", escModel)
            .replace("{{speed}}", speedStr)
            .replace("{{apiKey}}", escKey)
    }

    fun parseHeaders(raw: String, apiKey: String): List<Pair<String, String>> {
        if (raw.isBlank()) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val colon = trimmed.indexOf(':')
            if (colon <= 0) continue
            val k = trimmed.substring(0, colon).trim()
            val v = trimmed.substring(colon + 1).trim().replace("{{apiKey}}", apiKey)
            if (k.isNotEmpty()) out += k to v
        }
        return out
    }

    fun hasSpeedPlaceholder(template: String): Boolean = template.contains("{{speed}}")
}

/** WAV 头解析信息 */
data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dataOffset: Int,
)

/**
 * 标准 RIFF/WAVE 头解析器（纯字节解析，不限制子块顺序）。
 */
object WavHeaderParser {

    fun parse(bytes: ByteArray): WavInfo? {
        if (bytes.size < 44) return null
        if (!asciiEq(bytes, 0, "RIFF") || !asciiEq(bytes, 8, "WAVE")) return null

        var audioFormat = -1
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
            val chunkSizeLong = u32le(bytes, pos + 4)
            val payloadPos = pos + 8
            when (chunkId) {
                "fmt " -> {
                    if (payloadPos + 16 > bytes.size) return null
                    audioFormat = u16le(bytes, payloadPos)
                    channels = u16le(bytes, payloadPos + 2)
                    sampleRate = u32le(bytes, payloadPos + 4).toInt()
                    bitsPerSample = u16le(bytes, payloadPos + 14)
                }
                "data" -> {
                    if (audioFormat != 1 || bitsPerSample != 16 || channels !in 1..8 || sampleRate <= 0) {
                        return null
                    }
                    return WavInfo(
                        sampleRate = sampleRate,
                        channels = channels,
                        bitsPerSample = bitsPerSample,
                        dataOffset = payloadPos,
                    )
                }
            }
            if (chunkSizeLong > Int.MAX_VALUE.toLong()) return null
            val padded = (chunkSizeLong + (chunkSizeLong and 1L)).toInt()
            val next = payloadPos + padded
            if (next <= pos) return null
            pos = next
        }
        return null
    }

    private fun asciiEq(b: ByteArray, off: Int, tag: String): Boolean {
        if (off + 4 > b.size) return false
        for (i in 0 until 4) {
            if (b[off + i].toInt() != tag[i].code) return false
        }
        return true
    }

    private fun u16le(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32le(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFFL) or
            ((b[off + 1].toLong() and 0xFFL) shl 8) or
            ((b[off + 2].toLong() and 0xFFL) shl 16) or
            ((b[off + 3].toLong() and 0xFFL) shl 24)
}

/**
 * 有状态的流式线性重采样器：跨块保留末样本与小数相位，确保段内块间拼接无爆音。
 */
class StreamingPcmResampler(
    private val fromRate: Int,
    private val toRate: Int,
) {
    private var hasPrev = false
    private var prevSample = 0
    private var srcPos = 0.0

    fun process(pcm: ByteArray): ByteArray {
        if (fromRate <= 0 || toRate <= 0 || fromRate == toRate || pcm.size < 2) {
            return pcm
        }
        val nIn = pcm.size / 2
        if (nIn == 0) return ByteArray(0)
        val samples = ShortArray(nIn) { i ->
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            ((hi shl 8) or lo).toShort()
        }
        val step = fromRate.toDouble() / toRate.toDouble()
        val estOut = ((nIn / step) + 4).toInt().coerceAtLeast(4)
        val dynamicOut = ArrayList<Short>(estOut)
        while (true) {
            val idx = kotlin.math.floor(srcPos).toInt()
            if (idx >= nIn - 1) break
            val frac = srcPos - idx
            val s0 = if (idx < 0) {
                if (hasPrev) prevSample else samples[0].toInt()
            } else {
                samples[idx].toInt()
            }
            val s1 = if (idx + 1 < 0) samples[0].toInt() else samples[idx + 1].toInt()
            val v = kotlin.math.round(s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767)
            dynamicOut.add(v.toShort())
            srcPos += step
        }
        prevSample = samples[nIn - 1].toInt()
        hasPrev = true
        srcPos -= nIn.toDouble()

        val outCount = dynamicOut.size
        val bytes = ByteArray(outCount * 2)
        for (i in 0 until outCount) {
            val s = dynamicOut[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    fun flush(): ByteArray {
        if (!hasPrev || fromRate <= 0 || toRate <= 0 || fromRate == toRate) return ByteArray(0)
        val v = prevSample.coerceIn(-32768, 32767)
        hasPrev = false
        srcPos = 0.0
        return byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    }
}

/** PCM16 工具：多声道混音为单声道 + 一次性重采样 */
object PcmResampler {

    fun downmixToMonoPcm16(pcm: ByteArray, channels: Int): ByteArray {
        if (channels <= 1 || pcm.size < 2) return pcm
        val frameBytes = channels * 2
        val frames = pcm.size / frameBytes
        if (frames == 0) return ByteArray(0)
        val out = ByteArray(frames * 2)
        for (f in 0 until frames) {
            var sum = 0
            val base = f * frameBytes
            for (c in 0 until channels) {
                val off = base + c * 2
                val lo = pcm[off].toInt() and 0xFF
                val hi = pcm[off + 1].toInt()
                sum += (hi shl 8) or lo
            }
            val avg = (sum / channels).coerceIn(-32768, 32767)
            out[f * 2] = (avg and 0xFF).toByte()
            out[f * 2 + 1] = ((avg shr 8) and 0xFF).toByte()
        }
        return out
    }

    fun resampleMonoPcm16(pcm: ByteArray, fromRate: Int, toRate: Int): ByteArray {
        if (fromRate <= 0 || toRate <= 0 || fromRate == toRate || pcm.size < 2) return pcm
        val r = StreamingPcmResampler(fromRate, toRate)
        val body = r.process(pcm)
        val tail = r.flush()
        return if (tail.isEmpty()) body else body + tail
    }
}
