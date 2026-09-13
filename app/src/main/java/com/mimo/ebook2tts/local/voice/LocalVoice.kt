package com.mimo.ebook2tts.local.voice

/**
 * 本地模型音色。speakerId 对应 sherpa-onnx OfflineTts 的 sid。
 *
 * Kokoro multi-lang v1.1（103 speakers，int8/fp32 通用）：
 *  - 0–2    英文女声
 *  - 3–57   中文女声
 *  - 58–102 中文男声
 */
data class LocalVoice(
    val id: String,
    val label: String,
    val kind: String, // male | female
    val speakerId: Int,
    val desc: String = "",
) {
    companion object {

        /** 生成 kokoro v1.1 全量音色列表（可点选） */
        fun kokoroV11All(): List<LocalVoice> {
            val list = mutableListOf<LocalVoice>()
            // 英文女声 0-2
            listOf("af_maple", "af_sol", "bf_vale").forEachIndexed { i, n ->
                list += LocalVoice("en_$n", "EN·$n (女)", "female", i)
            }
            // 中文女声 3-57
            for (sid in 3..57) {
                list += LocalVoice("zf_$sid", "中文女 $sid", "female", sid)
            }
            // 中文男声 58-102
            for (sid in 58..102) {
                list += LocalVoice("zm_$sid", "中文男 $sid", "male", sid)
            }
            return list
        }

        /** 旁白默认：中文男声靠前（沉稳） */
        const val DEFAULT_NARRATOR_ID = "zm_058"

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
            modelId.startsWith("kokoro") -> kokoroV11All()
            modelId.contains("zh-ll") -> VITS_ZH_LL
            numSpeakers > 0 -> vitsPool(numSpeakers)
            else -> VITS_ZH_LL
        }

        fun byId(pool: List<LocalVoice>, id: String): LocalVoice {
            pool.firstOrNull { it.id == id }?.let { return it }
            return pool.firstOrNull { it.id == DEFAULT_NARRATOR_ID }
                ?: pool.firstOrNull { it.kind == "male" }
                ?: pool.first()
        }

        fun femalePool(pool: List<LocalVoice>) = pool.filter { it.kind == "female" }
        fun malePool(pool: List<LocalVoice>) = pool.filter { it.kind == "male" }
    }
}
