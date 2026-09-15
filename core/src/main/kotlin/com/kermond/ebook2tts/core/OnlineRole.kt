package com.kermond.ebook2tts.core

/**
 * 在线角色音色档案（RQ-507 / ADR-013）。
 *
 * 语义（甲方 2026-09-15 批复）：
 * - **只要求"多种可区分"** —— 不绑定角色与固定音色、**不要求跨段/跨章一致（允许偏差）**；
 * - `design` 是给 `mimo-v2.5-tts-voicedesign` 的**中文音色描述**（该模型的 user 消息即音色）；
 * - 档案**只影响后续段**（接缝换装语义），不追溯已播段。
 *
 * 分层纪律（与 `ModelManifest` 一致）：本类型为**纯数据 + 纯逻辑**，零 Android 依赖；
 * 其 JSON 编解码在 `:engine`（Android 提供 org.json）。
 */
data class RoleVoiceDesign(
    val name: String,
    val gender: String = "unknown",
    val ageHint: String = "",
    val personality: String = "",
    val speechStyle: String = "",
    val toneTags: String = "",
    /** voicedesign 音色描述；空白 = 该角色回落内置音色 */
    val design: String = "",
) {
    val hasDesign: Boolean get() = design.isNotBlank()
}

/**
 * 在线角色音色策略（纯逻辑）。
 *
 * 纪律：
 * - 音色描述**限长**（写入 MMKV 与请求体都要小）；
 * - 单次精标调用**限量**（避免一次拉爆文本用量与并发）；
 * - 精标调用**限频**（同一角色的在途/失败不重试风暴）。
 */
object OnlineRolePolicy {

    /** 音色描述长度上限（字符）。超出截断——描述只是"给模型的话"，不需要长文 */
    const val MAX_DESIGN_CHARS = 220

    /** 单次精标调用最多处理几个角色 */
    const val MAX_ROLES_PER_CALL = 2

    /** 两次精标调用最小间隔（毫秒），避免朗读中高频调用 */
    const val MIN_REFINE_INTERVAL_MS = 10_000L

    /** 精标输入片段保留长度（字符） */
    const val EXCERPT_CHARS = 3_000

    /** 缓存落盘最小间隔（毫秒）：合成侧只做内存累积，落盘走后台且限频 */
    const val BUFFER_FLUSH_INTERVAL_MS = 3_000L

    /** 角色库上限（防无限增长） */
    const val MAX_ROLES = 200

    /** 描述清洗：压平换行/多空格并截断到上限 */
    fun sanitizeDesign(raw: String): String {
        val t = raw
            .replace(Regex("[\\r\\n]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        return if (t.length <= MAX_DESIGN_CHARS) t else t.take(MAX_DESIGN_CHARS)
    }

    /** 性别归一（模型可能回中文/大小写） */
    fun normalizeGender(raw: String): String = when (raw.trim().lowercase()) {
        "male", "男", "男性" -> "male"
        "female", "女", "女性" -> "female"
        else -> "unknown"
    }

    /**
     * 待精标角色筛选（纯函数）：
     * @param candidates 已出现过的说话人名（保留首次出现顺序）
     * @param refined 已有档案的角色名
     * @param limit 本次最多返回几个
     */
    fun pendingRoles(
        candidates: Collection<String>,
        refined: Set<String>,
        limit: Int = MAX_ROLES_PER_CALL,
    ): List<String> = candidates
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filter { it !in refined }
        .distinct()
        .take(limit.coerceAtLeast(0))
        .toList()

    /** 限频判定：`now - lastAt >= MIN_REFINE_INTERVAL_MS`（`lastAt<=0` 视为首次，放行） */
    fun mayRefineNow(lastRefineAtMs: Long, nowMs: Long): Boolean =
        lastRefineAtMs <= 0L || nowMs - lastRefineAtMs >= MIN_REFINE_INTERVAL_MS

    /** 角色库是否已达上限 */
    fun isFull(size: Int): Boolean = size >= MAX_ROLES
}

/**
 * 一次在线段请求的出参（纯逻辑，可 JVM 单测）。
 * `roleDesignName != null` 表示该段走 voicedesign（模型与 user 消息都被替换）。
 */
data class OnlineSegmentPlan(
    val model: String,
    val voice: String,
    val style: String,
    val roleDesignName: String? = null,
) {
    val useDesign: Boolean get() = roleDesignName != null
}

/**
 * 逐段出参决策（RQ-507 / ADR-013）：
 * 有档案 → `mimo-v2.5-tts-voicedesign`（user 消息＝音色描述，**不带** `audio.voice`）；
 * 否则 → 内置音色（原行为：model + voice + 可选风格指令）。
 */
object OnlineSegmentPlanner {

    fun plan(
        baseModel: String,
        baseVoice: String,
        baseStyle: String,
        roleEnabled: Boolean,
        roleName: String?,
        design: RoleVoiceDesign?,
    ): OnlineSegmentPlan {
        if (!roleEnabled || roleName.isNullOrBlank() || design == null || !design.hasDesign) {
            return OnlineSegmentPlan(model = baseModel, voice = baseVoice, style = baseStyle)
        }
        return OnlineSegmentPlan(
            model = OnlineSettings.VOICEDESIGN_MODEL,
            voice = "",
            style = design.design,
            roleDesignName = roleName,
        )
    }
}

/**
 * 精标提示词（纯逻辑；**不许**发明协议字段，仅按 voicedesign 官方语义描述音色）。
 *
 * 关键约束：**不要让模型写语速** —— 语速在本方案里由客户端时域伸缩控制（ADR-014），
 * 写进描述会与实际语速打架。
 */
object RoleRefinePrompt {

    fun system(): String = "你是小说配音导演。只输出 JSON，不要 markdown，不要解释。"

    fun user(roleName: String, excerpt: String, knownNames: List<String>): String = buildString {
        append("请根据小说片段，为角色「")
        append(roleName)
        append("」提炼配音档案。\n")
        if (knownNames.isNotEmpty()) {
            append("已建档角色（尽量让新角色音色可区分）：")
            append(knownNames.take(12).joinToString("、"))
            append("\n")
        }
        append("只输出一个 JSON 对象，字段：\n")
        append("{\"name\":\"")
        append(roleName)
        append("\",\"gender\":\"male|female|unknown\",\"ageHint\":\"年龄气质\",")
        append("\"personality\":\"性格 2-8 字\",\"speechStyle\":\"说话风格 2-8 字\",")
        append("\"toneTags\":\"语气标签，逗号分隔\",")
        append("\"voiceDesign\":\"给语音合成用的中文音色描述 1-2 句\"}\n")
        append("voiceDesign 要求：写清性别与年龄段、音色质感、默认情绪与说话习惯；\n")
        append("**不要写语速快慢**、不要写混响/均衡等后期词、不要写标点与括号注释。\n")
        if (excerpt.isNotBlank()) {
            append("小说片段：\n")
            append(excerpt.take(OnlineRolePolicy.EXCERPT_CHARS))
        }
    }
}
