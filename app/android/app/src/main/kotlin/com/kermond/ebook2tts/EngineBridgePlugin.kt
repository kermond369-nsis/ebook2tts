package com.kermond.ebook2tts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.kermond.ebook2tts.core.MirrorResolver
import com.kermond.ebook2tts.core.ModelCatalog
import com.kermond.ebook2tts.core.ModelSpec
import com.kermond.ebook2tts.core.OnlineSettings
import com.kermond.ebook2tts.core.TtsProviderKind
import com.kermond.ebook2tts.core.VoiceCatalog
import com.kermond.ebook2tts.engine.ConfigStore
import com.kermond.ebook2tts.engine.DownloadService
import com.kermond.ebook2tts.engine.ModelRegistry
import com.kermond.ebook2tts.engine.OnlineBackendHolder
import com.kermond.ebook2tts.engine.OnlineRequest
import com.kermond.ebook2tts.engine.OnlineSelector
import com.kermond.ebook2tts.engine.PreviewService
import com.kermond.ebook2tts.engine.RoleRegistry
import com.tencent.mmkv.MMKV
import io.flutter.embedding.engine.plugins.FlutterPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * App ↔ 引擎 桥接（Pigeon 宿主侧，ADR-012）。
 *
 * 纪律：
 * - 配置**单写**：全部读写经 `ConfigStore`（MMKV 多进程），App 不得直接触碰引擎存储；
 * - **密钥不回明文**：`config()` 只回脱敏串，密钥不写日志；
 * - 进度推送不入侵引擎：下载中按暂存区 `.part` 实际字节数取样上报（只读文件长度）；
 * - 试听经 `PreviewService` 交给 `:tts_service` 进程播放（**不写全局旁白**，IM-114 / IM-506b）。
 */
class EngineBridgePlugin : FlutterPlugin, EngineHostApi {

    private lateinit var context: Context
    private var eventApi: EngineEventApi? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 桥接观测到的事件（诊断页用，新的在下，最多 200 条）。 */
    private val ring = ArrayDeque<String>()

