package com.kermond.ebook2tts.core

/**
 * 推理线程数策略（P6 / IM-522）。
 *
 * 甲方 2026-09-15 口径：「做自适应的最多 8 并发」——此前写死 2 线程，核多的设备白白闲置。
 *
 * 规则：
 * - 用户显式设置优先（>0），夹取到 [1, [MAX]]；
 * - 未设置 ⇒ `clamp(核数 - 1, 2, MAX)`：留 1 核给音频写入/UI 线程，其余给推理。
 *
 * 为什么上限是 8：ONNX Runtime / sherpa-onnx 的 intra-op 线程池在 ~8 线程后收益被内存带宽吃掉，
 * 且与音频回放线程抢核会造成推流抖动（ttfb 抖动、drop 增加）。
 */
object InferenceThreads {

    const val MAX = 8
    const val MIN = 2

    /**
     * 解析生效线程数（P7 / ADR-018：按核簇拓扑分配）。
     *
     * - 用户显式设置（stored > 0）优先，夹取到 [1, [MAX]]；
     * - 未设置 ⇒ `clamp(cores-1, MIN, 性能核数)`：**上限取性能核（大核）数**，避免在 big.LITTLE 上
     *   让 ORT 起过多线程去争抢少数大核（实机 SDM845：7 线程 108 s→ 4 线程 83.6 s → 2 线程 69 s）；
     * - `perfCores <= 0`（拓扑未知）⇒ 退回旧行为（上限 MAX），保持与 IM-522 同构核设备上的既有收益。
     */
    fun resolve(cores: Int, stored: Int = 0, perfCores: Int = 0): Int {
        if (stored > 0) return stored.coerceIn(1, MAX)
        val ceiling = if (perfCores > 0) maxOf(MIN, minOf(perfCores, MAX)) else MAX
        return (cores - 1).coerceIn(MIN, ceiling)
    }

    /**
     * 由各核最大频率（kHz）分簇求**性能核数**（纯函数，便于单测；判定在 Android 侧读 `cpufreq` 后传入）。
     *
     * 规则：以最高频为基准，统计 `maxFreq >= 最高频 × PERF_RATIO` 的核数（默认 0.75）。
     * 同构核设备 ⇒ 返回总核数（等价于旧行为）；big.LITTLE ⇒ 只返回大核数。
     */
    fun perfCoreCount(maxFreqKHz: List<Int>, ratio: Double = PERF_RATIO): Int {
        val valid = maxFreqKHz.filter { it > 0 }
        if (valid.isEmpty()) return 0
        val top = valid.max()
        return valid.count { it >= top * ratio }
    }

    /** 性能核判定阈值：≥ 最高频的 75% 视为同一性能簇（覆盖 A75/A55 这类典型 2.8/1.76 GHz 分簇） */
    const val PERF_RATIO = 0.75
}
