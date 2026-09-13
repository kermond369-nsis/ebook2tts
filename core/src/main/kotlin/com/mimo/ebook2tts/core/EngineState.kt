package com.mimo.ebook2tts.core

/** 引擎状态机状态（AR-§4.1） */
enum class EngineState {
    NO_MODEL,
    INITIALIZING,
    READY,
    SYNTHESIZING,
    RELOADING,
    ERROR,
}
