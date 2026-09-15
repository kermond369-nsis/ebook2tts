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
 * 仅承载与系统界面的少量交互（跳转系统「文字转语音」设置页）；
 * 朗读、合成、下载等全部由 :engine 的独立进程 `:tts_service` 完成，主进程零推理。
 */
class MainActivity : FlutterActivity() {

    private companion object {
        const val CHANNEL = "com.kermond.ebook2tts/system"
        /** 本 App 自身的 TTS 引擎包名：诊断页「对照朗读」显式绑定它（走 :tts_service 进程） */
        const val ENGINE_PACKAGE = "com.kermond.ebook2tts"
        const val DEFAULT_SAMPLE = "夜深了，台灯把书桌照成一小块温暖的岛。"
    }

    /** 诊断用：显式绑定本引擎的系统 TTS 客户端（仅用于对照朗读，不写配置、不改旁白） */
    private var diagTts: android.speech.tts.TextToSpeech? = null
    private var pendingSpeak: String? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // App ↔ 引擎桥接（Pigeon 宿主侧，ADR-012）：注册后 Flutter 侧
        // EngineHostApi 调用与 EngineEventApi 事件推送方可生效。
        flutterEngine.plugins.add(EngineBridgePlugin())

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "openSystemTtsSettings" -> result.success(openSystemTtsSettings())
                    // 对照朗读：经系统 TTS 交由本引擎在 :tts_service 进程合成（真机 A/B 用）
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
        // 修复（2026-09-15 真机发现）：此前从不设置语速，App 自家「对照朗读」永远 1.0×，
        // 与设置页文案「语速：影响全部朗读（离线与在线）」不一致，也让在线变速无从验证。
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
                    // 首次创建实例时也要带上滑杆语速（否则第一次「朗读一次」仍按 1.0×）
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

    /**
     * 打开系统「文字转语音」设置页。
     *
     * @return "tts" 已打开文字转语音设置；"settings" 退化为打开系统设置总页；"failed" 均失败
     */
    private fun openSystemTtsSettings(): String {
        val candidates = listOf(
            // AOSP / 多数定制系统：文字转语音输出设置（MuMu 亦适用）
            Intent("com.android.settings.TTS_SETTINGS"),
            // 备选常量写法（部分 ROM）
            Intent("android.settings.TTS_SETTINGS"),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(intent)
                return "tts"
            } catch (_: ActivityNotFoundException) {
                // 尝试下一个
            } catch (_: SecurityException) {
                // 尝试下一个
            }
        }
        // 兜底：打开系统设置总页，由界面引导用户手动进入
        return try {
            startActivity(
                Intent(android.provider.Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "settings"
        } catch (_: Exception) {
            "failed"
        }
    }
}
