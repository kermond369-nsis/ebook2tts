package com.kermond.ebook2tts.engine

import android.content.Context
import android.content.Intent
import android.util.Log
import com.kermond.ebook2tts.core.OnlineSettings
import com.kermond.ebook2tts.core.SystemTtsSelector
import com.kermond.ebook2tts.core.TtsProviderKind
import com.tencent.mmkv.MMKV

/**
 * 配置存储：MMKV 多进程（主进程写 → 广播 ENGINE_RELOAD → `:tts_service` 热读）。
 */
object ConfigStore {
    const val ACTION_ENGINE_RELOAD = "com.kermond.ebook2tts.ENGINE_RELOAD"
    const val ACTION_ENGINE_STATUS = "com.kermond.ebook2tts.ENGINE_STATUS_CHANGED"

    private const val MMKV_ID = "ebook2tts_cfg"
    private const val TAG = "ConfigStore"

    private fun kv(): MMKV = MMKV.mmkvWithID(MMKV_ID, MMKV.MULTI_PROCESS_MODE)

    fun roleMode(): String = kv().decodeString("role.mode", "smart")!!
    fun setRoleMode(mode: String) {
        kv().encode("role.mode", mode)
    }

    fun speedMode(): String = kv().decodeString("speed.mode", "readerFirst")!!
    fun setSpeedMode(mode: String) {
        kv().encode("speed.mode", mode)
    }

    // ---- 在线朗读配置 ----

    /** 在线朗读总开关（默认关；关闭时走系统自带 TTS，零网络请求） */
    fun onlineEnabled(): Boolean = kv().decodeBool("online.enabled", false)
    fun setOnlineEnabled(v: Boolean) {
        kv().encode("online.enabled", v)
    }

    /** 密钥类型：billing=按量计费（sk-）/ plan=Token Plan（tp-） */
    fun onlineKeyKind(): String = kv().decodeString("online.keyKind", OnlineSettings.KIND_BILLING)!!
    fun setOnlineKeyKind(kind: String) {
        kv().encode("online.keyKind", OnlineSettings.normalizeKind(kind))
    }

    fun onlineApiKey(): String = kv().decodeString("online.apiKey", "")!!
    fun setOnlineApiKey(key: String) {
        kv().encode("online.apiKey", key.trim())
    }

    /** 自定义 Base URL（空 = 按密钥类型自动解析默认域名） */
    fun onlineBaseUrl(): String = kv().decodeString("online.baseUrl", "")!!
    fun setOnlineBaseUrl(url: String) {
        kv().encode("online.baseUrl", url.trim())
    }

    /** 在线最近错误摘要 */
    fun onlineLastError(): String = kv().decodeString("online.lastError", "")!!
    fun setOnlineLastError(msg: String) {
        kv().encode("online.lastError", msg.trim())
    }

    fun onlineVoice(): String = kv().decodeString("online.voice", OnlineSettings.DEFAULT_VOICE)!!
    fun setOnlineVoice(voice: String) {
        kv().encode("online.voice", voice.trim())
    }

    fun onlineModel(): String = kv().decodeString("online.model", OnlineSettings.DEFAULT_MODEL)!!
    fun setOnlineModel(model: String) {
        kv().encode("online.model", model.trim())
    }

    /** 风格指令（作为 user 消息；空 = 不发送） */
    fun onlineStyle(): String = kv().decodeString("online.style", "")!!
    fun setOnlineStyle(style: String) {
        kv().encode("online.style", style.trim())
    }

    /** Token Plan 风险确认已通过 */
    fun onlineTokenPlanAccepted(): Boolean = kv().decodeBool("online.tokenPlanAccepted", false)
    fun setOnlineTokenPlanAccepted(v: Boolean) {
        kv().encode("online.tokenPlanAccepted", v)
    }

    /** 通用在线引擎类型（mimo / openai / custom_http） */
    fun onlineProviderKind(): TtsProviderKind =
        TtsProviderKind.parse(kv().decodeString("online.providerKind", TtsProviderKind.DEFAULT.id))

