package com.kermond.ebook2tts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P6 / IM-517：在线音色映射（本地音色 → MiMo 预置）回归。 */
class OnlineVoiceMapTest {

    private val pool = VoiceCatalog.poolForModel("kokoro-int8")

    private fun voice(id: String): VoiceInfo = VoiceCatalog.resolve(pool, id)

    @Test
    fun chinese_female_maps_to_female_presets_only() {
        // 全部中文女声都必须映射到中文女声预置（绝不再出现"女旁白听到男声"）
        val females = pool.filter { it.gender == VoiceGender.FEMALE && !OnlineVoiceMap.isEnglish(it) }
        assertTrue("中文女声池不应为空", females.isNotEmpty())
        val allowed = setOf("冰糖", "茉莉")
        females.forEach { v ->
            val got = OnlineVoiceMap.pick(v.id, pool)
            assertTrue("$v → $got 不是中文女声预置", got in allowed)
        }
    }

    @Test
    fun chinese_male_maps_to_male_presets_only() {
        val males = pool.filter { it.gender == VoiceGender.MALE && !OnlineVoiceMap.isEnglish(it) }
        assertTrue(males.isNotEmpty())
        val allowed = setOf("苏打", "白桦")
        males.forEach { v ->
            assertTrue("${v.id} 映射越界", OnlineVoiceMap.pick(v.id, pool) in allowed)
        }
    }

    @Test
    fun english_voices_map_to_english_presets() {
        val en = pool.filter { OnlineVoiceMap.isEnglish(it) }
        assertTrue(en.isNotEmpty())
        val allowed = setOf("Mia", "Chloe", "Milo", "Dean")
        en.forEach { v ->
            assertTrue("${v.id} 英文音色映射越界", OnlineVoiceMap.pick(v.id, pool) in allowed)
        }
    }

    @Test
    fun unknown_requested_falls_back_to_narrator_gender() {
        // 阅读器传入我们不认识的音色名（真机：Legado 的「松鹤庭沐」）→ 跟随旁白性别，而不是写死男声
        val femaleNarrator = voice("zf_3")
        val got = OnlineVoiceMap.pick("松鹤庭沐", pool, fallbackGender = femaleNarrator.gender)
        assertTrue("未知音色应跟随旁白性别", got in setOf("冰糖", "茉莉"))

        val maleNarrator = voice("zm_58")
        val got2 = OnlineVoiceMap.pick(null, pool, fallbackGender = maleNarrator.gender)
        assertTrue("空请求应跟随旁白性别", got2 in setOf("苏打", "白桦"))
    }

    @Test
    fun unknown_gender_uses_safe_default_not_male() {
        assertEquals(OnlineVoiceMap.DEFAULT_PRESET, OnlineVoiceMap.pick(null, emptyList()))
        assertEquals("冰糖", OnlineVoiceMap.DEFAULT_PRESET)
    }

    @Test
    fun same_gender_voices_are_stable_and_spread_across_presets() {
        val females = pool.filter { it.gender == VoiceGender.FEMALE && !OnlineVoiceMap.isEnglish(it) }
        // 稳定性：同输入必得同输出
        val first = females.first()
        assertEquals(OnlineVoiceMap.pick(first.id, pool), OnlineVoiceMap.pick(first.id, pool))
        // 分散性：至少覆盖到两个中文女声预置（可区分）
        val distinct = females.map { OnlineVoiceMap.pick(it.id, pool) }.toSet()
        assertTrue("中文女声应至少覆盖 2 个在线预置，实际=$distinct", distinct.size >= 2)
    }

    @Test
    fun preset_table_matches_official_docs() {
        // 官方预置表 9 个（mimo_default 为默认别名，不参与映射）
        val ids = OnlineVoiceMap.PRESETS.map { it.id }.toSet()
        assertEquals(
            setOf("冰糖", "茉莉", "苏打", "白桦", "Mia", "Chloe", "Milo", "Dean"),
            ids
        )
    }
}
