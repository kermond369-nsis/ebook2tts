package com.kermond.ebook2tts.core

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

    /**
     * 综合客户端（阅读器）语速与 App 设置页语速：
     * - `appFirst` / `engineFirst`：强制以 App 滑杆语速为准；
     * - `readerFirst`（默认）：阅读器显式调整过语速（>0 且 ≠100）时优先跟随阅读器，否则采用 App 滑杆语速。
     */
    fun resolveSpeed(
        speechRateAosp: Int,
        appSpeed: Float,
        speedMode: String = "readerFirst",
        emotionOffset: Float = 0f,
    ): Float {
        val emotion = emotionOffset.coerceIn(-EMOTION_LIMIT, EMOTION_LIMIT)
        val appClamped = appSpeed.coerceIn(MIN, MAX)
        val base = when {
            speedMode == "appFirst" || speedMode == "engineFirst" -> appClamped
            speechRateAosp > 0 && speechRateAosp != 100 -> readerSpeed(speechRateAosp)
            else -> appClamped
        }
        return (base * (1f + emotion)).coerceIn(MIN, MAX)
    }
}
