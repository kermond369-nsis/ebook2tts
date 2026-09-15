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

    fun resolve(cores: Int, stored: Int = 0): Int {
        if (stored > 0) return stored.coerceIn(1, MAX)
        return (cores - 1).coerceIn(MIN, MAX)
    }
}
