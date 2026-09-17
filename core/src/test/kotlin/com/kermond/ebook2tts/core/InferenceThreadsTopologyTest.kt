package com.kermond.ebook2tts.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ADR-018 拓扑感知线程数分配（P7 / IM-541）。
 *
 * 两个必须同时成立的趋势（跨设备实测）：
 * - **同构核**（全核同频）：线程数增加 ⇒ 更快（IM-522 在 6 核同构设备的实测）；
 * - **异构核**（big.LITTLE）：线程数超过大核数 ⇒ 更慢（E3 在 SDM845：7/4/2 线程 = 108/83.6/69 s）。
 */
class InferenceThreadsTopologyTest {

    // SDM845 实例：4×A75 @2.8 GHz + 4×A55 @1.76 GHz
    private val sdm845 = List(4) { 2_803_200 } + List(4) { 1_766_400 } // 4×A75 + 4×A55

    // 典型 6 核同构设备：全核 2.0 GHz
    private val homogeneous6 = List(6) { 2_000_000 }

    @Test
    fun `异构核 自动线程数取实测最优档 2（甲方 2026-09-17 口径）`() {
        val perf = InferenceThreads.perfCoreCount(sdm845)
        assertEquals(4, perf) // 分簇识别：4 个大核
        // 实测（真机 SDM845）：7 线程 108.0 s、4 线程 83.6 s、**2 线程 69.0 s** ⇒ 异构核自动取 2
        assertEquals(2, InferenceThreads.resolve(cores = 8, stored = 0, perfCores = perf))
        assertEquals(InferenceThreads.AUTO_HETERO, InferenceThreads.resolve(cores = 8, stored = 0, perfCores = perf))
    }

    @Test
    fun `同构核 6 核 保持旧行为 线程数等于核数减一`() {
        val perf = InferenceThreads.perfCoreCount(homogeneous6)
        assertEquals(6, perf) // 全核同频 ⇒ 视作同簇
        assertEquals(5, InferenceThreads.resolve(cores = 6, stored = 0, perfCores = perf))
    }

    @Test
    fun `拓扑未知时退回旧行为 不影响同构核设备既有收益`() {
        assertEquals(7, InferenceThreads.resolve(cores = 8, stored = 0, perfCores = 0))
        assertEquals(InferenceThreads.MAX, InferenceThreads.resolve(cores = 12, stored = 0, perfCores = 0))
    }

    @Test
    fun `用户显式设置始终优先 且可覆盖到 8`() {
        assertEquals(8, InferenceThreads.resolve(cores = 8, stored = 8, perfCores = 4))
        assertEquals(6, InferenceThreads.resolve(cores = 8, stored = 6, perfCores = 4))
        assertEquals(1, InferenceThreads.resolve(cores = 8, stored = 1, perfCores = 4))
        assertEquals(InferenceThreads.MAX, InferenceThreads.resolve(cores = 8, stored = 99, perfCores = 4))
    }

    @Test
    fun `性能核数下限保护 单核拓扑不会产生非法区间`() {
        val perf = InferenceThreads.perfCoreCount(listOf(1_000_000)) // 仅 1 核上报
        assertEquals(1, perf)
        // ceiling 被抬到 MIN，避免 coerceIn(min>max) 抛异常
        assertEquals(InferenceThreads.MIN, InferenceThreads.resolve(cores = 8, stored = 0, perfCores = perf))
    }

    @Test
    fun `频率全为 0 或为空时 视为拓扑未知`() {
        assertEquals(0, InferenceThreads.perfCoreCount(emptyList()))
        assertEquals(0, InferenceThreads.perfCoreCount(listOf(0, 0, 0)))
    }
    @Test
    fun `cpu_capacity 口径同样可分簇（典型 1024-4x 与 400-4x）`() {
        // 现代 big.LITTLE 常见：大核 capacity≈1024，小核≈400
        val caps = List(4) { 1024 } + List(4) { 400 }
        assertEquals(4, InferenceThreads.perfCoreCount(caps))
        assertEquals(InferenceThreads.AUTO_HETERO, InferenceThreads.resolve(8, 0, InferenceThreads.perfCoreCount(caps)))
        // 同构（罕见）：全核同 capacity ⇒ cores-1
        assertEquals(6, InferenceThreads.perfCoreCount(List(6) { 1024 }))
        assertEquals(5, InferenceThreads.resolve(6, 0, 6))
    }
}
