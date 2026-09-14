package com.mimo.ebook2tts.core

/**
 * 引擎状态机（AR-§4.1）。纯逻辑，线程安全由调用方保证或使用 [synchronized] 包装。
 */
class EngineStateMachine {

    var state: EngineState = EngineState.INITIALIZING
        private set

    var lastError: String? = null
        private set

    /** L2 后端类变更挂起（接缝换装，ADR-009） */
    @Volatile
    var pendingBackendReload: Boolean = false
        private set

    fun onNoModel(reason: String = "no_model") {
        state = EngineState.NO_MODEL
        lastError = reason
    }

    fun onInitStart() {
        state = EngineState.INITIALIZING
        lastError = null
    }

    fun onInitSuccess() {
        state = EngineState.READY
        lastError = null
    }

    fun onInitFailure(reason: String) {
        state = EngineState.ERROR
        lastError = reason
    }

    /** 收到有效合成请求 */
    fun onSynthesizeStart(): Boolean {
        return when (state) {
            EngineState.READY, EngineState.INITIALIZING -> {
                state = EngineState.SYNTHESIZING
                true
            }
            EngineState.NO_MODEL, EngineState.ERROR -> false
            EngineState.SYNTHESIZING -> {
                // 请求级串行，由上层保证；允许进入
                true
            }
            EngineState.RELOADING -> {
                // 接缝换装进行中：允许进入（就绪由 awaitBackend 有界等待兜住），
                // 避免阅读器在下一次请求上收到 error 造成"跳句"。
                state = EngineState.SYNTHESIZING
                true
            }
        }
    }

    /** 当前请求正常完成（成功或协议合规 error+done） */
    fun onSynthesizeEnd() {
        if (state == EngineState.SYNTHESIZING) {
            state = EngineState.READY
        }
        // 接缝窗口：若有 pending 则进入 RELOADING
        if (pendingBackendReload && state == EngineState.READY) {
            state = EngineState.RELOADING
        }
    }

    /** 请求 L2 后端重载（绝不中止在途请求） */
    fun requestBackendReload() {
        pendingBackendReload = true
        if (state == EngineState.READY) {
            state = EngineState.RELOADING
        }
    }

    fun onReloadSuccess() {
        pendingBackendReload = false
        state = if (lastError == null) EngineState.READY else EngineState.ERROR
    }

    fun onReloadFailure(reason: String) {
        pendingBackendReload = false
        state = EngineState.ERROR
        lastError = reason
    }

    fun canAcceptRequest(): Boolean = when (state) {
        EngineState.READY, EngineState.INITIALIZING, EngineState.SYNTHESIZING -> true
        else -> false
    }
}
