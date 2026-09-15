package com.kermond.ebook2tts.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** P6 / IM-522：推理线程策略（甲方口径：自适应、上限 8）。 */
class InferenceThreadsTest {

    @Test
    fun adaptive_by_core_count_capped_at_8() {
        assertEquals(InferenceThreads.MIN, InferenceThreads.resolve(cores = 1))
        assertEquals(InferenceThreads.MIN, InferenceThreads.resolve(cores = 2))
        assertEquals(3, InferenceThreads.resolve(cores = 4))
        assertEquals(5, InferenceThreads.resolve(cores = 6))
        assertEquals(7, InferenceThreads.resolve(cores = 8))
        assertEquals(InferenceThreads.MAX, InferenceThreads.resolve(cores = 12))  // 上限 8
        assertEquals(InferenceThreads.MAX, InferenceThreads.resolve(cores = 16))
    }

    @Test
    fun explicit_setting_wins_and_is_clamped_to_1_8() {
        assertEquals(1, InferenceThreads.resolve(cores = 8, stored = 1))
        assertEquals(6, InferenceThreads.resolve(cores = 8, stored = 6))
        assertEquals(InferenceThreads.MAX, InferenceThreads.resolve(cores = 8, stored = 12)) // 夹到 8
        // 0（未设置）走自适应
        assertEquals(7, InferenceThreads.resolve(cores = 8, stored = 0))
    }
}
