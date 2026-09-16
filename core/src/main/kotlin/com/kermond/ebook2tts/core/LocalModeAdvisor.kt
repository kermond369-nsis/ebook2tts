package com.kermond.ebook2tts.core

/**
 * 本地模式适用性判定（P7 / RQ-515 · RQ-516 / IM-543）。
 *
 * 纯函数：由设备上报的 SoC 标识与核信息判定"选本地模式时是否该弹性能警告"，供 UI 使用。
 *
 * 设计原则（不得违反）：
 * 1. **不按具体型号硬编码豁免**：玄戒（XRING）族按**族名模式**匹配（RQ-515 的例外项）。
 *    原因：O1/O3 的 `ro.soc.model` 实际字符串尚未实测确认（见台账 E6 记录），硬编码等于编造事实。
 * 2. **信息不足时不误伤**：无法判定 ⇒ **不告警**（fail-open），并在 reason 留痕，便于后续用真实数据收紧。
 * 3. **只用可解释信号**（SoC 族名 + 核数），不引入"跑分白名单"式黑箱。
 *
 * 阈值（甲方 2026-09-16 口径）：低于 **骁龙 8 Gen 1 及以上**、低于 **天玑 9300 及以上**、或同等性能以下 ⇒ 告警。
 */
object LocalModeAdvisor {

    /** 判定结果 */
    data class Verdict(val warn: Boolean, val reason: String)

    /** 核数下限：低于该值一律视为低配（与 SoC 型号无关的硬门槛） */
    const val MIN_CORES = 4

    /** 天玑（Dimensity）达阈值的最小代际编号（甲方口径：9300 及以上） */
    const val DIMENSITY_MIN_GEN = 9300

    /**
     * 玄戒（XRING）族模式：命中即**不告警**（RQ-515 例外）。
     * 甲方 2026-09-17 明确：名字串可以用已知的 `O1`，查不到的按族名容忍。
     * ⇒ 采用"族名 + 已知型号串"双保险：族名（xring/玄戒）覆盖未发布的后续型号，
     *    已知串（O1）保证即使平台只回型号号也能命中。
     */
    /**
     * 玄戒（XRING）豁免的**前置厂商校验**（依 agy 2026-09-17 复核意见收紧）：
     * 仅当厂商为小米系（Xiaomi / POCO / Redmi）**且**型号串命中族名/已知串时才豁免，
     * 避免"任何含 O1 的串"被误豁免（如 `SM8450-O1`、`POCO1`）。
     */
    private val XIAOMI_VENDORS = Regex("(?i)xring|xiaomi|redmi|poco|玄戒")

    /** 族名/已知型号串（**仅在厂商校验通过后才参与判定**） */
    private val XRING_FAMILY = Regex("(?i)(xring|玄戒)|(^|\\s|-)O[13]($|\\s|-)")

    /** 已知玄戒型号串（甲方口径：至少写 O1；O3 待实测确认后回填） */
    val XRING_KNOWN_MODELS = listOf("O1", "XRING O1")

    /**
     * 骁龙（Snapdragon）**达到阈值**的模式：8 Gen 1+ 家族，或以 SM8450 及之后的平台编号。
     * 说明：SM8450 = 骁龙 8 Gen 1；SM8475 = 8+ Gen 1；SM8550 = 8 Gen 2；SM8650 = 8 Gen 3；SM8750 = 8 Elite。
     */
    private val SNAPDRAGON_OK = Regex(
        "(?i)(snapdragon\\s*8\\s*gen\\s*[1-9]|8\\s*gen\\s*[1-9]|"
            + "sm8[4-9][0-9]{2}|sm9[0-9]{3}|"
            + "xring)"
    )

    /** 骁龙**未达阈值**的家族特征：可明确判低配的（8 Gen 之前的中低端/老旗舰） */
    private val SNAPDRAGON_OLD = Regex("(?i)(snapdragon|sdm|msm|sm[0-9]{4})")

    /**
     * 天玑（Dimensity）**达到阈值**：9300 及以上（含 9400/9500 等），或 MT6983 之后的编号。
     */
    private val DIMENSITY_OK = Regex("(?i)(dimensity\\s*(9[3-9][0-9]{2}|[0-9]{5})|mt69[89][0-9])")

    /** 天玑/联科发的其它编号（未达阈值前先按"未知"处理，避免误判新芯片） */
    private val MEDIATEK_ANY = Regex("(?i)(dimensity|mediatek|mt[0-9]{4})")

    /**
     * 判定入口。
     *
     * @param socModel  `ro.soc.model`（Android 12+）或等价标识；空表示取不到
     * @param manufacturer `ro.soc.manufacturer`（如 Qualcomm / MediaTek / Xiaomi）
     * @param cores     核数（`availableProcessors`）
     */
    fun judge(socModel: String, manufacturer: String = "", cores: Int = 0): Verdict {
        val id = "$manufacturer $socModel".trim()
        if (id.isBlank()) return Verdict(false, "unknown_soc")

        if (XIAOMI_VENDORS.containsMatchIn("$manufacturer $socModel") && XRING_FAMILY.containsMatchIn(socModel)) {
            return Verdict(false, "xring_exempt")
        }

        if (cores in 1 until MIN_CORES) return Verdict(true, "cores_below_$MIN_CORES")

        if (SNAPDRAGON_OK.containsMatchIn(id)) return Verdict(false, "snapdragon_8gen1_or_newer")
        if (DIMENSITY_OK.containsMatchIn(id)) return Verdict(false, "dimensity_9300_or_newer")

        // 明确的老骁龙 ⇒ 低配
        if (SNAPDRAGON_OLD.containsMatchIn(id) && !DIMENSITY_OK.containsMatchIn(id)) {
            return Verdict(true, "snapdragon_before_8gen1")
        }

        // 联发科：解析代际数字 —— ≥9300 视为达阈值（已在上方命中）；有数字但更低 ⇒ 低配；
        // 无数字（新型号标识） ⇒ fail-open 不误伤，留痕待收紧
        if (MEDIATEK_ANY.containsMatchIn(id)) {
            val gen = Regex("[0-9]{4}").findAll(id).map { it.value.toIntOrNull() ?: 0 }.maxOrNull() ?: 0
            return when {
                gen >= DIMENSITY_MIN_GEN -> Verdict(false, "dimensity_ok_$gen")
                gen > 0 -> Verdict(true, "dimensity_below_$DIMENSITY_MIN_GEN")
                else -> Verdict(false, "mediatek_unknown_generation")
            }
        }

        // 其它厂商（Exynos / Tensor / Kirin / 展锐…）：信息不足 ⇒ 不告警
        return Verdict(false, "vendor_unknown_fail_open")
    }

    /** 面向用户的警告文案（RQ-515 指定） */
    const val WARNING_TEXT = "性能不足，可能延迟极大"
}