    private var progressJob: Job? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        // 主进程（UI 进程）不经过 EngineApp，需在此处初始化多进程 MMKV（幂等；
        // 引擎进程由 EngineApp.onCreate 初始化，两进程共享同一 MMKV 根目录）。
        runCatching { MMKV.initialize(context) }
        eventApi = EngineEventApi(binding.binaryMessenger)
        // 试听不在此进程持有播放器：推理只允许在 :tts_service（见 PreviewService 注释）
        EngineHostApi.setUp(binding.binaryMessenger, this)
        registerStatusReceiver()
        ModelRegistry.refreshAsyncIfNeeded(context)
        log("bridge attached")
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        EngineHostApi.setUp(binding.binaryMessenger, null)
        progressJob?.cancel()
        runCatching { statusReceiver?.let { context.unregisterReceiver(it) } }
        statusReceiver = null
        eventApi = null
        scope.cancel()
    }

    /**
     * 引擎状态广播 → Flutter 事件（IM-510）。
     *
     * 背景：`:tts_service` 在就绪/故障/下载阶段会广播 `ACTION_ENGINE_STATUS`，但界面此前**无人监听**，
     * 导致「引擎已就绪、徽标仍停在旧态」。此处接住广播并转成 `status` 事件驱动界面刷新。
     */
    private var statusReceiver: BroadcastReceiver? = null

    private fun registerStatusReceiver() {
        if (statusReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val code = intent?.getStringExtra("code").orEmpty()
                when {
                    code.startsWith("DL_PROGRESS|") -> {
                        val p = code.split('|', limit = 6)
                        if (p.size >= 5) {
                            emit(
                                "progress",
                                JSONObject()
                                    .put("modelId", p[1])
                                    .put("received", p[2].toLongOrNull() ?: 0L)
                                    .put("total", p[3].toLongOrNull() ?: 0L)
                                    .put("phase", p[4])
                                    .put("sourceLabel", p.getOrNull(5).orEmpty())
                                    .toString(),
                            )
                        }
                        return
                    }
                    code.startsWith("DL_DONE|") -> {
                        val mid = code.removePrefix("DL_DONE|")
                        progressJob?.cancel()
                        emit(
                            "progress",
                            JSONObject()
                                .put("modelId", mid)
                                .put("received", 1L)
                                .put("total", 1L)
                                .put("phase", "done")
                                .toString(),
                        )
                        emit("model", JSONObject().put("modelId", mid).put("installed", true).toString())
                        emit("status", JSONObject().put("phase", "model_ready").put("modelId", mid).put("state", "ready").toString())
                        return
                    }
                    code.startsWith("DL_CANCELLED|") -> {
                        val mid = code.removePrefix("DL_CANCELLED|")
                        progressJob?.cancel()
                        emit(
                            "progress",
                            JSONObject()
                                .put("modelId", mid)
                                .put("received", 0L)
                                .put("total", 0L)
                                .put("phase", "cancelled")
                                .toString(),
                        )
                        return
                    }
                    code.startsWith("DL_ERROR|") -> {
                        val rest = code.removePrefix("DL_ERROR|")
                        val idx = rest.indexOf('|')
                        val mid = if (idx >= 0) rest.substring(0, idx) else ""
                        val errMsg = if (idx >= 0) rest.substring(idx + 1) else rest
                        progressJob?.cancel()
                        emit(
                            "progress",
                            JSONObject()
                                .put("modelId", mid)
                                .put("received", 0L)
                                .put("total", 0L)
                                .put("phase", "error")
                                .put("error", errMsg)
                                .toString(),
                        )
                        return
                    }
                }
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

    // ---------------------------------------------------------------- 状态

    override suspend fun status(): EngineStatusDto {
        val modelId = ConfigStore.modelId().takeIf { isInstalled(it) }
        val installed = modelId != null
        val state = when (ConfigStore.statusState()) {
            "NO_MODEL" -> "noModel"
            "IDLE_RELEASED" -> if (installed) "ready" else "noModel"
            "INITIALIZING", "LOADING", "INIT" -> "loading"
            "READY" -> "ready"
            "RELOADING" -> "reloading"
            "ERROR" -> "error"
            else -> "noModel"
        }
        val providerKind = ConfigStore.onlineProviderKind()
        val onlineOk = OnlineSelector.isOnlineConfigured(
            routeMode = ConfigStore.routeMode(),
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
        return EngineStatusDto(
            state = state,
            modelId = modelId,
            sampleRate = if (installed) ConfigStore.statusSampleRate().toLong() else 0L,
            speakers = if (installed) VoiceCatalog.poolForModel(modelId!!).size.toLong() else 0L,
            online = online,
        )
    }

    override suspend fun models(): List<ModelDto> {
        val active = ConfigStore.modelId()
        val list = runCatching { ModelRegistry.specsLocalOnly(context) }.getOrElse { ModelCatalog.ALL }
        return list.map { spec ->
            ModelDto(
                id = spec.id,
                label = spec.label,
                note = spec.desc,
                downloadBytes = spec.downloadBytes,
                extractedBytes = spec.extractedBytes,
                installed = isInstalled(spec.id),
                active = isInstalled(spec.id) && spec.id == active,
                recommended = spec.recommended,
            )
        }
    }

    // ---------------------------------------------------------------- 下载

    override suspend fun startDownload(modelId: String) {
        if (isInstalled(modelId)) return
        DownloadService.start(context, modelId)
        log("download start $modelId")
        val spec = runCatching { ModelCatalog.byId(modelId) }.getOrNull()
        val initTotal = (spec?.downloadBytes ?: 0L) + (spec?.files?.vocoderBytes ?: 0L)
        val selectedMirror = ConfigStore.mirrorSelection()
        val initPhase = MirrorResolver.initialPhase(selectedMirror)
        val initSource = if (MirrorResolver.isAuto(selectedMirror) || spec == null) {
            ""
        } else {
            MirrorResolver.orderedSources(spec, selectedMirror, ConfigStore.mirrorBase())
                .firstOrNull()
                ?.let { MirrorResolver.hostLabel(it) }
                .orEmpty()
        }
        val initJson = JSONObject()
            .put("modelId", modelId)
            .put("received", 0L)
            .put("total", initTotal)
            .put("phase", initPhase)
        if (initSource.isNotEmpty()) {
            initJson.put("sourceLabel", initSource)
        }
        emit("progress", initJson.toString())
        emit("status", JSONObject().put("modelId", modelId).put("state", "downloading").toString())
        startProgressWatch(modelId)
    }

    override suspend fun cancelDownload() {
        val curId = DownloadService.currentSnapshot()?.modelId.orEmpty()
        progressJob?.cancel()
        DownloadService.cancel(context)
        log("download cancel")
        emit(
            "progress",
            JSONObject()
                .put("modelId", curId)
                .put("received", 0L)
                .put("total", 0L)
                .put("phase", "idle")
                .toString(),
        )
        emit("status", JSONObject().put("state", "cancelled").toString())
    }

    override suspend fun deleteModel(modelId: String) = withContext(Dispatchers.IO) {
        if (modelId == ConfigStore.modelId()) {
            log("delete refused (active): $modelId")
            return@withContext
        }
        val dir = File(modelsDir(), modelId)
        if (dir.exists()) {
            dir.deleteRecursively()
            log("deleted model $modelId")
            emit("model", JSONObject().put("modelId", modelId).put("installed", false).toString())
        }
    }

    override suspend fun setActiveModel(modelId: String) {
        if (!isInstalled(modelId)) return
        ConfigStore.setModelId(modelId)
        ConfigStore.notifyReload(context, "bridge_active_model")
        log("active model -> $modelId")
        emit("model", JSONObject().put("modelId", modelId).put("active", true).toString())
        emit("status", JSONObject().put("modelId", modelId).put("state", "reloading").toString())
    }

    // ---------------------------------------------------------------- 音色与试听

    override suspend fun voices(): List<VoiceDto> = withContext(Dispatchers.Default) {
        val narrator = ConfigStore.narratorVoice()
        val pool = VoiceCatalog.poolForModel(ConfigStore.modelId())
        pool.map { v ->
            VoiceDto(
                id = v.id,
                name = v.displayName,
                lang = if (v.group.contains("英") && !v.group.contains("中")) "en" else "zh",
                isNarrator = v.id == narrator,
            )
        }
    }

    override suspend fun setNarratorVoice(voiceId: String) {
        ConfigStore.setNarratorVoice(voiceId)
        ConfigStore.notifyReload(context, "bridge_narrator")
        log("narrator -> $voiceId")
        emit("status", JSONObject().put("narrator", voiceId).toString())
    }

    override suspend fun preview(text: String, voiceId: String) {
        // 交给 :tts_service 进程内的 PreviewService 播放：界面进程零推理（AR-§1.7），
        // 且试听只播放样音、不写全局旁白（IM-114）。
        PreviewService.preview(context, text, voiceId)
        emit(
            "progress",
            JSONObject().put("phase", "preview_dispatched").put("voice", voiceId).toString()
        )
    }

    override suspend fun stopPreview() {
        PreviewService.stop(context)
        emit("status", JSONObject().put("phase", "preview_stopped").put("previewing", false).toString())
        emit("progress", JSONObject().put("phase", "preview_stopped").toString())
    }

    // ---------------------------------------------------------------- 配置

    override suspend fun config(): ConfigDto {
        val kind = ConfigStore.onlineKeyKind()
        val custom = ConfigStore.onlineBaseUrl()
        val mirror = ConfigStore.mirrorBase()
        return ConfigDto(
            speed = ConfigStore.speedValue().toDouble(),
            onlineEnabled = ConfigStore.onlineEnabled(),
            tokenPlanAccepted = ConfigStore.onlineTokenPlanAccepted(),
            keyKind = kind,
            apiKeyMasked = mask(ConfigStore.onlineApiKey()),
            baseUrl = OnlineSettings.resolveBaseUrl(kind, custom),
            allowMobileData = ConfigStore.netAllowMobileData(),
            customMirror = mirror,
            roleVoiceEnabled = ConfigStore.roleVoiceEnabled(),
            roleCount = RoleRegistry.size().toLong(),
            roleRefineCount = ConfigStore.roleRefineCount().toLong(),
        )
    }

    override suspend fun updateConfig(
        speed: Double?,
        onlineEnabled: Boolean?,
        allowMobileData: Boolean?,
        keyKind: String?,
        apiKey: String?,
        baseUrl: String?,
        customMirror: String?,
        tokenPlanAccepted: Boolean?,
        roleVoiceEnabled: Boolean?,
    ) {
        speed?.let { ConfigStore.setSpeedValue(it.toFloat()) }
        onlineEnabled?.let { ConfigStore.setOnlineEnabled(it) }
        allowMobileData?.let { ConfigStore.setNetAllowMobileData(it) }
        keyKind?.let { ConfigStore.setOnlineKeyKind(it) }
        apiKey?.let { ConfigStore.setOnlineApiKey(it.trim()) }
        baseUrl?.let { ConfigStore.setOnlineBaseUrl(it.trim()) }
        customMirror?.let {
            ConfigStore.setMirrorBase(it.trim())
            ModelRegistry.invalidate()
        }
        tokenPlanAccepted?.let { ConfigStore.setOnlineTokenPlanAccepted(it) }
        roleVoiceEnabled?.let { ConfigStore.setRoleVoiceEnabled(it) }
        if (onlineEnabled != null || keyKind != null || apiKey != null ||
            baseUrl != null || tokenPlanAccepted != null
        ) {
            ConfigStore.setOnlineLastError("")
        }
        ConfigStore.notifyReload(context, "bridge_config")
        log("config updated")
        emit("status", JSONObject().put("phase", "config").toString())
    }

    override suspend fun recentLogs(): List<String> = synchronized(ring) { ring.toList() }

    override suspend fun clearPerfCounters() {
        ConfigStore.resetPerfCounters()
        log("perf counters cleared")
        emit("status", JSONObject().put("phase", "perf_cleared").toString())
    }

    override suspend fun validateKey(keyKind: String, apiKey: String): String? {
        val err = withContext(Dispatchers.IO) {
            val providerKind = ConfigStore.onlineProviderKind()
            val input = apiKey.trim()
            val key = if (input.isNotEmpty()) input else ConfigStore.onlineApiKey().trim()
            if (providerKind == TtsProviderKind.MIMO && key.isBlank()) {
                return@withContext "尚未保存密钥：请先填写密钥并点「保存密钥」"
            }
            val base = when (providerKind) {
                TtsProviderKind.MIMO -> OnlineSettings.resolveBaseUrl(keyKind, ConfigStore.onlineBaseUrl())
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
            )
            OnlineBackendHolder.get().validate(req)
        }
        ConfigStore.setOnlineLastError(err.orEmpty())
        emit("status", JSONObject().put("phase", "validate").put("ok", err == null).toString())
        return err
    }

    // ---------------------------------------------------------------- 内部

    private fun emit(type: String, dataJson: String) {
        log("$type|$dataJson")
        val api = eventApi ?: return
        // 生成的 EngineEventApi.onEvent 为 suspend：统一投递到主线程 scope；
        // 推送失败只丢弃该事件，绝不影响引擎调用本身。
        scope.launch {
            runCatching { api.onEvent(EngineEventDto(type = type, dataJson = dataJson)) }
        }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        synchronized(ring) {
            ring.addLast(line)
            while (ring.size > 200) ring.removeFirst()
        }
    }

    private fun modelsDir(): File = File(context.filesDir, "models")

    private fun isInstalled(modelId: String): Boolean {
        val spec: ModelSpec = runCatching { ModelCatalog.byId(modelId) }.getOrNull() ?: return false
        val dir = File(modelsDir(), spec.id)
        if (!File(dir, ".completed").exists() || !File(dir, spec.files.modelName).exists()) return false
        if (spec.files.vocoder.isNotEmpty()) {
            val voc = File(dir, spec.files.vocoder)
            if (!voc.exists() || voc.length() <= 0L) return false
        }
        return true
    }

    private fun mask(key: String): String {
        if (key.isBlank()) return ""
        val head = key.take(5)
        return "$head••••••••${key.takeLast(4)}"
    }

    /** 下载期：优先同步 DownloadService 精确阶段/源/进度，退化按 `.part` 实际长度保底推流。 */
    private fun startProgressWatch(modelId: String) {
        progressJob?.cancel()
        val spec = runCatching { ModelCatalog.byId(modelId) }.getOrNull() ?: return
        val staging = File(context.filesDir, "models-staging")
        val mainPart = File(staging, "${spec.archiveName}.part")
        val vocPart = if (spec.files.vocoder.isNotEmpty()) {
            File(staging, "${spec.id}-${spec.files.vocoder}.part")
        } else {
            null
        }
        val total = spec.downloadBytes + spec.files.vocoderBytes
        progressJob = scope.launch {
            while (isActive) {
                if (isInstalled(modelId)) {
                    emit(
                        "progress",
                        JSONObject()
                            .put("modelId", modelId)
                            .put("received", total)
                            .put("total", total)
                            .put("phase", "done")
                            .toString(),
                    )
                    emit("model", JSONObject().put("modelId", modelId).put("installed", true).toString())
                    break
                }
                val snap = DownloadService.currentSnapshot()
                if (snap != null && snap.modelId == modelId) {
                    emit(
                        "progress",
                        JSONObject()
                            .put("modelId", modelId)
                            .put("received", snap.received)
                            .put("total", if (snap.total > 0L) snap.total else total)
                            .put("phase", snap.phase)
                            .put("sourceLabel", snap.sourceLabel)
                            .toString(),
                    )
                } else {
                    val mainBytes = if (mainPart.exists()) mainPart.length() else 0L
                    val vocBytes = if (vocPart != null && vocPart.exists()) vocPart.length() else 0L
                    val received = (mainBytes + vocBytes).coerceAtMost(total)
                    val selectedMirror = ConfigStore.mirrorSelection()
                    val manualSource = if (MirrorResolver.isAuto(selectedMirror)) {
                        ""
                    } else {
                        MirrorResolver.orderedSources(spec, selectedMirror, ConfigStore.mirrorBase())
                            .firstOrNull()
                            ?.let { MirrorResolver.hostLabel(it) }
                            .orEmpty()
                    }
                    val phase = when {
                        vocBytes > 0L -> "download_vocoder"
                        mainBytes > 0L -> "download"
                        else -> MirrorResolver.initialPhase(selectedMirror)
                    }
                    val fallbackJson = JSONObject()
                        .put("modelId", modelId)
                        .put("received", received)
                        .put("total", total)
                        .put("phase", phase)
                    if (manualSource.isNotEmpty()) {
                        fallbackJson.put("sourceLabel", manualSource)
                    }
                    emit("progress", fallbackJson.toString())
                }
                delay(1500)
            }
        }
    }

    companion object {
        private const val TAG = "EngineBridge"
    }
}
