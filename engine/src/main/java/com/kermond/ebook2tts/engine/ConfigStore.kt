package com.kermond.ebook2tts.engine

import android.content.Context
import android.content.Intent
import android.util.Log
import com.kermond.ebook2tts.core.ModelCatalog
import com.kermond.ebook2tts.core.OnlineSettings
import com.kermond.ebook2tts.core.VoiceCatalog
import com.tencent.mmkv.MMKV
import com.kermond.ebook2tts.core.InferenceThreads

/**
 * 配置存储：MMKV 多进程（ADR-002）。
 * 主进程写 → 广播 ENGINE_RELOAD → 引擎热读。
 */
object ConfigStore {
    const val ACTION_ENGINE_RELOAD = "com.kermond.ebook2tts.ENGINE_RELOAD"
    const val ACTION_ENGINE_STATUS = "com.kermond.ebook2tts.ENGINE_STATUS_CHANGED"

    private const val MMKV_ID = "ebook2tts_cfg"
    private const val TAG = "ConfigStore"

    private fun kv(): MMKV = MMKV.mmkvWithID(MMKV_ID, MMKV.MULTI_PROCESS_MODE)

    fun modelId(): String = kv().decodeString("model.id", ModelCatalog.DEFAULT_ID)!!
    fun setModelId(id: String) {
        kv().encode("model.id", id)
    }

    fun narratorVoice(): String =
        kv().decodeString("voice.narrator", VoiceCatalog.DEFAULT_NARRATOR_ID)!!

    fun setNarratorVoice(id: String) {
        kv().encode("voice.narrator", id)
    }

    fun roleMode(): String = kv().decodeString("role.mode", "smart")!!
    fun setRoleMode(mode: String) {
        kv().encode("role.mode", mode)
    }

    fun speedMode(): String = kv().decodeString("speed.mode", "readerFirst")!!
    fun setSpeedMode(mode: String) {
        kv().encode("speed.mode", mode)
    }

    /**
     * 推理线程数（P6 / IM-522，甲方 2026-09-15 口径："做自适应的最多 8 并发"）。
     *
     * 背景：此前**写死 2**，8 核设备上白白闲置 6 核，本地合成实测 RTF≈1.6（甲方 P0 报障③"合成时间太长"）。
     * 取值策略：
     * - 用户显式设置（engine.threads > 0）优先，夹取到 [1, 8]；
     * - 未设置时按核数自适应 = clamp(核数 - 1, 2, 8)（留 1 核给音频写入/UI，其余全给推理，上限 8）。
     *
     * 说明（甲方问"各家怎么办"）：ONNX Runtime / sherpa-onnx 走的是 intra-op 线程池，
     * 典型做法是 num_threads ≈ 物理大核数（常取 4~8）；超过 ~8 后收益被内存带宽吃掉，
     * 且会与音频回放线程抢核导致推流抖动，故本实现设 8 为硬上限。
     */
    /**
     * 性能核（大核）数（P7 / ADR-018）：读各核 `cpufreq/cpuinfo_max_freq` 分簇判定。
     * 只读一次并缓存（CPU 拓扑不会变）；失败或读不到 ⇒ 0（调用方退回旧行为）。
     */
    private val perfCores: Int by lazy {
        runCatching {
            val n = Runtime.getRuntime().availableProcessors()
            val freqs = (0 until n).mapNotNull { i ->
                java.io.File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                    .takeIf { it.canRead() }
                    ?.readText()?.trim()?.toIntOrNull()
            }
            InferenceThreads.perfCoreCount(freqs)
        }.getOrDefault(0)
    }

    fun threads(): Int = InferenceThreads.resolve(
        cores = Runtime.getRuntime().availableProcessors(),
        stored = kv().decodeInt("engine.threads", 0),
        perfCores = perfCores,
    )

    fun setThreads(n: Int) {
        kv().encode("engine.threads", n.coerceIn(1, MAX_THREADS))
    }

    // ---- 在线朗读（MiMo；P3 §4 协议键）----
    // 纪律：在线关闭时引擎路径只读 online.enabled 一个键，其余键零触达（本地路径零额外开销）。

    /** 在线朗读总开关（默认关；关闭时零网络） */
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

    fun onlineVoice(): String = kv().decodeString("online.voice", OnlineSettings.DEFAULT_VOICE)!!
    fun setOnlineVoice(voice: String) {
        kv().encode("online.voice", voice.trim())
    }

    fun onlineModel(): String = kv().decodeString("online.model", OnlineSettings.DEFAULT_MODEL)!!
    fun setOnlineModel(model: String) {
        kv().encode("online.model", model.trim())
    }

    /** 风格指令（作为 user 消息；空 = 不发送。定制音色模型下作为音色描述） */
    fun onlineStyle(): String = kv().decodeString("online.style", "")!!
    fun setOnlineStyle(style: String) {
        kv().encode("online.style", style.trim())
    }

    /** Token Plan 风险确认已通过（未确认时禁止使用 tp- 密钥） */
    fun onlineTokenPlanAccepted(): Boolean = kv().decodeBool("online.tokenPlanAccepted", false)
    fun setOnlineTokenPlanAccepted(v: Boolean) {
        kv().encode("online.tokenPlanAccepted", v)
    }

