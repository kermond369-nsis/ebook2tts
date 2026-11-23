package com.kermond.ebook2tts

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.kermond.ebook2tts.core.MirrorResolver
import com.kermond.ebook2tts.core.ModelCatalog
import com.kermond.ebook2tts.core.OnlineSettings
import com.kermond.ebook2tts.core.TtsProviderKind
import com.kermond.ebook2tts.engine.ConfigStore
import com.kermond.ebook2tts.engine.HttpDownloader
import com.kermond.ebook2tts.engine.ModelDownloader
import com.kermond.ebook2tts.engine.ModelRegistry
import com.kermond.ebook2tts.engine.OnlineBackendHolder
import com.kermond.ebook2tts.engine.OnlineRequest
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 界面进程入口。
 *
 * 仅承载与系统界面的少量交互（跳转系统「文字转语音」设置页 / 镜像测速 / SAF 本地导入）；
 * 朗读、合成等推理全部由 :engine 的独立进程 `:tts_service` 完成，主进程零推理。
 */
class MainActivity : FlutterActivity() {

    private companion object {
        const val CHANNEL = "com.kermond.ebook2tts/system"
        /** 本 App 自身的 TTS 引擎包名：诊断页「对照朗读」显式绑定它（走 :tts_service 进程） */
        const val ENGINE_PACKAGE = "com.kermond.ebook2tts"
        const val DEFAULT_SAMPLE = "夜深了，台灯把书桌照成一小块温暖的岛。"
        /** 通知权限请求码（P7 / IM-544） */
        const val REQ_NOTIFICATIONS = 1001
        /** SAF 离线导入模型文件请求码（RQ-204） */
        const val REQ_IMPORT_MODEL = 1002
    }

