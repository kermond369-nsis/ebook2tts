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
        /** 通知权限请求码（P7 / IM-544） */
        const val REQ_NOTIFICATIONS = 1001
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
                    // RQ-515 / IM-546：本地模式适用性判定（只读；界面在用户选本地模式时按需弹警告）
                    "localModeVerdict" -> result.success(localModeVerdict())
                    "stopSample" -> {
                        diagTts?.stop()
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    /**
     * 本地模式适用性判定（RQ-515/RQ-516、IM-546）：把 :core 的 [LocalModeAdvisor] 暴露给界面。
     *
     * **只读判定**：不写配置、不改模式；界面在用户选择「仅本地 / 本地优先」时按需弹
     * 「性能不足，可能延迟极大」。阈值与玄戒豁免的判定逻辑全部在 :core（已单测），此处只取 SoC 型号。
     */
    private fun localModeVerdict(): Map<String, Any> {
        val soc = if (android.os.Build.VERSION.SDK_INT >= 31) {
            android.os.Build.SOC_MODEL
        } else {
            val hw = runCatching {
                java.io.File("/proc/cpuinfo").readText().lineSequence()
                    .firstOrNull { it.startsWith("Hardware", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()
            }.getOrNull()
            if (!hw.isNullOrBlank()) hw else android.os.Build.HARDWARE
        }
        val cores = Runtime.getRuntime().availableProcessors()
        val v = com.kermond.ebook2tts.core.LocalModeAdvisor.judge(soc, android.os.Build.MANUFACTURER, cores)
        return mapOf(
            "warn" to v.warn,
            "reason" to v.reason,
            "soc" to soc,
            "manufacturer" to android.os.Build.MANUFACTURER,
            "cores" to cores,
        )
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

    /**
     * P7 / RQ-510 / IM-544：**运行时申请通知权限**（Android 13+）。
     *
     * 背景（真机实测，BUG-P7-003）：引擎声明了 `POST_NOTIFICATIONS` 却**从不申请**，
     * 真机上 `granted=false` ⇒ 下载/合成的前台服务照跑但**一条通知都不显示**，
     * 用户看不到进度与失败原因。
     *
     * 实现约束：不引入新依赖（用平台 API）；仅 Android 13+ 需要；拒绝授权也不影响功能（只少了通知）。
     */
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            }
        }
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
