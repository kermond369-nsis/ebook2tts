package com.kermond.ebook2tts

import android.content.ActivityNotFoundException
import android.content.Intent
import com.kermond.ebook2tts.engine.ConfigStore
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * 界面进程入口。
 *
 * 仅承载系统「文字转语音」设置页跳转与系统 TTS 通道对照朗读；
 * 全部合成与系统 TTS 兜底均由 `:engine` 的 `:tts_service` 进程完成。
 */
class MainActivity : FlutterActivity() {

    private companion object {
        const val CHANNEL = "com.kermond.ebook2tts/system"
        const val ENGINE_PACKAGE = "com.kermond.ebook2tts"
        const val DEFAULT_SAMPLE = "夜深了，台灯把书桌照成一小块温暖的岛。"
    }

    private var diagTts: android.speech.tts.TextToSpeech? = null
    private var pendingSpeak: String? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        flutterEngine.plugins.add(EngineBridgePlugin())

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "openSystemTtsSettings" -> result.success(openSystemTtsSettings())
                    "speakSample" -> {
                        speakSample(call.argument<String>("text") ?: DEFAULT_SAMPLE)
                        result.success(null)
                    }
                    "stopSample" -> {
                        diagTts?.stop()
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun speakSample(text: String) {
        runCatching { diagTts?.setSpeechRate(ConfigStore.speedValue()) }
        val existing = diagTts
        if (existing != null) {
            existing.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "diag-sample")
            return
        }
        pendingSpeak = text
        diagTts = android.speech.tts.TextToSpeech(
            applicationContext,
            android.speech.tts.TextToSpeech.OnInitListener { status ->
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    diagTts?.language = java.util.Locale.SIMPLIFIED_CHINESE
                    runCatching { diagTts?.setSpeechRate(ConfigStore.speedValue()) }
                    pendingSpeak?.let {
                        diagTts?.speak(it, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "diag-sample")
                        pendingSpeak = null
                    }
                }
            },
            ENGINE_PACKAGE,
        )
    }

    override fun onDestroy() {
        diagTts?.shutdown()
        diagTts = null
        super.onDestroy()
    }

    private fun openSystemTtsSettings(): String {
        val candidates = listOf(
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent("android.settings.TTS_SETTINGS"),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(intent)
                return "tts"
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return try {
            startActivity(
                Intent(android.provider.Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            "settings"
        } catch (_: Exception) {
            "failed"
        }
    }
}
