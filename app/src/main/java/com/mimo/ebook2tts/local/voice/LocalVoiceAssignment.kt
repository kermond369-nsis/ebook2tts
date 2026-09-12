package com.mimo.ebook2tts.local.voice

import com.mimo.ebook2tts.local.analysis.SpeakerIds

/** 角色 → 本地音色分配。旁白沉稳男声，角色按性别/顺序轮转。 */
object LocalVoiceAssignment {

    fun assign(
        speakerIds: Collection<String>,
        genderOf: (String) -> String?,
        narratorVoiceId: String,
        pool: List<LocalVoice>,
    ): Map<String, LocalVoice> {
        val cast = linkedMapOf<String, LocalVoice>()
        val narrator = LocalVoice.byId(pool, narratorVoiceId)
        cast[SpeakerIds.NARRATOR] = narrator

        val used = mutableSetOf(narrator.id)
        val female = LocalVoice.femalePool(pool)
        val male = LocalVoice.malePool(pool)
        var fi = 0
        var mi = 0

        for (name in speakerIds) {
            if (name == SpeakerIds.NARRATOR) continue
            val g = genderOf(name) ?: "unknown"
            val pick = when (g) {
                "female" -> {
                    female.firstOrNull { it.id !in used }
                        ?: female[fi % female.size].also { fi++ }
                }
                "male" -> {
                    male.firstOrNull { it.id !in used }
                        ?: male[mi % male.size].also { mi++ }
                }
                else -> {
                    val all = pool.filter { it.id != narrator.id }
                    all.firstOrNull { it.id !in used } ?: all.firstOrNull() ?: narrator
                }
            }
            used += pick.id
            cast[name] = pick
        }
        return cast
    }
}
