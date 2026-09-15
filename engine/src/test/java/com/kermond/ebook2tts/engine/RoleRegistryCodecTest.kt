package com.kermond.ebook2tts.engine

import com.kermond.ebook2tts.core.RoleVoiceDesign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 角色库编解码单测（ADR-013：畸形整份作废；LLM 输出容错解析） */
class RoleRegistryCodecTest {

    @Test
    fun roundTrip_preserves_fields_and_clamps_design() {
        val profiles = linkedMapOf(
            "林安" to RoleVoiceDesign(
                name = "林安",
                gender = "male",
                ageHint = "青年",
                personality = "冷静",
                speechStyle = "简短",
                toneTags = "低声,克制",
                design = "青年男性，声线清亮偏薄，语速平稳。",
            ),
            "苏岑" to RoleVoiceDesign(name = "苏岑", gender = "female", design = "少女，软糯"),
        )
        val json = RoleRegistryCodec.encodeAll(profiles)
        val back = RoleRegistryCodec.decodeAll(json)
        assertEquals(2, back.size)
        assertEquals("青年男性，声线清亮偏薄，语速平稳。", back["林安"]!!.design)
        assertEquals("female", back["苏岑"]!!.gender)
    }

    @Test
    fun decode_malformed_returns_empty() {
        assertTrue(RoleRegistryCodec.decodeAll("").isEmpty())
        assertTrue(RoleRegistryCodec.decodeAll("not-json").isEmpty())
        assertTrue(RoleRegistryCodec.decodeAll("[1,2,3]").isEmpty())
    }

    @Test
    fun decode_skips_non_object_entries_not_whole_table() {
        // 单个条目畸形 → 跳过该条；合法条目仍生效（不含密钥/响应体，仅本地库结构）
        val json = """{"林安":{"design":"青年男性","gender":"male"},"坏条目":42}"""
        val back = RoleRegistryCodec.decodeAll(json)
        assertEquals(1, back.size)
        assertEquals("青年男性", back["林安"]!!.design)
    }

    @Test
    fun parseLlm_handles_fence_and_preamble() {
        val raw = """
            好的，这是结果：
            ```json
            {"name":"林安","gender":"Male","ageHint":"青年","personality":"冷静克制",
             "speechStyle":"语速平稳","toneTags":"低声,克制","voiceDesign":"青年男性，清亮偏薄。"}
            ```
        """.trimIndent()
        val p = RoleRegistryCodec.parseLlm(raw, "林安")
        assertNotNull(p)
        assertEquals("林安", p!!.name)
        assertEquals("male", p.gender)
        assertEquals("青年男性，清亮偏薄。", p.design)
    }

    @Test
    fun parseLlm_missing_design_falls_back_to_name_and_null_on_garbage() {
        val p = RoleRegistryCodec.parseLlm("""{"name":"","gender":"女","personality":"活泼"}""", "苏岑")
        assertNotNull(p)
        assertEquals("苏岑", p!!.name)
        assertEquals("female", p.gender)
        assertEquals("", p.design)
        assertNull(RoleRegistryCodec.parseLlm("模型胡言乱语，没有 JSON", "苏岑"))
    }

    @Test
    fun parseLlm_clamps_overlong_design() {
        val long = "甲".repeat(900)
        val p = RoleRegistryCodec.parseLlm("""{"name":"林安","voiceDesign":"$long"}""", "林安")
        assertNotNull(p)
        assertEquals(com.kermond.ebook2tts.core.OnlineRolePolicy.MAX_DESIGN_CHARS, p!!.design.length)
        assertFalse(p.design.contains("\n"))
    }
}