    fun setOnlineProviderKind(kind: TtsProviderKind) {
        kv().encode("online.providerKind", kind.id)
    }

    /** 期待响应音频格式（pcm / wav / mp3 / opus / auto） */
    fun onlineResponseFormat(): String = kv().decodeString("online.responseFormat", "pcm")!!
    fun setOnlineResponseFormat(fmt: String) {
        kv().encode("online.responseFormat", fmt.trim().lowercase().ifEmpty { "pcm" })
    }

    /** PCM 无头流采样率（默认 24000Hz） */
    fun onlineSampleRate(): Int = kv().decodeInt("online.sampleRate", OnlineSettings.SAMPLE_RATE)
    fun setOnlineSampleRate(sr: Int) {
        kv().encode("online.sampleRate", if (sr > 0) sr else OnlineSettings.SAMPLE_RATE)
    }

    /** 自定义 HTTP 请求头（每行 Key: Value） */
    fun onlineCustomHeaders(): String = kv().decodeString("online.customHeaders", "")!!
    fun setOnlineCustomHeaders(h: String) {
        kv().encode("online.customHeaders", h.trim())
    }

    /** 自定义 HTTP JSON 请求体模板 */
    fun onlineCustomBodyTemplate(): String = kv().decodeString("online.customBodyTemplate", "")!!
    fun setOnlineCustomBodyTemplate(tpl: String) {
        kv().encode("online.customBodyTemplate", tpl.trim())
    }

    /** 弱网首包超时阈值（毫秒，默认 6000ms） */
    fun onlineTimeoutMs(): Int =
        OnlineSettings.clampTimeoutMs(kv().decodeInt("online.timeoutMs", OnlineSettings.DEFAULT_ONLINE_TIMEOUT_MS))

    fun setOnlineTimeoutMs(ms: Int) {
        kv().encode("online.timeoutMs", OnlineSettings.clampTimeoutMs(ms))
    }

    // ---- 系统自带 TTS 兜底配置与回退状态 ----

    /** 用户指定的兜底系统 TTS 包名（空串 = 自动选优） */
    fun fallbackTtsEngine(): String =
        kv().decodeString("fallback.ttsEngine", SystemTtsSelector.AUTO_ENGINE)!!

    fun setFallbackTtsEngine(pkg: String) {
        kv().encode("fallback.ttsEngine", pkg.trim())
    }

    fun lastFallbackEngine(): String = kv().decodeString("fallback.lastEngine", "")!!
    fun lastFallbackReason(): String = kv().decodeString("fallback.lastReason", "")!!
    fun lastFallbackAt(): Long = kv().decodeLong("fallback.lastAt", 0L)

    fun recordFallback(engineLabel: String, reason: String) {
        kv().encode("fallback.lastEngine", engineLabel.trim())
        kv().encode("fallback.lastReason", reason.trim())
        kv().encode("fallback.lastAt", System.currentTimeMillis())
    }

    fun clearFallback() {
        kv().encode("fallback.lastEngine", "")
        kv().encode("fallback.lastReason", "")
        kv().encode("fallback.lastAt", 0L)
    }

    // ---- 在线角色音色 / 角色精标（仅 MiMo） ----

    /** 「AI 角色音色」开关（默认开） */
    fun roleVoiceEnabled(): Boolean = kv().decodeBool("online.roleEnabled", true)
    fun setRoleVoiceEnabled(v: Boolean) {
        kv().encode("online.roleEnabled", v)
    }

    /** 精标用文本模型（默认 `mimo-v2.6-flash`） */
    fun onlineLlmModel(): String =
        OnlineSettings.resolveLlmModel(kv().decodeString("online.llmModel", OnlineSettings.DEFAULT_LLM_MODEL)!!)

    fun setOnlineLlmModel(m: String) {
        kv().encode("online.llmModel", OnlineSettings.resolveLlmModel(m))
    }

