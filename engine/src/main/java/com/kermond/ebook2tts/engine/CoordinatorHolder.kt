package com.kermond.ebook2tts.engine

import android.content.Context

/**
 * `:tts_service` 进程内的协调器共享点（P7 / R1，BUG-P7-015 修复）。
 *
 * 背景：`PreviewPlayer` 原先**每次试听**都自建 `SherpaBackend` 并 `load()` + 用后 `release()`，
 * 与 [SynthesisCoordinator] 自持的实例各自加载同一模型。真机（SDM845）实测：
 * 首次试听首音 12.25 s；连续第二次试听总耗时 80.0 s。
 *
 * 关键点：`:tts_service` 进程可能**先由 `PreviewService` 拉起**（用户在本 App 里点"试听"），
 * 此时 `LocalTextToSpeechService` 尚未创建 ⇒ 若协调器由 TTS 服务独占持有，预览通道拿不到它、
 * 只能回落到"自建实例"的旧路径（实测仍是 12 s/次）。故此处改为**进程级单例**：
 * 谁先需要谁创建（[getOrCreate]），两条通道此后共享同一常驻后端。
 *
 * 约定（务必遵守）：
 * - **借用方（预览通道）不得调用 `release()`**，释放权归协调器（模型切换 / 服务销毁）；
 * - 预热（`initAsync()`）由首个创建者触发一次，避免重复加载。
 */
object CoordinatorHolder {

    /** 预览通道是否正在播放（P7 追加：空闲/内存压力下可安全释放常驻后端的前提） */
    @Volatile
    var previewActive: Boolean = false

    @Volatile
    private var coordinator: SynthesisCoordinator? = null

    /** 兼容旧调用：仅取已有实例（可能为 null）。 */
    val instance: SynthesisCoordinator?
        get() = coordinator

    /**
     * 取得（必要时创建）进程内唯一协调器。
     *
     * 线程安全：`@Synchronized`；创建后立即 `initAsync()` 预热（重复调用不重复预热）。
     */
    @Synchronized
    fun getOrCreate(context: Context): SynthesisCoordinator {
        coordinator?.let { return it }
        val created = SynthesisCoordinator(context.applicationContext).also {
            coordinator = it
            it.initAsync()
        }
        return created
    }

    fun detach(target: SynthesisCoordinator) {
        if (coordinator === target) coordinator = null
    }
}