    // ---- 在线角色音色 / 角色精标（RQ-507 / ADR-013）----
    // 纪律：角色表在**请求开始时一次性快照**（合成路径零 IO，红线 7）；
    //      精标（文本 LLM）只允许在**主进程**执行（红线 4），:tts_service 侧只写"待精标候选/文本缓存"。

    /** 「AI 角色音色」开关（默认开；仅在线开启时有意义） */
    fun roleVoiceEnabled(): Boolean = kv().decodeBool("online.roleEnabled", true)
    fun setRoleVoiceEnabled(v: Boolean) {
        kv().encode("online.roleEnabled", v)
    }

    /** 精标用文本模型（默认 `mimo-v2.5`；TTS 模型不用于文本调用） */
    fun onlineLlmModel(): String =
        kv().decodeString("online.llmModel", OnlineSettings.DEFAULT_LLM_MODEL)!!

    fun setOnlineLlmModel(m: String) {
        kv().encode("online.llmModel", m.trim().ifEmpty { OnlineSettings.DEFAULT_LLM_MODEL })
    }

    /** 角色库（name → 档案）整体 JSON；空串 = 空库 */
    fun roleProfilesJson(): String = kv().decodeString("online.roles", "")!!
    fun setRoleProfilesJson(json: String) {
        kv().encode("online.roles", json)
    }

    /** 待精标候选角色名（由 :tts_service 在朗读中收集） */
    fun roleCandidatesJson(): String = kv().decodeString("online.roleCandidates", "")!!
    fun setRoleCandidatesJson(json: String) {
        kv().encode("online.roleCandidates", json)
    }

    /** 精标输入：最近的朗读文本缓存（滚动窗口，供文本 LLM 提炼角色） */
    fun roleExcerpt(): String = kv().decodeString("online.roleExcerpt", "")!!
    fun setRoleExcerpt(text: String) {
        kv().encode("online.roleExcerpt", text)
    }

    /** 上次精标调用时间（毫秒；0 = 从未）——限频用 */
    fun roleLastRefineAt(): Long = kv().decodeLong("online.roleLastRefineAt", 0L)
    fun setRoleLastRefineAt(at: Long) {
        kv().encode("online.roleLastRefineAt", at)
    }

    /** 精标成功次数（诊断页展示；文本用量粗估） */
    fun roleRefineCount(): Int = kv().decodeInt("online.roleRefineCount", 0)
    fun setRoleRefineCount(n: Int) {
        kv().encode("online.roleRefineCount", n)
    }

    fun bumpRoleRefineCount() {
        setRoleRefineCount(roleRefineCount() + 1)
    }

    /** 语速倍数（用户值，0.5–2.0；阅读器优先模式下作为上限约束） */
    fun speedValue(): Float = kv().decodeFloat("speed.value", 1.0f)
    fun setSpeedValue(v: Float) {
        kv().encode("speed.value", v.coerceIn(0.5f, 2.0f))
    }

    /** 引擎实测采样率（模型加载成功后写入；0 = 未知）——**不猜测**，未加载成功保持 0 */
    fun statusSampleRate(): Int = kv().decodeInt("status.sampleRate", 0)
    fun setStatusSampleRate(sr: Int) {
        kv().encode("status.sampleRate", sr)
    }

    /** 性能计数清零（诊断页用；丢块等计数复位） */
    fun resetPerfCounters() {
        kv().encode("status.dropUnit", 0)
    }

    /** 允许使用数据流量（默认关：蜂窝下禁止在线与模型下载，见 core.NetPolicy） */
    fun netAllowMobileData(): Boolean = kv().decodeBool("net.allowMobileData", false)
    fun setNetAllowMobileData(v: Boolean) {
        kv().encode("net.allowMobileData", v)
    }

    fun statusState(): String = kv().decodeString("status.state", "NO_MODEL")!!
    fun setStatusState(s: String) {
        kv().encode("status.state", s)
    }

    fun statusModelId(): String = kv().decodeString("status.modelId", "")!!
    fun setStatusModelId(id: String) {
        kv().encode("status.modelId", id)
    }

    fun statusLastError(): String = kv().decodeString("status.lastError", "")!!
    fun setStatusLastError(msg: String) {
        kv().encode("status.lastError", msg)
    }

    fun dropUnitCount(): Int = kv().decodeInt("status.dropUnit", 0)
    fun bumpDropUnit() {
        kv().encode("status.dropUnit", dropUnitCount() + 1)
    }

    fun migrationDone(): Boolean = kv().decodeBool("migration.v2.done", false)
    fun setMigrationDone(v: Boolean) {
        kv().encode("migration.v2.done", v)
    }

    /** 自定义镜像基地址（IM-201）；空 = 用官方源 */
    fun mirrorBase(): String = kv().decodeString("mirror.base", "")!!
    fun setMirrorBase(url: String) {
        kv().encode("mirror.base", url.trim())
    }

    /** 清单上次远程拉取成功时间（毫秒；0 = 从未成功） */
    fun manifestFetchedAt(): Long = kv().decodeLong("manifest.fetchedAt", 0L)
    fun setManifestFetchedAt(at: Long) {
        kv().encode("manifest.fetchedAt", at)
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

    /** 推理线程硬上限（甲方口径：最多 8 并发） */
    const val MAX_THREADS = 8
}
