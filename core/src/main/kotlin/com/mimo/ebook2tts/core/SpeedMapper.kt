package com.mimo.ebook2tts.core

/**
 * 语速映射（RQ-107 / AR-§4.6）。
 * readerSpeed = request.speechRate / 100f；actualSpeed = clamp(reader × (1+emotion), 0.5, 2.0)
 */
object SpeedMapper {

    const val MIN = 0.5f
    const val MAX = 2.0f
    const val EMOTION_LIMIT = 0.10f

    fun readerSpeed(speechRateAosp: Int): Float {
        // AOSP: 100 = 1.0
        val v = speechRateAosp / 100f
        return if (v <= 0f) 1.0f else v
    }

    fun actualSpeed(speechRateAosp: Int, emotionOffset: Float = 0f): Float {
        val emotion = emotionOffset.coerceIn(-EMOTION_LIMIT, EMOTION_LIMIT)
        val reader = readerSpeed(speechRateAosp)
        return (reader * (1f + emotion)).coerceIn(MIN, MAX)
    }
}
