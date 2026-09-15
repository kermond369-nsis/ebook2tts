package com.kermond.ebook2tts.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * native 守卫门单测（ADR-008 / 红线 7 / P3 遗留 TASK-02）。
 *
 * 断言三件事：
 * 1. 同一线程可重入（换装临界区外层加锁 + `SherpaBackend` 内层加锁，同线程不得自锁）；
 * 2. 不同线程互斥（native 串行）；
 * 3. **网络式等长任务不持有本门**——即"锁里等网络"这类结构缺陷在实现层不可能再出现：
 *    在线合成路径不使用 NativeGate（见 `SynthesisCoordinator.synthesizeOnlineSegment`）。
 */
class NativeGateTest {

    @Test
    fun reentrant_same_thread() {
        val depth = AtomicInteger(0)
        NativeGate.withLock {
            depth.incrementAndGet()
            NativeGate.withLock { depth.incrementAndGet() }
            assertTrue("持锁者应为当前线程", NativeGate.isHeldByCurrentThread())
        }
        assertEquals(2, depth.get())
        assertFalse("退出后不得仍持锁", NativeGate.isHeldByCurrentThread())
    }

    @Test
    fun mutually_exclusive_across_threads() {
        val inside = AtomicBoolean(false)
        val overlap = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        val holder = Thread {
            NativeGate.withLock {
                inside.set(true)
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                inside.set(false)
            }
        }
        holder.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        val other = Thread {
            NativeGate.withLock {
                if (inside.get()) overlap.set(true)
            }
        }
        other.start()
        // 另一个线程必须被挡在门外
        other.join(150)
        assertTrue("第二个线程不应进入临界区", other.isAlive)

        release.countDown()
        holder.join(2000)
        other.join(2000)
        assertFalse("临界区不得重叠", overlap.get())
    }

    @Test
    fun gate_is_idle_after_use() {
        NativeGate.withLock { }
        assertFalse("空闲时不得持锁", NativeGate.isLocked())
    }
}
