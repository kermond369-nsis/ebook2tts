package com.kermond.ebook2tts.core

/**
 * 在线预置音色条目（小米 MiMo 官方预置音色）。
 */
data class OnlineVoicePreset(
    val id: String,
    val label: String,
    val lang: String,
    val gender: String,
)

/**
 * 小米 MiMo 官方预置音色表（纯逻辑，零 Android 依赖）。
 */
object OnlineVoiceCatalog {

    val MIMO_PRESETS: List<OnlineVoicePreset> = listOf(
        OnlineVoicePreset(id = "白桦", label = "白桦 · 中文男声", lang = "zh", gender = "male"),
        OnlineVoicePreset(id = "苏打", label = "苏打 · 中文男声", lang = "zh", gender = "male"),
        OnlineVoicePreset(id = "冰糖", label = "冰糖 · 中文女声", lang = "zh", gender = "female"),
        OnlineVoicePreset(id = "茉莉", label = "茉莉 · 中文女声", lang = "zh", gender = "female"),
        OnlineVoicePreset(id = "Mia", label = "Mia · 英文女声", lang = "en", gender = "female"),
        OnlineVoicePreset(id = "Chloe", label = "Chloe · 英文女声", lang = "en", gender = "female"),
        OnlineVoicePreset(id = "Milo", label = "Milo · 英文男声", lang = "en", gender = "male"),
        OnlineVoicePreset(id = "Dean", label = "Dean · 英文男声", lang = "en", gender = "male"),
    )

    fun isPreset(name: String?): Boolean {
        val raw = name?.trim().orEmpty()
        if (raw.isEmpty()) return false
        return MIMO_PRESETS.any {
            it.id.equals(raw, ignoreCase = true) || it.label.equals(raw, ignoreCase = true)
        }
    }

    /**
     * 解析本次请求生效的 MiMo 音色：
     * 1. 若外部调用方/试听显式指定了有效预置名（或展示标签），优先使用；
     * 2. 否则使用用户配置的默认在线音色（若也在预置表中则归一到标准 id）；
     * 3. 否则回落到 [OnlineSettings.DEFAULT_VOICE]。
     */
    fun resolveMimoVoice(requested: String?, configuredDefault: String): String {
        val reqMatch = findPresetId(requested)
        if (reqMatch != null) return reqMatch
        val cfgTrim = configuredDefault.trim()
        val cfgMatch = findPresetId(cfgTrim)
        if (cfgMatch != null) return cfgMatch
        return if (cfgTrim.isNotEmpty()) cfgTrim else OnlineSettings.DEFAULT_VOICE
    }

    private fun findPresetId(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        return MIMO_PRESETS.firstOrNull {
            it.id.equals(t, ignoreCase = true) || it.label.equals(t, ignoreCase = true)
        }?.id
    }
}
