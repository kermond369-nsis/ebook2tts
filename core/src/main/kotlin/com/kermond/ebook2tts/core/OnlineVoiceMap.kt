package com.kermond.ebook2tts.core

/**
 * 在线（MiMo-V2.5-TTS）预置音色映射 —— P6 / IM-517。
 *
 * 为什么需要：在线路径此前把音色写死为 `ConfigStore.onlineVoice()`（默认**白桦＝男声**），
 * 于是"试听任意音色""把旁白设成女声"在在线模式下全部失效（甲方 P0 报障 ①②④，真机日志实证）。
 *
 * 映射规则（先查官方文档，禁止猜测）：
 * - 预置音色表 9 个（官方《Speech Synthesis (MiMo-V2.5-TTS Series)》2026-09-15 实查）：
 *   中文女 `冰糖`/`茉莉`，中文男 `苏打`/`白桦`，英文女 `Mia`/`Chloe`，英文男 `Milo`/`Dean`；
 * - 先按**语言**（本地音色 id `en_` 前缀 / 展示名含「英文」⇒ 英文），再按**性别**（[VoiceInfo.gender]）；
 * - 同(语言,性别)内有 2 个预置时，按音色 id 的稳定散列二选一 ⇒ 不同本地音色在在线模式下仍**可区分**；
 * - 请求音色无法解析（如阅读器传入我们不认识的音色名）⇒ 回落到**旁白性别**，而非硬编码男声。
 */
object OnlineVoiceMap {

    /** MiMo 预置音色（语言 + 性别均来自官方文档，不得改写） */
    data class Preset(val id: String, val zh: Boolean, val gender: VoiceGender)

    val PRESETS: List<Preset> = listOf(
        Preset("冰糖", zh = true, gender = VoiceGender.FEMALE),
        Preset("茉莉", zh = true, gender = VoiceGender.FEMALE),
        Preset("苏打", zh = true, gender = VoiceGender.MALE),
        Preset("白桦", zh = true, gender = VoiceGender.MALE),
        Preset("Mia", zh = false, gender = VoiceGender.FEMALE),
        Preset("Chloe", zh = false, gender = VoiceGender.FEMALE),
        Preset("Milo", zh = false, gender = VoiceGender.MALE),
        Preset("Dean", zh = false, gender = VoiceGender.MALE),
    )

    /** 性别无法判定时的兜底：中文女声（避免复现"永远男声"的老问题） */
    const val DEFAULT_PRESET = "冰糖"

    /** 是否英文音色（本地池约定：`en_*` id 或展示名含「英文」） */
    fun isEnglish(voice: VoiceInfo?): Boolean {
        val id = voice?.id.orEmpty()
        val name = voice?.displayName.orEmpty()
        return id.startsWith("en_") || name.contains("英文")
    }

    /** 由请求串（id / 展示名 / 兼容 id）解析本地音色；解析不到返回 null（**不使用 resolve 的兜底**） */
    fun lookup(requested: String?, pool: List<VoiceInfo>): VoiceInfo? {
        if (requested.isNullOrBlank()) return null
        return if (VoiceCatalog.isValid(pool, requested)) VoiceCatalog.resolve(pool, requested) else null
    }

    fun genderOf(requested: String?, pool: List<VoiceInfo>): VoiceGender =
        lookup(requested, pool)?.gender ?: VoiceGender.UNKNOWN

    /**
     * 选取在线音色。
     *
     * @param requested 请求音色（试听所选 / 阅读器显式 / 旁白），可为 null 或不可识别
     * @param pool 当前模型对应的本地音色池（仅用其 id/性别/语言元数据，无需加载模型）
     * @param fallbackGender 请求音色不可解析时采用的性别（调用方通常传**旁白性别**）
     */
    fun pick(
        requested: String?,
        pool: List<VoiceInfo>,
        fallbackGender: VoiceGender = VoiceGender.UNKNOWN,
    ): String {
        val v = lookup(requested, pool)
        val zh = !isEnglish(v)
        val gender = if (v != null) v.gender else fallbackGender
        if (gender == VoiceGender.UNKNOWN) return DEFAULT_PRESET
        val candidates = PRESETS.filter { it.zh == zh && it.gender == gender }
        if (candidates.isEmpty()) return DEFAULT_PRESET
        if (candidates.size == 1) return candidates[0].id
        val key = v?.id ?: requested.orEmpty()
        val idx = Math.floorMod(key.hashCode(), candidates.size)
        return candidates[idx].id
    }
}
