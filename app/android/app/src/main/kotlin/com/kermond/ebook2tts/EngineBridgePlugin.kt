package com.kermond.ebook2tts

import android.content.Context
import android.content.Intent
import android.util.Log
import com.kermond.ebook2tts.core.ModelCatalog
import com.kermond.ebook2tts.core.ModelSpec
import com.kermond.ebook2tts.core.OnlineSettings
import com.kermond.ebook2tts.core.VoiceCatalog
import com.kermond.ebook2tts.engine.ConfigStore
import com.kermond.ebook2tts.engine.DownloadService
import com.kermond.ebook2tts.engine.PreviewPlayer
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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * App ↔ 引擎 桥接（Pigeon 宿主侧，ADR-012）。
 *
 * 纪律：
 * - 配置**单写**：全部读写经 `ConfigStore`（MMKV 多进程），App 不得直接触碰引擎存储；
 * - **密钥不回明文**：`config()` 只回脱敏串，密钥不写日志；
 * - 进度推送不入侵引擎：下载中按暂存区 `.part` 实际字节数取样上报（只读文件长度）；
 * - 试听走 `PreviewPlayer`（**不写全局旁白**，IM-114）。
 */
class EngineBridgePlugin : FlutterPlugin, EngineHostApi {

    private lateinit var context: Context
    private var eventApi: EngineEventApi? = null
    private var preview: PreviewPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 桥接观测到的事件（诊断页用，新的在下，最多 200 条）。 */
    private val ring = ArrayDeque<String>()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private var progressJob: Job? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        // 主进程（UI 进程）不经过 EngineApp，需在此处初始化多进程 MMKV（幂等；
        // 引擎进程由 EngineApp.onCreate 初始化，两进程共享同一 MMKV 根目录）。
        runCatching { MMKV.initialize(context) }
        eventApi = EngineEventApi(binding.binaryMessenger)
        preview = PreviewPlayer(context)
        EngineHostApi.setUp(binding.binaryMessenger, this)
        log("bridge attached")
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        EngineHostApi.setUp(binding.binaryMessenger, null)
        progressJob?.cancel()
        preview?.stop()
        preview = null
        eventApi = null
        scope.cancel()
    }

    // ---------------------------------------------------------------- 状态

    override suspend fun status(): EngineStatusDto {
        val state = when (ConfigStore.statusState()) {
            "NO_MODEL" -> "noModel"
            "LOADING", "INIT" -> "loading"
            "READY" -> "ready"
            "RELOADING" -> "reloading"
            "ERROR" -> "error"
            else -> "noModel"
        }
        val modelId = ConfigStore.modelId().takeIf { isInstalled(it) }
        val installed = modelId != null
        val online = when {
            !ConfigStore.onlineEnabled() -> "off"
            ConfigStore.onlineApiKey().isBlank() -> "noKey"
            ConfigStore.statusLastError().isNotBlank() -> "error"
            else -> "ready"
        }
        return EngineStatusDto(
            state = state,
            modelId = modelId,
            sampleRate = if (installed) ConfigStore.statusSampleRate().toLong() else 0L,
            speakers = if (installed) VoiceCatalog.kokoroVoices().size.toLong() else 0L,
            online = online,
        )
    }

    override suspend fun models(): List<ModelDto> = withContext(Dispatchers.IO) {
        val active = ConfigStore.modelId()
        // 清单驱动（远程→缓存→内置兜底）由 ModelCatalog 内置兜底保证；此处只做展示映射
        val custom = ConfigStore.mirrorBase()
        ModelCatalog.ALL.map { spec ->
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
        emit("status", JSONObject().put("modelId", modelId).put("state", "downloading").toString())
        startProgressWatch(modelId)
    }

    override suspend fun cancelDownload() {
        progressJob?.cancel()
        context.stopService(Intent(context, DownloadService::class.java))
        log("download cancel")
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
        emit("status", JSONObject().put("modelId", modelId).put("state", "reloading").toString())
    }

    // ---------------------------------------------------------------- 音色与试听

    override suspend fun voices(): List<VoiceDto> = withContext(Dispatchers.Default) {
        val narrator = ConfigStore.narratorVoice()
        VoiceCatalog.kokoroVoices().map { v ->
            VoiceDto(
                id = v.id,
                name = v.displayName,
                lang = if (v.group.contains("英")) "en" else "zh",
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
        val p = preview ?: return
        p.preview(text, voiceId, ConfigStore.speedValue(), object : PreviewPlayer.Listener {
            override fun onStart(sampleRate: Int) {
                emit("progress", JSONObject().put("phase", "preview_start").put("sr", sampleRate).toString())
            }

            override fun onProgress(percent: Int) {
                emit("progress", JSONObject().put("phase", "preview").put("percent", percent).toString())
            }

            override fun onDone() {
                emit("progress", JSONObject().put("phase", "preview_done").toString())
            }

            override fun onError(code: Int, message: String) {
                log("preview error $code $message")
                emit("error", JSONObject().put("code", code).put("message", message).toString())
            }
        })
    }

    override suspend fun stopPreview() {
        preview?.stop()
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
    ) {
        speed?.let { ConfigStore.setSpeedValue(it.toFloat()) }
        onlineEnabled?.let { ConfigStore.setOnlineEnabled(it) }
        allowMobileData?.let { ConfigStore.setNetAllowMobileData(it) }
        keyKind?.let { ConfigStore.setOnlineKeyKind(it) }
        apiKey?.let { ConfigStore.setOnlineApiKey(it.trim()) }
        baseUrl?.let { ConfigStore.setOnlineBaseUrl(it.trim()) }
        customMirror?.let { ConfigStore.setMirrorBase(it.trim()) }
        tokenPlanAccepted?.let { ConfigStore.setOnlineTokenPlanAccepted(it) }
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

    override suspend fun validateKey(keyKind: String, apiKey: String): String? =
        withContext(Dispatchers.IO) {
            val key = apiKey.trim()
            if (key.isBlank()) return@withContext "密钥为空"
            val base = OnlineSettings.resolveBaseUrl(keyKind, ConfigStore.onlineBaseUrl())
            val req = Request.Builder()
                .url(base.trimEnd('/') + "/models")
                .header("Authorization", "Bearer $key")
                .get()
                .build()
            runCatching {
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) null
                    else {
                        val body = resp.body?.string().orEmpty()
                        // 只回平台方原始错误摘要；不含密钥
                        "HTTP ${resp.code}: " + body.take(200)
                    }
                }
            }.getOrElse { e -> e.message?.take(200) ?: "网络错误" }
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
        return File(dir, ".completed").exists() && File(dir, spec.files.modelName).exists()
    }

    private fun mask(key: String): String {
        if (key.isBlank()) return ""
        val head = key.take(5)
        return "$head••••••••${key.takeLast(4)}"
    }

    /** 下载期按 `.part` 实际长度推流进度（只读文件长度，不触碰引擎内部状态）。 */
    private fun startProgressWatch(modelId: String) {
        progressJob?.cancel()
        val spec = runCatching { ModelCatalog.byId(modelId) }.getOrNull() ?: return
        val staging = File(context.filesDir, "models-staging")
        progressJob = scope.launch {
            while (isActive) {
                val part = staging.listFiles()?.firstOrNull { it.name.endsWith(".part") }
                val received = part?.length() ?: 0L
                val total = spec.downloadBytes
                val done = isInstalled(modelId)
                emit(
                    "progress",
                    JSONObject()
                        .put("modelId", modelId)
                        .put("received", received)
                        .put("total", total)
                        .put("phase", if (done) "done" else if (received > 0) "download" else "probe")
                        .toString(),
                )
                if (done) {
                    emit("model", JSONObject().put("modelId", modelId).put("installed", true).toString())
                    break
                }
                delay(1000)
            }
        }
    }

    companion object {
        private const val TAG = "EngineBridge"
    }
}
