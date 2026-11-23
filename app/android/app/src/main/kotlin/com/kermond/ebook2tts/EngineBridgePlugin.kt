package com.kermond.ebook2tts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.kermond.ebook2tts.core.OnlineSettings
import com.kermond.ebook2tts.core.TtsProviderKind
import com.kermond.ebook2tts.engine.ConfigStore
import com.kermond.ebook2tts.engine.OnlineBackendHolder
import com.kermond.ebook2tts.engine.OnlineRequest
import com.kermond.ebook2tts.engine.OnlineSelector
import com.kermond.ebook2tts.engine.PreviewService
import com.kermond.ebook2tts.engine.RoleRegistry
import com.kermond.ebook2tts.engine.SystemTtsDiscovery
import com.tencent.mmkv.MMKV
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * App ↔ 引擎统一桥接（基于标准 MethodChannel + EventChannel，零代码生成）。
 */
class EngineBridgePlugin : FlutterPlugin, MethodChannel.MethodCallHandler {

    private lateinit var context: Context
    private var methodChannel: MethodChannel? = null
    private var eventChannel: EventChannel? = null

    @Volatile
    private var eventSink: EventChannel.EventSink? = null

    private var statusReceiver: BroadcastReceiver? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "bridge-io").apply { isDaemon = true }
    }

    /** 桥接观测到的事件（诊断页展示，新的在下，最多 200 条） */
    private val ring = ArrayDeque<String>()

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        runCatching { MMKV.initialize(context) }

        methodChannel = MethodChannel(binding.binaryMessenger, METHOD_CHANNEL).also {
            it.setMethodCallHandler(this)
        }
        eventChannel = EventChannel(binding.binaryMessenger, EVENT_CHANNEL).also {
            it.setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    eventSink = events
                }

                override fun onCancel(arguments: Any?) {
                    eventSink = null
                }
            })
        }
        registerStatusReceiver()
        log("bridge attached")
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
        eventChannel?.setStreamHandler(null)
        eventChannel = null
        eventSink = null
        runCatching { statusReceiver?.let { context.unregisterReceiver(it) } }
        statusReceiver = null
        ioExecutor.shutdownNow()
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getStatus" -> result.success(buildStatusMap())
            "getConfig" -> result.success(buildConfigMap())
            "updateConfig" -> {
                handleUpdateConfig(call)
                result.success(true)
            }
            "validateOnline" -> handleValidateOnline(call, result)
            "listSystemTtsEngines" -> ioExecutor.execute {
                val list = SystemTtsDiscovery.listInstalledEngines(context, preferredPackage = "")
                    .map { e ->
                        mapOf(
                            "packageName" to e.packageName,
                            "label" to e.label,
                            "isSystemApp" to e.isSystemApp,
                        )
                    }
                mainHandler.post { result.success(list) }
            }
            "preview" -> {
                val text = call.argument<String>("text").orEmpty()
                val voiceId = call.argument<String>("voiceId")
                PreviewService.preview(context, text, voiceId)
                emit(
                    "status",
                    JSONObject().put("phase", "preview_dispatched").put("previewing", true).toString(),
                )
                result.success(null)
            }
            "stopPreview" -> {
                PreviewService.stop(context)
                emit(
                    "status",
                    JSONObject().put("phase", "preview_stopped").put("previewing", false).toString(),
                )
                result.success(null)
            }
            "recentLogs" -> result.success(synchronized(ring) { ring.toList() })
            "clearPerfCounters" -> {
                ConfigStore.resetPerfCounters()
                ConfigStore.clearFallback()
                log("perf counters cleared")
                emit("status", JSONObject().put("phase", "perf_cleared").toString())
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    private fun buildStatusMap(): Map<String, Any> {
        val providerKind = ConfigStore.onlineProviderKind()
        val onlineOk = OnlineSelector.isOnlineConfigured(
            onlineEnabled = ConfigStore.onlineEnabled(),
            apiKey = ConfigStore.onlineApiKey(),
            keyKind = ConfigStore.onlineKeyKind(),
            tokenPlanAccepted = ConfigStore.onlineTokenPlanAccepted(),
            providerKind = providerKind,
            baseUrl = ConfigStore.onlineBaseUrl(),
        )
        val online = when {
            !ConfigStore.onlineEnabled() -> "off"
            !onlineOk && providerKind == TtsProviderKind.MIMO && ConfigStore.onlineApiKey().isBlank() -> "noKey"
            !onlineOk -> "error"
            ConfigStore.onlineLastError().isNotBlank() -> "error"
            else -> "ready"
        }
        val (fallbackPkg, fallbackLabel) = SystemTtsDiscovery.resolvePreferredLabel(context)
        return mapOf(
            "online" to online,
            "providerKind" to providerKind.id,
            "onlineLastError" to ConfigStore.onlineLastError(),
            "fallbackEnginePackage" to fallbackPkg,
            "fallbackEngineLabel" to fallbackLabel,
            "hasFallbackEngine" to fallbackPkg.isNotEmpty(),
            "lastFallbackEngine" to ConfigStore.lastFallbackEngine(),
            "lastFallbackReason" to ConfigStore.lastFallbackReason(),
            "lastFallbackAt" to ConfigStore.lastFallbackAt(),
            "lastPerf" to ConfigStore.lastPerfMap(),
        )
    }

    private fun buildConfigMap(): Map<String, Any> {
        val providerKind = ConfigStore.onlineProviderKind()
        val keyKind = ConfigStore.onlineKeyKind()
        val rawBase = ConfigStore.onlineBaseUrl()
        val resolvedBase = if (providerKind == TtsProviderKind.MIMO) {
            OnlineSettings.resolveBaseUrl(keyKind, rawBase)
        } else {
            rawBase
        }
        return mapOf(
            "speed" to ConfigStore.speedValue().toDouble(),
            "onlineEnabled" to ConfigStore.onlineEnabled(),
            "tokenPlanAccepted" to ConfigStore.onlineTokenPlanAccepted(),
            "keyKind" to keyKind,
            "apiKeyMasked" to mask(ConfigStore.onlineApiKey()),
            "baseUrl" to resolvedBase,
            "allowMobileData" to ConfigStore.netAllowMobileData(),
            "roleVoiceEnabled" to ConfigStore.roleVoiceEnabled(),
            "roleCount" to RoleRegistry.size(),
            "roleRefineCount" to ConfigStore.roleRefineCount(),
            "providerKind" to providerKind.id,
            "model" to ConfigStore.onlineModel(),
            "voice" to ConfigStore.onlineVoice(),
            "responseFormat" to ConfigStore.onlineResponseFormat(),
            "sampleRate" to ConfigStore.onlineSampleRate(),
            "customHeaders" to ConfigStore.onlineCustomHeaders(),
            "customBodyTemplate" to ConfigStore.onlineCustomBodyTemplate(),
            "timeoutMs" to ConfigStore.onlineTimeoutMs(),
            "fallbackTtsEngine" to ConfigStore.fallbackTtsEngine(),
        )
    }

    private fun handleUpdateConfig(call: MethodCall) {
        call.argument<Number>("speed")?.toFloat()?.let { ConfigStore.setSpeedValue(it) }
        call.argument<Boolean>("onlineEnabled")?.let { ConfigStore.setOnlineEnabled(it) }
        call.argument<Boolean>("allowMobileData")?.let { ConfigStore.setNetAllowMobileData(it) }
        call.argument<String>("keyKind")?.let { ConfigStore.setOnlineKeyKind(it) }
        call.argument<String>("apiKey")?.let { ConfigStore.setOnlineApiKey(it.trim()) }
        call.argument<String>("baseUrl")?.let { ConfigStore.setOnlineBaseUrl(it.trim()) }
        call.argument<Boolean>("tokenPlanAccepted")?.let { ConfigStore.setOnlineTokenPlanAccepted(it) }
        call.argument<Boolean>("roleVoiceEnabled")?.let { ConfigStore.setRoleVoiceEnabled(it) }
        call.argument<String>("providerKind")?.let {
            ConfigStore.setOnlineProviderKind(TtsProviderKind.parse(it))
        }
        call.argument<String>("model")?.let { ConfigStore.setOnlineModel(it) }
        call.argument<String>("voice")?.let { ConfigStore.setOnlineVoice(it) }
        call.argument<String>("responseFormat")?.let { ConfigStore.setOnlineResponseFormat(it) }
        call.argument<Number>("sampleRate")?.toInt()?.let { ConfigStore.setOnlineSampleRate(it) }
        call.argument<String>("customHeaders")?.let { ConfigStore.setOnlineCustomHeaders(it) }
        call.argument<String>("customBodyTemplate")?.let { ConfigStore.setOnlineCustomBodyTemplate(it) }
        call.argument<Number>("timeoutMs")?.toInt()?.let { ConfigStore.setOnlineTimeoutMs(it) }
        call.argument<String>("fallbackTtsEngine")?.let { ConfigStore.setFallbackTtsEngine(it) }

        val touchesOnline = listOf(
            "onlineEnabled",
            "keyKind",
            "apiKey",
            "baseUrl",
            "tokenPlanAccepted",
            "providerKind",
            "model",
            "voice",
            "responseFormat",
            "sampleRate",
            "customHeaders",
            "customBodyTemplate",
            "timeoutMs",
        ).any { call.hasArgument(it) }
        if (touchesOnline) {
            ConfigStore.setOnlineLastError("")
        }
        ConfigStore.notifyReload(context, "bridge_config")
        log("config updated")
        emit("status", JSONObject().put("phase", "config").toString())
    }

    private fun handleValidateOnline(call: MethodCall, result: MethodChannel.Result) {
        val providerKind = ConfigStore.onlineProviderKind()
        val keyKindArg = call.argument<String>("keyKind")?.takeIf { it.isNotBlank() }
            ?: ConfigStore.onlineKeyKind()
        val inputKey = call.argument<String>("apiKey")?.trim().orEmpty()
        val key = if (inputKey.isNotEmpty()) inputKey else ConfigStore.onlineApiKey().trim()

        if (providerKind == TtsProviderKind.MIMO && key.isBlank()) {
            val err = "尚未保存密钥：请先填写密钥并点「保存密钥」"
            mainHandler.post { result.success(err) }
            return
        }

        val base = when (providerKind) {
            TtsProviderKind.MIMO -> OnlineSettings.resolveBaseUrl(keyKindArg, ConfigStore.onlineBaseUrl())
            TtsProviderKind.OPENAI_COMPAT -> ConfigStore.onlineBaseUrl().trim()
                .ifEmpty { TtsProviderKind.OPENAI_COMPAT.defaultBaseUrl }
            TtsProviderKind.CUSTOM_HTTP -> ConfigStore.onlineBaseUrl().trim()
        }
        val req = OnlineRequest(
            baseUrl = base,
            apiKey = key,
            model = ConfigStore.onlineModel().trim().ifEmpty { providerKind.defaultModel },
            voice = ConfigStore.onlineVoice().trim().ifEmpty { providerKind.defaultVoice },
            style = ConfigStore.onlineStyle(),
            providerKind = providerKind,
            responseFormat = ConfigStore.onlineResponseFormat(),
            sampleRate = ConfigStore.onlineSampleRate(),
            customHeaders = ConfigStore.onlineCustomHeaders(),
            customBodyTemplate = ConfigStore.onlineCustomBodyTemplate(),
            timeoutMs = ConfigStore.onlineTimeoutMs(),
        )
        ioExecutor.execute {
            val err = runCatching { OnlineBackendHolder.get().validate(req) }
                .getOrElse { it.message?.take(200) ?: "校验异常" }
            ConfigStore.setOnlineLastError(err.orEmpty())
            emit("status", JSONObject().put("phase", "validate").put("ok", err == null).toString())
            mainHandler.post { result.success(err) }
        }
    }

    private fun registerStatusReceiver() {
        if (statusReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val code = intent?.getStringExtra("code").orEmpty()
                val payload = JSONObject().put("phase", "engine_status").put("code", code)
                when {
                    code.startsWith("PREVIEW_START|") -> {
                        val voice = code.removePrefix("PREVIEW_START|")
                        payload.put("previewing", true)
                        if (voice.isNotEmpty()) payload.put("previewVoiceId", voice)
                    }
                    code.startsWith("PREVIEW_STAGE|") -> {
                        payload.put("previewing", true)
                        payload.put("previewStage", code.removePrefix("PREVIEW_STAGE|"))
                    }
                    code == "PREVIEW_DONE" -> payload.put("previewing", false)
                    code.startsWith("PREVIEW_ERROR|") -> {
                        payload.put("previewing", false)
                        payload.put("previewError", code.removePrefix("PREVIEW_ERROR|"))
                    }
                    code.startsWith("SYSTEM_FALLBACK|") -> {
                        val parts = code.split('|', limit = 3)
                        if (parts.size >= 3) {
                            payload.put("fallbackEngine", parts[1])
                            payload.put("fallbackReason", parts[2])
                        }
                    }
                    code == "PERF" -> {
                        val perf = ConfigStore.lastPerfMap()
                        if (perf.isNotEmpty()) {
                            payload.put("perf", JSONObject(perf))
                        }
                    }
                }
                emit("status", payload.toString())
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(ConfigStore.ACTION_ENGINE_STATUS),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            statusReceiver = receiver
        }.onFailure { log("status receiver failed: ${it.javaClass.simpleName}") }
    }

    private fun emit(type: String, dataJson: String) {
        log("$type|$dataJson")
        mainHandler.post {
            runCatching {
                eventSink?.success(mapOf("type" to type, "dataJson" to dataJson))
            }
        }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        synchronized(ring) {
            ring.addLast(line)
            while (ring.size > 200) ring.removeFirst()
        }
    }

    private fun mask(key: String): String {
        if (key.isBlank()) return ""
        val head = key.take(5)
        return "$head••••••••${key.takeLast(4)}"
    }

    private companion object {
        const val TAG = "EngineBridge"
        const val METHOD_CHANNEL = "com.kermond.ebook2tts/engine"
        const val EVENT_CHANNEL = "com.kermond.ebook2tts/engine_events"
    }
}
