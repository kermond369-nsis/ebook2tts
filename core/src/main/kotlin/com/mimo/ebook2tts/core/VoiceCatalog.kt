package com.mimo.ebook2tts.core

/** 音色性别 */
enum class VoiceGender { MALE, FEMALE, UNKNOWN }

/** 音色条目（ADR-007：Voice.name = 中文展示名） */
data class VoiceInfo(
    val id: String,
    val displayName: String,
    val gender: VoiceGender,
    val speakerId: Int,
    val group: String = "默认",
    val legacyIds: Set<String> = emptySet(),
)

/**
 * 音色命名表（RQ-109 / IM-107）。
 * Kokoro v1.1 zh：sid 0–102；默认旁白 zm_58（修复 zm_058）。
 */
object VoiceCatalog {

    const val DEFAULT_NARRATOR_ID = "zm_58"

    /** 历史兼容 ID */
    val LEGACY_ALIASES = mapOf(
        "zm_058" to "zm_58",
        "zf_03" to "zf_3",
        "en_af_maple" to "en_af_maple",
    )

    private val EN_FEMALE = listOf(
        Triple(0, "en_af_maple", "枫糖·英文女声"),
        Triple(1, "en_af_sol", "暖阳·英文女声"),
        Triple(2, "en_bf_vale", "溪谷·英文女声"),
    )

    /** 中文女声示例名（sid 3–57），其余按序号生成可读名 */
    private val ZH_FEMALE_NAMES = listOf(
        "晓晓", "晓伊", "晓妮", "晓北", "晓诗", "晓悠", "晓晴", "晓婉",
        "晓彤", "晓梦", "晓岚", "晓荷", "晓月", "晓星", "晓雪", "晓风",
        "晓露", "晓桐", "晓禾", "晓樱", "晓柳", "晓棠", "晓梅", "晓竹",
        "晓兰", "晓芷", "晓菱", "晓芙", "晓蓉", "晓蕊", "晓筠", "晓柔",
        "晓宁", "晓安", "晓宜", "晓然", "晓清", "晓澄", "晓澜", "晓汐",
        "晓汀", "晓羽", "晓翎", "晓音", "晓律", "晓笙", "晓笛", "晓筝",
        "晓歌", "晓吟", "晓咏", "晓颂",
    )

    private val ZH_MALE_NAMES = listOf(
        "云扬", "云健", "云希", "云夏", "云开", "云朗", "云澈", "云舟",
        "云策", "云驰", "云峰", "云柯", "云阔", "云澜", "云岭", "云墨",
        "云磐", "云齐", "云谦", "云乔", "云青", "云山", "云深", "云石",
        "云舒", "云松", "云涛", "云天", "云霆", "云望", "云熙", "云侠",
        "云霄", "云岩", "云野", "云逸", "云翼", "云影", "云渊", "云泽",
        "云湛", "云章", "云峥", "云舟", "云洲", "云卓", "云子", "云纵",
    )

    fun kokoroVoices(): List<VoiceInfo> {
        val list = mutableListOf<VoiceInfo>()
        EN_FEMALE.forEach { (sid, id, name) ->
            list += VoiceInfo(id, name, VoiceGender.FEMALE, sid, "英文")
        }
        for (sid in 3..57) {
            val idx = sid - 3
            val base = ZH_FEMALE_NAMES.getOrNull(idx) ?: "晓音$sid"
            val id = "zf_$sid"
            val legacy = if (sid == 3) setOf("zf_03") else emptySet()
            list += VoiceInfo(id, "$base·温婉女声", VoiceGender.FEMALE, sid, "中文女声", legacy)
        }
        for (sid in 58..102) {
            val idx = sid - 58
            val base = ZH_MALE_NAMES.getOrNull(idx) ?: "云声$sid"
            val id = "zm_$sid"
            val legacy = if (sid == 58) setOf("zm_058") else emptySet()
            list += VoiceInfo(id, "$base·沉稳男声", VoiceGender.MALE, sid, "中文男声", legacy)
        }
        return list
    }

    fun vitsZhLlVoices(): List<VoiceInfo> = listOf(
        VoiceInfo("ll_0", "清荷·中文女声", VoiceGender.FEMALE, 0, "VITS"),
        VoiceInfo("ll_1", "疏影·中文女声", VoiceGender.FEMALE, 1, "VITS"),
        VoiceInfo("ll_2", "疏桐·中文男声", VoiceGender.MALE, 2, "VITS"),
        VoiceInfo("ll_3", "寒江·中文男声", VoiceGender.MALE, 3, "VITS"),
        VoiceInfo("ll_4", "春晓·中文女声", VoiceGender.FEMALE, 4, "VITS"),
    )

    fun poolForModel(modelId: String, numSpeakers: Int = 0): List<VoiceInfo> = when {
        modelId.startsWith("kokoro") -> kokoroVoices()
        modelId.contains("zh-ll") -> vitsZhLlVoices()
        numSpeakers > 0 -> (0 until numSpeakers.coerceAtMost(64)).map { i ->
            val g = if (i % 2 == 0) VoiceGender.FEMALE else VoiceGender.MALE
            val label = if (g == VoiceGender.FEMALE) "女声$i" else "男声$i"
            VoiceInfo("spk_$i", label, g, i, "自定义")
        }
        else -> vitsZhLlVoices()
    }

    fun defaultNarrator(pool: List<VoiceInfo>): VoiceInfo {
        if (pool.isEmpty()) {
            return VoiceInfo("none", "无可用音色", VoiceGender.UNKNOWN, 0)
        }
        pool.firstOrNull { it.id == DEFAULT_NARRATOR_ID }?.let { return it }
        pool.firstOrNull { it.legacyIds.contains(DEFAULT_NARRATOR_ID) }?.let { return it }
        pool.firstOrNull { it.id == "zm_58" }?.let { return it }
        pool.firstOrNull { it.gender == VoiceGender.MALE }?.let { return it }
        return pool.first()
    }

    /** 兼容校验：legacy ID 仍可通过 */
    fun isValid(pool: List<VoiceInfo>, id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        val resolved = LEGACY_ALIASES[id] ?: id
        return pool.any { it.id == resolved || it.legacyIds.contains(id) || it.id == id }
    }

    fun resolve(pool: List<VoiceInfo>, id: String?): VoiceInfo {
        val raw = id?.let { LEGACY_ALIASES[it] ?: it }
        if (!raw.isNullOrBlank()) {
            pool.firstOrNull { it.id == raw }?.let { return it }
            pool.firstOrNull { it.legacyIds.contains(id) }?.let { return it }
        }
        return defaultNarrator(pool)
    }
}