    /** 诊断用：显式绑定本引擎的系统 TTS 客户端（仅用于对照朗读，不写配置、不改旁白） */
    private var diagTts: android.speech.tts.TextToSpeech? = null
    private var pendingSpeak: String? = null
    private var pendingImportModelId: String? = null
    private var pendingImportResult: MethodChannel.Result? = null
    private val ioExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "app-io").apply { isDaemon = true }
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
                    // 对照朗读：经系统 TTS 交由本引擎在 :tts_service 进程合成（真机 A/B 用）
                    "speakSample" -> {
                        speakSample(call.argument<String>("text") ?: DEFAULT_SAMPLE)
                        result.success(null)
                    }
                    // RQ-513：开局朗读路由模式（读：模式 + 是否已选择；写：模式 + 置已选择）
                    "getRouteMode" -> result.success(
                        mapOf(
                            "mode" to ConfigStore.routeMode().id,
                            "chosen" to ConfigStore.routeChosen(),
                        )
                    )
                    "setRouteMode" -> {
                        val id = call.argument<String>("mode").orEmpty()
                        val mode = com.kermond.ebook2tts.core.RouteMode.parse(id)
                        ConfigStore.setRouteMode(mode)
                        ConfigStore.setRouteChosen(true)
                        if (mode == com.kermond.ebook2tts.core.RouteMode.ONLY_LOCAL) {
                            ConfigStore.setOnlineLastError("")
                        } else {
                            // 离开纯本地时若此前仅是本地无模型，不应污染状态展示
                            if (ConfigStore.statusLastError() == "模型未安装" && !mode.allowsLocal) {
                                ConfigStore.setStatusLastError("")
                            }
                        }
                        ConfigStore.notifyReload(this, "route_mode")
                        ConfigStore.notifyStatus(this, "ROUTE_MODE")
                        result.success(mode.id)
                    }
                    "getRuntimeMeta" -> result.success(
                        mapOf(
                            "onlineLastError" to ConfigStore.onlineLastError(),
                            "localLastError" to ConfigStore.statusLastError(),
                            "lastPerf" to ConfigStore.lastPerfMap(),
                            "providerKind" to ConfigStore.onlineProviderKind().id,
                        )
                    )
                    "getMirrorConfig" -> result.success(
                        mapOf(
                            "selected" to ConfigStore.mirrorSelection(),
                            "customUrl" to ConfigStore.mirrorBase(),
                            "presets" to MirrorResolver.PRESETS.map { p ->
                                mapOf("id" to p.id, "label" to p.label, "prefix" to p.prefix)
                            },
                        )
                    )
                    "setMirrorConfig" -> {
                        call.argument<String>("selected")?.let { ConfigStore.setMirrorSelection(it) }
                        call.argument<String>("customUrl")?.let { ConfigStore.setMirrorBase(it) }
                        ModelRegistry.invalidate()
                        result.success("ok")
                    }
                    "getInferenceConfig" -> result.success(
                        mapOf(
                            "storedThreads" to ConfigStore.storedThreads(),
                            "effectiveThreads" to ConfigStore.threads(),
                            "cpuCores" to Runtime.getRuntime().availableProcessors(),
                            "provider" to "cpu",
                        )
                    )
                    "setInferenceConfig" -> {
                        call.argument<Number>("threads")?.toInt()?.let { ConfigStore.setThreads(it) }
                        ConfigStore.notifyReload(this, "inference_config")
                        ConfigStore.notifyStatus(this, "INFERENCE_CONFIG")
                        result.success("ok")
                    }
                    "probeMirrors" -> {
                        val customArg = call.argument<String>("customUrl")?.trim()
                        ioExecutor.execute {
                            val spec = ModelCatalog.byId("matcha-zh-en")
                            val customUrl = customArg ?: ConfigStore.mirrorBase()
                            val entries = mutableListOf<Triple<String, String, String>>()
                            for (p in MirrorResolver.PRESETS) {
                                val url = when (p.id) {
                                    MirrorResolver.MIRROR_AUTO -> null
                                    MirrorResolver.MIRROR_DIRECT -> spec.primaryUrl
                                    MirrorResolver.MIRROR_CUSTOM ->
                                        MirrorResolver.resolveProxyOrBaseUrl(spec, customUrl)
                                    else -> MirrorResolver.resolveProxyOrBaseUrl(spec, p.prefix)
                                }
                                if (url != null) entries += Triple(p.id, p.label, url)
                            }
                            val probeResults = HttpDownloader().probeAll(entries.map { it.third })
                            val payload = entries.mapIndexed { idx, (id, label, _) ->
                                val pr = probeResults.getOrNull(idx)
                                mapOf(
                                    "id" to id,
                                    "label" to label,
                                    "ok" to (pr?.ok == true),
                                    "latencyMs" to (pr?.latencyMs ?: -1L),
                                    "speedKbps" to (pr?.speedKbps ?: -1.0),
                                    "httpCode" to (pr?.httpCode ?: 0),
                                )
                            }
                            runOnUiThread { result.success(payload) }
                        }
                    }
                    "importLocalModel" -> {
                        val modelId = call.argument<String>("modelId")?.trim().orEmpty()
                        if (modelId.isEmpty()) {
                            result.success(mapOf("ok" to false, "cancelled" to false, "message" to "未指定要导入的模型"))
                            return@setMethodCallHandler
                        }
                        if (pendingImportResult != null) {
                            result.success(mapOf("ok" to false, "cancelled" to false, "message" to "已有导入任务正在进行"))
                            return@setMethodCallHandler
                        }
                        pendingImportModelId = modelId
                        pendingImportResult = result
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(
                                Intent.EXTRA_MIME_TYPES,
                                arrayOf(
                                    "application/x-bzip2",
                                    "application/gzip",
                                    "application/zip",
                                    "application/x-tar",
                                    "application/octet-stream",
                                    "*/*",
                                ),
                            )
                        }
                        try {
                            @Suppress("DEPRECATION")
                            startActivityForResult(intent, REQ_IMPORT_MODEL)
                        } catch (t: Throwable) {
                            pendingImportModelId = null
                            pendingImportResult = null
                            result.success(
                                mapOf(
                                    "ok" to false,
                                    "cancelled" to false,
                                    "message" to "无法打开系统文件选择器：${t.message ?: t.javaClass.simpleName}",
                                )
                            )
                        }
                    }
                    "getOnlineProviderConfig" -> {
                        val kind = ConfigStore.onlineProviderKind()
                        result.success(
                            mapOf(
                                "providerKind" to kind.id,
                                "baseUrl" to ConfigStore.onlineBaseUrl(),
                                "model" to ConfigStore.onlineModel(),
                                "voice" to ConfigStore.onlineVoice(),
                                "responseFormat" to ConfigStore.onlineResponseFormat(),
                                "sampleRate" to ConfigStore.onlineSampleRate(),
                                "customHeaders" to ConfigStore.onlineCustomHeaders(),
                                "customBodyTemplate" to ConfigStore.onlineCustomBodyTemplate(),
                            )
                        )
                    }
                    "setOnlineProviderConfig" -> {
                        val kind = TtsProviderKind.parse(call.argument<String>("providerKind"))
                        ConfigStore.setOnlineProviderKind(kind)
                        call.argument<String>("baseUrl")?.let { ConfigStore.setOnlineBaseUrl(it) }
                        call.argument<String>("model")?.let { ConfigStore.setOnlineModel(it) }
                        call.argument<String>("voice")?.let { ConfigStore.setOnlineVoice(it) }
                        call.argument<String>("responseFormat")?.let { ConfigStore.setOnlineResponseFormat(it) }
                        call.argument<Int>("sampleRate")?.let { ConfigStore.setOnlineSampleRate(it) }
                        call.argument<String>("customHeaders")?.let { ConfigStore.setOnlineCustomHeaders(it) }
                        call.argument<String>("customBodyTemplate")?.let { ConfigStore.setOnlineCustomBodyTemplate(it) }
                        ConfigStore.setOnlineLastError("")
                        ConfigStore.notifyReload(this, "system_provider_config")
                        ConfigStore.notifyStatus(this, "PROVIDER_CONFIG")
                        result.success("ok")
                    }
                    "validateOnlineProvider" -> {
                        val overrideKey = call.argument<String>("apiKey")?.trim().orEmpty()
                        val kind = ConfigStore.onlineProviderKind()
                        val key = if (overrideKey.isNotEmpty()) overrideKey else ConfigStore.onlineApiKey().trim()
                        val base = when (kind) {
                            TtsProviderKind.MIMO -> OnlineSettings.resolveBaseUrl(
                                ConfigStore.onlineKeyKind(),
                                ConfigStore.onlineBaseUrl(),
                            )
                            TtsProviderKind.OPENAI_COMPAT -> ConfigStore.onlineBaseUrl().trim()
                                .ifEmpty { TtsProviderKind.OPENAI_COMPAT.defaultBaseUrl }
                            TtsProviderKind.CUSTOM_HTTP -> ConfigStore.onlineBaseUrl().trim()
                        }
                        val req = OnlineRequest(
                            baseUrl = base,
                            apiKey = key,
                            model = ConfigStore.onlineModel().trim().ifEmpty { kind.defaultModel },
                            voice = ConfigStore.onlineVoice().trim().ifEmpty { kind.defaultVoice },
                            style = ConfigStore.onlineStyle(),
                            providerKind = kind,
                            responseFormat = ConfigStore.onlineResponseFormat(),
                            sampleRate = ConfigStore.onlineSampleRate(),
                            customHeaders = ConfigStore.onlineCustomHeaders(),
                            customBodyTemplate = ConfigStore.onlineCustomBodyTemplate(),
                        )
                        ioExecutor.execute {
                            val err = runCatching { OnlineBackendHolder.get().validate(req) }
                                .getOrElse { it.message?.take(200) ?: "校验异常" }
                            ConfigStore.setOnlineLastError(err.orEmpty())
                            ConfigStore.notifyStatus(this, if (err == null) "ONLINE_OK" else "ONLINE_ERR")
                            runOnUiThread { result.success(err) }
                        }
                    }
                    "stopSample" -> {
                        diagTts?.stop()
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_IMPORT_MODEL) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val res = pendingImportResult
        val modelId = pendingImportModelId
        pendingImportResult = null
        pendingImportModelId = null
        if (res == null) return
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null || modelId == null) {
            res.success(mapOf("ok" to false, "cancelled" to true, "message" to "已取消选择文件"))
            return
        }
        val spec = ModelCatalog.byId(modelId)
        val displayName = resolveDisplayName(uri) ?: spec.archiveName
        ConfigStore.notifyStatus(this, "DL_PROGRESS|$modelId|0|1|import|本地文件")
        ioExecutor.execute {
            val completed = AtomicBoolean(false)
            fun reply(ok: Boolean, message: String) {
                if (!completed.compareAndSet(false, true)) return
                if (ok) {
                    ConfigStore.notifyStatus(this@MainActivity, "DL_DONE|$modelId")
                } else {
                    ConfigStore.notifyStatus(this@MainActivity, "DL_CANCELLED|$modelId")
                }
                runOnUiThread {
                    res.success(mapOf("ok" to ok, "cancelled" to false, "modelId" to modelId, "message" to message))
                }
            }
            try {
                val input = contentResolver.openInputStream(uri)
                if (input == null) {
                    reply(false, "无法读取所选文件，请检查权限或重试")
                    return@execute
                }
                input.use { stream ->
                    ModelDownloader(applicationContext).importFromStream(
                        input = stream,
                        archiveName = displayName,
                        spec = spec,
                        progress = object : ModelDownloader.Progress {
                            override fun onProgress(bytes: Long, total: Long, stage: String) {
                                ConfigStore.notifyStatus(
                                    this@MainActivity,
                                    "DL_PROGRESS|$modelId|$bytes|$total|$stage|本地导入",
                                )
                            }

                            override fun onSuccess(modelId: String) {
                                reply(true, "已导入并启用：${spec.label}")
                            }

                            override fun onError(message: String) {
                                reply(false, message)
                            }
                        },
                    )
                }
            } catch (t: Throwable) {
                reply(false, "导入失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.trim()?.takeIf { it.isNotEmpty() } else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

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
        pendingImportResult = null
        pendingImportModelId = null
        diagTts?.shutdown()
        diagTts = null
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    /**
     * 打开系统「文字转语音」设置页。
     *
     * @return "tts" 已打开文字转语音设置；"settings" 退化为打开系统设置总页；"failed" 均失败
     */
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
                // 尝试下一个
            } catch (_: SecurityException) {
                // 尝试下一个
            }
        }
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
