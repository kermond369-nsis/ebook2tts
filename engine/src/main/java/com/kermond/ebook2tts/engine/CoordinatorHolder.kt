package com.kermond.ebook2tts.engine

import android.content.Context

/**
 * `:tts_service` 进程内的协调器共享点。
 */
object CoordinatorHolder {

    @Volatile
    private var coordinator: SynthesisCoordinator? = null

    val instance: SynthesisCoordinator?
        get() = coordinator

    @Synchronized
    fun getOrCreate(context: Context): SynthesisCoordinator {
        coordinator?.let { return it }
        val created = SynthesisCoordinator(context.applicationContext)
        coordinator = created
        return created
    }

    fun detach(target: SynthesisCoordinator) {
        if (coordinator === target) coordinator = null
    }
}
