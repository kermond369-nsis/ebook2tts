package com.mimo.ebook2tts.local.analysis

/**
 * 情绪档位：映射到 TTS 音高/语速偏置与 MiMo 风格标签。
 */
enum class Emotion(
    val id: String,
    val label: String,
    val pitchMul: Float,
    val rateMul: Float,
    val styleTag: String,
) {
    CALM("calm", "沉稳", 1.00f, 1.00f, "（沉稳）"),
    JOY("joy", "喜悦", 1.08f, 1.06f, "（微笑）"),
    ANGER("anger", "愤怒", 0.95f, 1.18f, "（压抑的怒意）"),
    SADNESS("sadness", "悲伤", 0.92f, 0.88f, "（低落）"),
    FEAR("fear", "惊恐", 1.12f, 1.15f, "（紧张）"),
    SURPRISE("surprise", "惊讶", 1.10f, 1.10f, "（惊讶）"),
    WHISPER("whisper", "低语", 0.90f, 0.90f, "（小声）"),
    ;

    companion object {
        fun fromId(id: String?): Emotion =
            entries.firstOrNull { it.id == id } ?: CALM
    }
}
