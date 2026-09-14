package com.kermond.ebook2tts

import android.content.ActivityNotFoundException
import android.content.Intent
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
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // App ↔ 引擎桥接（Pigeon 宿主侧，ADR-012）：注册后 Flutter 侧
        // EngineHostApi 调用与 EngineEventApi 事件推送方可生效。
        flutterEngine.plugins.add(EngineBridgePlugin())

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "openSystemTtsSettings" -> result.success(openSystemTtsSettings())
                    else -> result.notImplemented()
                }
            }
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