    fun roleProfilesJson(): String = kv().decodeString("online.roles", "")!!
    fun setRoleProfilesJson(json: String) {
        kv().encode("online.roles", json)
    }

    fun roleCandidatesJson(): String = kv().decodeString("online.roleCandidates", "")!!
    fun setRoleCandidatesJson(json: String) {
        kv().encode("online.roleCandidates", json)
    }

    fun roleExcerpt(): String = kv().decodeString("online.roleExcerpt", "")!!
    fun setRoleExcerpt(text: String) {
        kv().encode("online.roleExcerpt", text)
    }

    fun roleLastRefineAt(): Long = kv().decodeLong("online.roleLastRefineAt", 0L)
    fun setRoleLastRefineAt(at: Long) {
        kv().encode("online.roleLastRefineAt", at)
    }

    fun roleRefineCount(): Int = kv().decodeInt("online.roleRefineCount", 0)
    fun setRoleRefineCount(n: Int) {
        kv().encode("online.roleRefineCount", n)
    }

    fun bumpRoleRefineCount() {
        setRoleRefineCount(roleRefineCount() + 1)
    }

    // ---- 语速、网络与性能 ----

    /** 语速倍数（0.5–2.0） */
    fun speedValue(): Float = kv().decodeFloat("speed.value", 1.0f)
    fun setSpeedValue(v: Float) {
        kv().encode("speed.value", v.coerceIn(0.5f, 2.0f))
    }

    /** 允许使用数据流量（默认关：蜂窝下直接回退系统 TTS） */
    fun netAllowMobileData(): Boolean = kv().decodeBool("net.allowMobileData", false)
    fun setNetAllowMobileData(v: Boolean) {
        kv().encode("net.allowMobileData", v)
    }

    fun resetPerfCounters() {
        kv().encode("status.dropUnit", 0)
        kv().encode("perf.at", 0L)
    }

    fun recordLastPerf(firstChunkMs: Long, totalMs: Long, droppedChunks: Int, backend: String) {
        kv().encode("perf.firstChunkMs", firstChunkMs)
        kv().encode("perf.totalMs", totalMs)
        kv().encode("perf.droppedChunks", droppedChunks)
        kv().encode("perf.backend", backend)
        kv().encode("perf.at", System.currentTimeMillis())
    }

    fun lastPerfMap(): Map<String, Any> {
        val at = kv().decodeLong("perf.at", 0L)
        if (at <= 0L) return emptyMap()
        return mapOf(
            "firstChunkMs" to kv().decodeLong("perf.firstChunkMs", -1L),
            "totalMs" to kv().decodeLong("perf.totalMs", 0L),
            "droppedChunks" to kv().decodeInt("perf.droppedChunks", 0),
            "backend" to kv().decodeString("perf.backend", "")!!,
            "perfAt" to at,
        )
    }

    fun dropUnitCount(): Int = kv().decodeInt("status.dropUnit", 0)
    fun bumpDropUnit() {
        kv().encode("status.dropUnit", dropUnitCount() + 1)
    }

    /** v2.0.0 一次性旧版离线模型残留清理标记 */
    fun legacyCleanupDone(): Boolean = kv().decodeBool("cleanup.v2.modelsDone", false)
    fun setLegacyCleanupDone(v: Boolean) {
        kv().encode("cleanup.v2.modelsDone", v)
    }

    fun notifyReload(context: Context, reason: String = "config") {
        val i = Intent(ACTION_ENGINE_RELOAD).setPackage(context.packageName)
        i.putExtra("reason", reason)
        context.sendBroadcast(i)
        Log.i(TAG, "broadcast reload reason=$reason")
    }

    fun notifyStatus(context: Context, code: String = "") {
        val i = Intent(ACTION_ENGINE_STATUS).setPackage(context.packageName)
        i.putExtra("code", code)
        context.sendBroadcast(i)
    }
}
