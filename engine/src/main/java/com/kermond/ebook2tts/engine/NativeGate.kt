package com.kermond.ebook2tts.engine

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * native 守卫门（ADR-008「单一互斥」的**唯一实现点**）。
 *
 * 设计取舍（P3 遗留 TASK-02 的整改）：
 * - 需要串行的只有 **native（sherpa）的加载 / 换装 / 合成 / 释放**；
 * - 锁**下沉到 native 调用点**（`SherpaBackend` 内部），意味着"谁碰 native 谁进锁"——
 *   上层（合成协调器、试听播放器）不再各自包锁，也就不可能出现"锁里等网络"这种结构性缺陷
 *   （红线 7：合成线程严禁 IO / 长等待；在线网络请求**绝不允许**持锁）；
 * - 试听（`PreviewPlayer`，独立线程、独立 backend 实例）与系统合成共用**同一把锁**，
 *   因此换装/释放不会被试听穿透（ADR-008 原本的意图，此前未真正落实）。
 *
 * 可重入：`SherpaBackend` 内部加锁后，协调器在**换装临界区**（release+load 原子对）外层再加锁
 * 仍是安全的（同一线程重入），从而保持"换装对合成原子"这一性质。
 */
object NativeGate {

    val lock = ReentrantLock()

    fun <T> withLock(block: () -> T): T = lock.withLock { block() }

    /** 诊断用：当前是否有线程持锁 */
    fun isLocked(): Boolean = lock.isLocked

    /** 诊断用：持锁者是否为当前线程 */
    fun isHeldByCurrentThread(): Boolean = lock.isHeldByCurrentThread
}
