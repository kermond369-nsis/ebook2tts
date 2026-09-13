package com.mimo.ebook2tts.core

/** 角色模式（RQ-106 / DQ-7） */
enum class RoleMode { SMART_MULTI, RESPECT_READER }

/**
 * 多角色分配（RQ-105/113）。
 * 空池/单性别池禁止取模崩溃；孤立对白继承会话窗口说话人。
 */
class RoleAssigner(
    private val pool: List<VoiceInfo>,
    private val mode: RoleMode = RoleMode.SMART_MULTI,
    private val narrator: VoiceInfo = VoiceCatalog.defaultNarrator(pool),
    private val windowMs: Long = 30_000L,
    private val maxKnownSpeakers: Int = 200,
) {

    private val known = LinkedHashMap<String, VoiceInfo>()
    private var lastSpeakerKey: String? = null
    private var lastMs: Long = 0L
    private var dialogueTurn = 0
    private val dialoguePool: List<VoiceInfo> = pool.filter { it.gender != VoiceGender.UNKNOWN }

    fun reset() {
        known.clear()
        lastSpeakerKey = null
        lastMs = 0L
        dialogueTurn = 0
    }

    fun onSilenceReset(nowMs: Long) {
        if (nowMs - lastMs > 240_000L) reset() // 4 分钟静默（3~5 分钟区间）
    }

    fun assign(segment: TextSegment, nowMs: Long = System.currentTimeMillis()): VoiceInfo {
        onSilenceReset(nowMs)
        if (mode == RoleMode.RESPECT_READER) return narrator
        return when (segment.kind) {
            SegmentKind.NARRATION, SegmentKind.TITLE, SegmentKind.EMPTY -> narrator
            SegmentKind.DIALOGUE -> assignDialogue(segment, nowMs)
        }
    }

    private fun assignDialogue(segment: TextSegment, nowMs: Long): VoiceInfo {
        val hint = segment.speakerHint?.trim()
        if (!hint.isNullOrEmpty()) {
            val existing = known[hint]
            if (existing != null) {
                lastSpeakerKey = hint
                lastMs = nowMs
                return existing
            }
            val assigned = nextByGender(hint)
            if (known.size >= maxKnownSpeakers) {
                val oldest = known.keys.firstOrNull()
                if (oldest != null) known.remove(oldest)
            }
            known[hint] = assigned
            lastSpeakerKey = hint
            lastMs = nowMs
            return assigned
        }
        // 孤立对白：继承窗口内说话人，否则轮转
        val last = lastSpeakerKey
        if (last != null && nowMs - lastMs <= windowMs) {
            known[last]?.let {
                lastMs = nowMs
                return it
            }
        }
        val v = rotateDialogue()
        lastSpeakerKey = "__rot_${dialogueTurn - 1}"
        lastMs = nowMs
        return v
    }

    private fun nextByGender(hint: String): VoiceInfo {
        val males = pool.filter { it.gender == VoiceGender.MALE }
        val females = pool.filter { it.gender == VoiceGender.FEMALE }
        val femaleHint = listOf("女", "娘", "姐", "妹", "妈", "她", "小姐", "姑娘", "夫人")
        val isFemale = femaleHint.any { hint.contains(it) }
        return when {
            isFemale && females.isNotEmpty() -> females[(known.size) % females.size]
            !isFemale && males.isNotEmpty() -> males[(known.size) % males.size]
            dialoguePool.isNotEmpty() -> dialoguePool[dialogueTurn % dialoguePool.size]
            pool.isNotEmpty() -> pool[(dialogueTurn) % pool.size]
            else -> narrator
        }
    }

    private fun rotateDialogue(): VoiceInfo {
        val source = if (dialoguePool.isNotEmpty()) dialoguePool else pool.ifEmpty { listOf(narrator) }
        val v = source[dialogueTurn % source.size]
        dialogueTurn++
        return v
    }
}
