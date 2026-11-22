package com.kermond.ebook2tts.engine

import com.kermond.ebook2tts.core.TtsProviderKind

/** 解码后的单声道 PCM16 音频块 */
data class TtsAudioChunk(
    val pcm16Mono: ByteArray,
    val sampleRate: Int,
)

/**
 * 通用在线 TTS 引擎适配器接口（仅在 `:tts_service` 或主进程测试连通性时调用）。
 */
interface TtsProvider {
    val kind: TtsProviderKind

    fun synthesize(
        request: OnlineRequest,
        text: String,
        speed: Float,
        onChunk: (TtsAudioChunk) -> Boolean,
        isCancelled: () -> Boolean,
    ): OnlineOutcome

    /** 校验配置/连通性：null = 通过；否则为脱敏后的可读错误摘要 */
    fun validate(request: OnlineRequest): String?
}

/** 错误文本脱敏与截断（防止错误回显泄露 Authorization / API Key） */
object ErrorSanitizer {
    private val SENSITIVE_BEARER = Regex("""(?i)Bearer\s+[A-Za-z0-9._-]+""")
    private val SENSITIVE_KEY = Regex("""\b(?:sk|tp)-[A-Za-z0-9_-]{4,}""")

    fun sanitize(raw: String?, maxLen: Int = 200): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        return text
            .replace(SENSITIVE_BEARER, "Bearer ***")
            .replace(SENSITIVE_KEY, "***")
            .take(maxLen)
    }
}
