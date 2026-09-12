package com.mimo.ebook2tts.local.tts

/** 本地合成后端统一接口。 */
interface LocalTtsBackend {
    val sampleRate: Int
    fun isReady(): Boolean
    /** 合成一句，返回 16-bit PCM mono */
    fun synthesize(text: String, speakerId: Int, speed: Float): ByteArray
    fun release() {}
}
