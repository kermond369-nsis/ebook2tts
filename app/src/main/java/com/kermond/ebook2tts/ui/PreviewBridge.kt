package com.kermond.ebook2tts.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/** 预览桥：绑定本应用 TTS 引擎试听 */
object PreviewBridge {
    private val held = AtomicReference<TextToSpeech?>(null)

    fun preview(ctx: Context, voiceId: String, text: String) {
        com.kermond.ebook2tts.engine.ConfigStore.setNarratorVoice(voiceId)
        com.kermond.ebook2tts.engine.ConfigStore.notifyReload(ctx, "preview_voice")
        val old = held.getAndSet(null)
        runCatching { old?.shutdown() }
        var engine: TextToSpeech? = null
        engine = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.SIMPLIFIED_CHINESE
                engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview")
            }
        }
        held.set(engine)
    }

    fun stop() {
        held.getAndSet(null)?.let { runCatching { it.stop(); it.shutdown() } }
    }
}
