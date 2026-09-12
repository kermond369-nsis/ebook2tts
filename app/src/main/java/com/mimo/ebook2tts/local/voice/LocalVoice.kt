package com.mimo.ebook2tts.local.voice

/** 本地模型音色。speakerId 对应 sherpa-onnx OfflineTts 的 sid。 */
data class LocalVoice(
    val id: String,
    val label: String,
    val kind: String, // male | female
    val speakerId: Int,
    val desc: String = "",
) {
    companion object {
        /** Kokoro multi-lang 中文音色（v1.0 ID 45-52） */
        val KOKORO_ZH = listOf(
            LocalVoice("zf_xiaoxiao", "晓晓", "female", 47, "活泼女声"),
            LocalVoice("zf_xiaoyi", "晓伊", "female", 48, "温柔女声"),
            LocalVoice("zf_xiaoni", "晓妮", "female", 46, "知性女声"),
            LocalVoice("zf_xiaobei", "晓北", "female", 45, "清亮女声"),
            LocalVoice("zm_yunyang", "云扬", "male", 52, "沉稳男声（旁白默认）"),
            LocalVoice("zm_yunjian", "云健", "male", 49, "浑厚男声"),
            LocalVoice("zm_yunxi", "云希", "male", 50, "阳光男声"),
            LocalVoice("zm_yunxia", "云夏", "male", 51, "少年男声"),
        )

        val VITS_ZH_LL = listOf(
            LocalVoice("ll_0", "中文女-0", "female", 0),
            LocalVoice("ll_1", "中文女-1", "female", 1),
            LocalVoice("ll_2", "中文男-0", "male", 2),
            LocalVoice("ll_3", "中文男-1", "male", 3),
            LocalVoice("ll_4", "中文女-2", "female", 4),
        )

        fun vitsPool(numSpeakers: Int): List<LocalVoice> {
            val n = numSpeakers.coerceIn(1, 64)
            return (0 until n).map { i ->
                val gender = if (i % 2 == 0) "female" else "male"
                LocalVoice("spk_$i", "音色$i", gender, i)
            }
        }

        fun poolForModel(modelId: String, numSpeakers: Int = 0): List<LocalVoice> = when {
            modelId.startsWith("kokoro") -> KOKORO_ZH
            modelId.contains("zh-ll") -> VITS_ZH_LL
            numSpeakers > 0 -> vitsPool(numSpeakers)
            else -> VITS_ZH_LL
        }

        fun byId(pool: List<LocalVoice>, id: String): LocalVoice =
            pool.firstOrNull { it.id == id } ?: pool.first()

        fun femalePool(pool: List<LocalVoice>) = pool.filter { it.kind == "female" }
        fun malePool(pool: List<LocalVoice>) = pool.filter { it.kind == "male" }
    }
}
