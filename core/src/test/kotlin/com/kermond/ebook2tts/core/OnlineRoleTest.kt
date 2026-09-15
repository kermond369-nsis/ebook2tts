package com.kermond.ebook2tts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 在线角色音色策略单测（RQ-507 / ADR-013；纯逻辑，零 Android 依赖） */
class OnlineRoleTest {

    // ---------------- 描述清洗 ----------------

    @Test
    fun sanitize_flattens_newlines_and_clamps() {
        val long = "女声，" + "很".repeat(500)
        val out = OnlineRolePolicy.sanitizeDesign("  青年男声\n\n低沉，  沙哑  ")
        assertEquals("青年男声 低沉， 沙哑", out)
        assertEquals(OnlineRolePolicy.MAX_DESIGN_CHARS, OnlineRolePolicy.sanitizeDesign(long).length)
    }

    @Test
    fun normalizeGender_handles_chinese_and_case() {
        assertEquals("male", OnlineRolePolicy.normalizeGender("Male"))
        assertEquals("female", OnlineRolePolicy.normalizeGender(" 女 "))
        assertEquals("unknown", OnlineRolePolicy.normalizeGender(""))
    }

    // ---------------- 待精标筛选 ----------------

    @Test
    fun pendingRoles_skips_refined_blank_and_respects_limit() {
        val candidates = listOf("林安", "  ", "林安", "苏岑", "吴伯")
        val pending = OnlineRolePolicy.pendingRoles(
            candidates = candidates,
            refined = setOf("苏岑"),
            limit = 2,
        )
        assertEquals(listOf("林安", "吴伯"), pending)
    }

    @Test
    fun mayRefineNow_enforces_interval() {
        assertTrue(OnlineRolePolicy.mayRefineNow(0L, 1_000L))
        assertFalse(OnlineRolePolicy.mayRefineNow(10_000L, 15_000L))
        assertTrue(OnlineRolePolicy.mayRefineNow(10_000L, 20_000L))
    }

    @Test
    fun isFull_at_limit() {
        assertFalse(OnlineRolePolicy.isFull(OnlineRolePolicy.MAX_ROLES - 1))
        assertTrue(OnlineRolePolicy.isFull(OnlineRolePolicy.MAX_ROLES))
    }

    // ---------------- 逐段出参决策 ----------------

    @Test
    fun plan_uses_design_model_when_role_has_design() {
        val plan = OnlineSegmentPlanner.plan(
            baseModel = "mimo-v2.5-tts",
            baseVoice = "白桦",
            baseStyle = "沉稳",
            roleEnabled = true,
            roleName = "林安",
            design = RoleVoiceDesign(name = "林安", design = "青年男性，清亮"),
        )
        assertTrue(plan.useDesign)
        assertEquals(OnlineSettings.VOICEDESIGN_MODEL, plan.model)
        assertEquals("", plan.voice) // voicedesign 不接受 audio.voice
        assertEquals("青年男性，清亮", plan.style)
        assertEquals("林安", plan.roleDesignName)
        // 红线不变式：含 voicedesign 的模型必走 user 消息分支
        assertTrue(OnlineSettings.isVoiceDesignModel(plan.model))
    }

    @Test
    fun plan_falls_back_to_builtin_voice() {
        val cases = listOf(
            OnlineSegmentPlanner.plan("m", "白桦", "沉稳", true, null, null),
            OnlineSegmentPlanner.plan("m", "白桦", "沉稳", true, "林安", null),
            OnlineSegmentPlanner.plan("m", "白桦", "沉稳", true, "林安", RoleVoiceDesign("林安")),
            OnlineSegmentPlanner.plan("m", "白桦", "沉稳", false, "林安", RoleVoiceDesign("林安", design = "x")),
        )
        for (p in cases) {
            assertFalse(p.useDesign)
            assertEquals("m", p.model)
            assertEquals("白桦", p.voice)
            assertEquals("沉稳", p.style)
            assertNull(p.roleDesignName)
        }
    }

    @Test
    fun plan_design_does_not_leak_global_style() {
        // 走 voicedesign 时，全局风格指令必须被档案描述替换（否则模型把"沉稳"当音色）
        val plan = OnlineSegmentPlanner.plan(
            baseModel = "mimo-v2.5-tts",
            baseVoice = "白桦",
            baseStyle = "全局风格：沉稳",
            roleEnabled = true,
            roleName = "苏岑",
            design = RoleVoiceDesign(name = "苏岑", design = "少女，软糯"),
        )
        assertEquals("少女，软糯", plan.style)
    }

    // ---------------- 提示词 ----------------

    @Test
    fun prompt_forbids_speed_and_carries_role_name() {
        val p = RoleRefinePrompt.user("林安", "林安说：快走！", listOf("苏岑"))
        assertTrue(p.contains("林安"))
        assertTrue(p.contains("voiceDesign"))
        assertTrue(p.contains("不要写语速")) // ADR-014：语速由客户端控制
        assertTrue(p.contains("苏岑"))
        assertTrue(RoleRefinePrompt.system().contains("JSON"))
    }

    @Test
    fun prompt_truncates_excerpt() {
        val huge = "字".repeat(OnlineRolePolicy.EXCERPT_CHARS * 3)
        val p = RoleRefinePrompt.user("林安", huge, emptyList())
        // 片段被截断：整串不应出现在提示里
        assertFalse(p.contains("字".repeat(OnlineRolePolicy.EXCERPT_CHARS + 1)))
    }
}
