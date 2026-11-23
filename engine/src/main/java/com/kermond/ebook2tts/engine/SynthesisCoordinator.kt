package com.kermond.ebook2tts.engine

import android.content.Context
import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.util.Log
import com.kermond.ebook2tts.core.EngineState
import com.kermond.ebook2tts.core.RouteMode
import com.kermond.ebook2tts.core.EngineStateMachine
import com.kermond.ebook2tts.core.ModelCatalog
import com.kermond.ebook2tts.core.OnlineSegmentPlan
import com.kermond.ebook2tts.core.OnlineSegmentPlanner
import com.kermond.ebook2tts.core.OnlineSettings
import com.kermond.ebook2tts.core.OnlineVoiceMap
import com.kermond.ebook2tts.core.PcmChunker
import com.kermond.ebook2tts.core.ProcessNames
import com.kermond.ebook2tts.core.RoleAssigner
import com.kermond.ebook2tts.core.RoleMode
import com.kermond.ebook2tts.core.SegmentKind
import com.kermond.ebook2tts.core.SpeedMapper
import com.kermond.ebook2tts.core.StreamingPcmResampler
import com.kermond.ebook2tts.core.TextAnalyzer
import com.kermond.ebook2tts.core.TextClean
import com.kermond.ebook2tts.core.TextSegment
import com.kermond.ebook2tts.core.TtsProviderKind
import com.kermond.ebook2tts.core.VoiceCatalog
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 合成协调器（AR-§4.2/4.3/4.4/4.8，ADR-008/009）。
 *
 * 设计要点：
 * - **真流式**（ADR-003）：native 逐块回调 → 有界队列 → 独立推送线程立即 audioAvailable，
 *   不再"整句拼完才推送"（首字延迟）。
 * - **双后端请求级热切换**：请求开始时选定本地 sherpa 或在线 MiMo（不每句切换）；
 *   在线失败**静默回落本地**（`ONLINE|fallback=local|reason=..`），绝不为内部原因中止在途朗读
 *   （AR-§4.8.3 红线）；在线关闭时本地路径零额外开销（无网络、无 await、无新增线程）。
 * - **锁纪律**（红线 7）：守卫锁只包住 native/在线调用（合成/换装/释放/试听），IPC 推送在推手线程完成。
 * - **微淡化**（红线 6）：仅请求首块头部（fadeHead）与末块尾部（fadeTail）；单块请求用 fadeEdges。
 * - **有界销毁**（ADR-008）：置停止 → tryLock(≤3s) → 锁内释放；超时**不释放**（防 UAF），交进程回收。
 * - **假成功禁令**（红线 3）：非空文本零音频必须 error 可见。
 *
 * 仅在 :tts_service 进程使用（红线 4 硬断言）。
 */
class SynthesisCoordinator(
    private val context: Context,
) {
    private val filesDir: File = context.filesDir
    companion object {
        private const val TAG = "SynthCoord"
        private const val MAX_AUDIO_BYTES = 8192
        private const val QUEUE_CAPACITY = 16
        // P7 / R5（BUG-P7-011）：真机（SDM845）实测冷加载 12.09 s ⇒ 原 1500 ms 预算必然超时被拒。
        // 常态由 initAsync() 预热覆盖，本预算仅作兜底（首请求排队至此，超时才回 error）。
        private const val READY_WAIT_MS = 15_000L
        private const val SHUTDOWN_WAIT_MS = 3000L
        private const val PUSH_JOIN_MS = 60_000L

        /** 陈旧 stop 宽限窗口（见 requestStop 处注释） */
        private const val STOP_GRACE_MS = 150L
        private val END = AudioBlock(ByteArray(0), false) // 毒丸
    }

    private class AudioBlock(val pcm: ByteArray, val stretch: Boolean)

    /**
     * native 串行锁：**唯一实现点是 [NativeGate]**（合成/换装/释放/试听共用同一把锁，ADR-008）。
     * 本字段只是别名——`SherpaBackend` 内部已自行进锁，协调器仅在**换装临界区**外层再加锁，
     * 保证 release+load 对合成原子。**网络路径绝不持锁**（红线 7 / P3 遗留 TASK-02 整改）。
     */
    private val guard = NativeGate.lock

    /** 就绪条件（替代 sleep 轮询，红线 7） */
    private val readyLock = ReentrantLock()
    private val readyCond = readyLock.newCondition()

    private val sm = EngineStateMachine()

    /**
     * 停止语义（AOSP 实证，TextToSpeechService.java:1057-1072）：
     * `onStop()` 只在"存在活跃合成回调"时被调用，且**由发起 stop 的线程同步执行**——
     * 因此阅读器逐句 `QUEUE_FLUSH` 时，框架 flush 上一条的 `onStop()` 会与本条请求的
     * 启动**并发**到达。若照单全收，就会把新请求当"被停止"处理 → **跳句**。
     *
     * 规则：请求启动后的 [STOP_GRACE_MS] 窗口内到达的 stop 视为"冲刷上一条"（忽略）；
     * 窗口之后到达的 stop 才是对本条的真实停止意图。
     */
    private val reqSeq = AtomicInteger(0)

    @Volatile
    private var stopAtMs = 0L

    @Volatile
    private var destroyed = false

    /** 仅调试用（P7/E3）：可调试包从 /data/local/tmp/p7-threads.txt 覆盖线程数；release 永不读取。交付前移除。 */
    private fun resolvedThreads(): Int {
        val debuggable = (context.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) {
            val f = java.io.File("/data/local/tmp/p7-threads.txt")
            if (f.exists()) {
                val n = runCatching { f.readText().trim().toInt() }.getOrNull()
                if (n != null && n in 1..8) { Log.i(TAG, "THREADS|override|n=$n"); return n }
            }
        }
        return ConfigStore.threads()
    }

    private val reloadExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tts-reload").apply { isDaemon = true }
    }
    private val pushExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tts-push").apply { isDaemon = true }
    }
    private val prefetchExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tts-prefetch").apply { isDaemon = true }
    }

    @Volatile
    private var backend: SherpaBackend? = null

    /** 活跃合成请求计数（由 [synthesize] 配对增减，供 [releaseIfIdle] 判断是否真正在用） */
    private val activeSyntheses = AtomicInteger(0)

    /** 常驻后端已被空闲/内存压力释放；下次需用本地后端时触发按需预热 */
    @Volatile
    private var idleReleased = false

    /** 防重入按需重预热标志 */
    private val autoRewarmPending = AtomicBoolean(false)

    /**
     * 当前已加载后端的"硬配置指纹"（P7 / R6b，BUG-P7-014 核心根因）。
     *
     * 仅 modelId 与线程数变化才需要真正 release+load（真机 12 s 代价）；
     * 旁白音色 / 角色模式变化只需重建内存中的 [voicePool]/[roleAssigner]（零 native 代价）。
     */
    private var loadedModelId: String? = null
    private var loadedThreads: Int = 0
    private var loadedNarrator: String? = null
    private var loadedRoleMode: String? = null

    @Volatile
    private var voicePool = VoiceCatalog.kokoroVoices()

    @Volatile
    private var roleAssigner: RoleAssigner = RoleAssigner(voicePool)

    @Volatile
    private var dropUnits = 0

    fun state() = sm.state
    fun lastError() = sm.lastError
    fun isBackendReady(): Boolean = backend?.isReady() == true

    fun initAsync() {
        reloadExecutor.execute { reloadInternal() }
    }

    /** 若常驻后端曾因内存压力被释放，在服务重绑时异步恢复预热 */
    fun ensureWarmIfIdleReleased() {
        if (destroyed || !idleReleased) return
        if (autoRewarmPending.compareAndSet(false, true)) {
            Log.i(TAG, "RELOAD|auto_rewarm|reason=service_rebind")
            reloadExecutor.execute { reloadInternal() }
        }
    }

    /** 记录停止意图时刻；是否生效由各请求按启动时间自行判定（见 STOP_GRACE_MS） */
    fun requestStop() {
        stopAtMs = android.os.SystemClock.uptimeMillis()
        // P7 / R4（BUG-P7-013）：中止必须**唤醒**正在 awaitBackend 等待就绪的合成线程，
        // 否则该线程只能等预算超时（原 1500 ms，后按 R5 提至 15 s）才感知中止 ⇒ 取消不即时。
        signalReady()
    }

    fun resetRoles() = roleAssigner.reset()

    /** L2 变更：400ms 去抖**只在重载线程**，绝不中止在途请求（红线 1） */
    fun scheduleReload(reason: String) {
        Log.i(TAG, "reload scheduled reason=$reason")
        reloadExecutor.execute {
            try {
                Thread.sleep(400)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@execute
            }
            if (destroyed) return@execute
            sm.requestBackendReload()
            if (sm.state != EngineState.SYNTHESIZING) {
                reloadInternal()
            }
            // 若在合成中：等接缝，由 synthesize() 结束时触发 reloadInternal
        }
    }

    /**
     * 销毁（ADR-008）：置停止 → 有界等待守卫锁 → 锁内释放 native。
     * tryLock 超时**不释放**，避免 native 仍在使用时释放导致 UAF；交由进程退出回收。
     */
    fun shutdown() {
        destroyed = true
        reloadExecutor.shutdownNow()
        pushExecutor.shutdownNow()
        prefetchExecutor.shutdownNow()
        var locked = false
        try {
            locked = guard.tryLock(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)
            if (locked) {
                oldRelease()
            } else {
                Log.w(
                    TAG,
                    "WARN|SHUTDOWN_TIMEOUT|guard busy>${SHUTDOWN_WAIT_MS}ms|native left to process teardown"
                )
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            if (locked) guard.unlock()
        }
    }

    private fun signalReady() {
        readyLock.withLock { readyCond.signalAll() }
    }

    private fun reloadInternal() {
        try {
            guard.withLock {
                idleReleased = false
                // ── P7 / R6b（BUG-P7-014 的核心根因）：配置未实质变化则**不做** release+load ──
                // 原实现无条件 oldRelease()+load() ⇒ 任何 bridge_* 广播（哪怕值没变）都要付
                // 真机 12 s 的整模型重载代价。现按"硬指纹 / 软指纹"分级：
                //   硬指纹（modelId + 线程数）变化 ⇒ 必须重载；
                //   仅软指纹（旁白音色 / 角色模式）变化 ⇒ 只重建内存角色/音色池（零 native 代价）；
                //   两者都没变 ⇒ 直接短路。
                val curModelId0 = ConfigStore.modelId()
                val curThreads0 = resolvedThreads()
                val curNarrator0 = ConfigStore.narratorVoice()
                val curRoleMode0 = ConfigStore.roleMode()
                val curBackend = backend
                if (curBackend != null && curBackend.isReady() &&
                    loadedModelId == curModelId0 && loadedThreads == curThreads0
                ) {
                    val softChanged = loadedNarrator != curNarrator0 || loadedRoleMode != curRoleMode0
                    if (softChanged) {
                        voicePool = VoiceCatalog.poolForModel(curModelId0, curBackend.numSpeakers())
                        roleAssigner = RoleAssigner(
                            pool = voicePool,
                            mode = if (curRoleMode0 == "respectReader") {
                                RoleMode.RESPECT_READER
                            } else {
                                RoleMode.SMART_MULTI
                            },
                            narrator = VoiceCatalog.resolve(voicePool, curNarrator0),
                        )
                        loadedNarrator = curNarrator0
                        loadedRoleMode = curRoleMode0
                        Log.i(TAG, "RELOAD|soft|reason=role_cfg|model=$curModelId0|narrator=$curNarrator0")
                    } else {
                        Log.i(TAG, "RELOAD|skipped|reason=unchanged|model=$curModelId0|threads=$curThreads0")
                    }
                    ConfigStore.setStatusState("READY")
                    return@withLock
                }
                oldRelease()
                val modelId = ConfigStore.modelId()
                val spec = ModelCatalog.byId(modelId)
                val dir = File(filesDir, "models/${spec.id}")
                if (!File(dir, spec.files.modelName).exists() ||
                    (spec.files.vocoder.isNotEmpty() && !File(dir, spec.files.vocoder).exists())
                ) {
                    voicePool = VoiceCatalog.vitsZhLlVoices()
                    backend = null
                    sm.onNoModel("model_missing")
                    ConfigStore.setStatusState("NO_MODEL")
                    ConfigStore.setStatusModelId(spec.id)
                    ConfigStore.setStatusLastError(
                        if (ConfigStore.routeMode().allowsLocal) "模型未安装" else ""
                    )
                    return@withLock
                }
                sm.onInitStart()
                val targetThreads = resolvedThreads()
                val b = SherpaBackend(dir, spec, targetThreads)
                b.load()
                if (!b.isReady()) {
                    sm.onInitFailure(b.loadError ?: "load_failed")
                    ConfigStore.setStatusState("ERROR")
                    ConfigStore.setStatusLastError(b.loadError ?: "load_failed")
                    return@withLock
                }
                backend = b
                voicePool = VoiceCatalog.poolForModel(spec.id, b.numSpeakers())
                // P7 / R6b：加载成功 ⇒ 记录指纹（供后续短路判定）
                loadedModelId = modelId
                loadedThreads = targetThreads
                loadedNarrator = ConfigStore.narratorVoice()
                loadedRoleMode = ConfigStore.roleMode()
                roleAssigner = RoleAssigner(
                    pool = voicePool,
                    mode = if (ConfigStore.roleMode() == "respectReader") {
                        RoleMode.RESPECT_READER
                    } else {
                        RoleMode.SMART_MULTI
                    },
                    narrator = VoiceCatalog.resolve(voicePool, ConfigStore.narratorVoice()),
                )
                if (sm.pendingBackendReload) sm.onReloadSuccess() else sm.onInitSuccess()
                ConfigStore.setStatusState("READY")
                ConfigStore.setStatusModelId(spec.id)
                ConfigStore.setStatusLastError("")
                Log.i(TAG, "reload ok model=$modelId sr=${b.sampleRate} speakers=${b.numSpeakers()}")
                // 桥接/诊断页读取的「实测采样率」：仅在加载成功后写入（未成功保持 0，不猜测）
                ConfigStore.setStatusSampleRate(b.sampleRate)
                // 状态广播（IM-510）：就绪态变化必须主动告知界面，否则徽标停留在旧态
                // （本线程 = reloadExecutor，非合成线程，sendBroadcast 开销可接受）
                ConfigStore.notifyStatus(context, "READY")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "reload failed", t)
            sm.onInitFailure(t.message ?: "reload_exception")
            ConfigStore.setStatusState("ERROR")
            ConfigStore.setStatusLastError(t.message ?: "reload_exception")
            ConfigStore.notifyStatus(context, "ERROR")
        } finally {
            autoRewarmPending.set(false)
            signalReady()
        }
    }

    /**
     * 空闲/内存压力下的**按需释放**（P7 追加，甲方 2026-09-17 要求）。
     *
     * 动机：常驻后端在国产 ROM 上"留存越多越容易被杀"。此方法只在**确无在用**时释放：
     * ① 预览未在播放；② 当前状态为 READY（非 BUSY）；两条都不满足则跳过并留日志。
     * 释放后再次需要时会走既有预热/加载路径（真机冷加载 ~12 s，属可接受代价）。
     */
    fun releaseIfIdle(reason: String): Boolean {
        if (CoordinatorHolder.previewActive) {
            Log.i(TAG, "RELEASE|skipped|reason=preview_active|$reason"); return false
        }
        if (activeSyntheses.get() > 0) {
            Log.i(TAG, "RELEASE|skipped|reason=active_synth(${activeSyntheses.get()})|$reason"); return false
        }
        return guard.withLock {
            if (activeSyntheses.get() > 0 || sm.state == EngineState.SYNTHESIZING) {
                Log.i(TAG, "RELEASE|skipped|reason=busy(${sm.state})|$reason"); return@withLock false
            }
            if (backend == null) { Log.i(TAG, "RELEASE|skipped|reason=already_released|$reason"); return@withLock false }
            oldRelease()
            idleReleased = true
            autoRewarmPending.set(false)
            ConfigStore.setStatusState("IDLE_RELEASED")
            Log.i(TAG, "RELEASE|done|reason=$reason")
            true
        }
    }

    private fun oldRelease() {
        backend?.release()
        backend = null
        // P7 / R6b：释放后指纹失效，避免误判为"已加载"
        loadedModelId = null
        loadedThreads = 0
        loadedNarrator = null
        loadedRoleMode = null
    }

    /**
     * 请求级在线选择（请求开始时调用一次；AR-§4.8.3 分级生效 / ADR-009）。
     *
     * 开销纪律：在线关闭 → 单次 MMKV 布尔读后立即返回 null，其余配置键零触达，
     * 无网络探测、无 await、无线程/锁变化 —— 本地路径保持既有行为。
     * 开启但不可用（无 Key / Token Plan 未确认 / 蜂窝未允许）→ 打点回落本地。
     */
    /**
     * 在线音色映射（P6 / IM-517）：请求音色（阅读器显式 / 旁白）→ MiMo 预置音色。
     * 只读 MMKV 与静态音色表，不加载任何模型，可在合成线程外的请求准备阶段调用。
     */
    private fun mappedOnlineVoice(voiceHint: String?): String {
        val pool = VoiceCatalog.poolForModel(ConfigStore.modelId())
        val narrator = ConfigStore.narratorVoice()
        val requested = voiceHint?.takeIf { VoiceCatalog.isValid(pool, it) } ?: narrator
        val fallback = OnlineVoiceMap.genderOf(narrator, pool)
        val mapped = OnlineVoiceMap.pick(requested, pool, fallback)
        Log.i(TAG, "ONLINE|voice_map|from=$requested|gender=${OnlineVoiceMap.genderOf(requested, pool)}|to=$mapped")
        return mapped
    }

    private data class RouteDecision(
        val routeMode: RouteMode,
        val choice: BackendChoice,
    ) {
        val online: BackendChoice.Online? get() = choice as? BackendChoice.Online
        val hardBlockReason: String? get() = OnlineSelector.hardBlockReason(choice)
    }

    private fun isLocalModelAvailable(): Boolean =
        backend?.isReady() == true || ModelPresence.isActiveModelInstalled(context)

    private fun resolveRoute(voiceHint: String?): RouteDecision {
        // RQ-513 路由模式（P7 第二批）
        val routeMode = ConfigStore.routeMode()
        if (routeMode == RouteMode.ONLY_LOCAL) {
            Log.i(TAG, "ROUTE|only_local|skip_online")
            return RouteDecision(routeMode, BackendChoice.Local("only_local"))
        }
        val localAvailable = isLocalModelAvailable()
        if (routeMode == RouteMode.PREFER_LOCAL && localAvailable) {
            return RouteDecision(routeMode, BackendChoice.Local("prefer_local"))
        }
        if (!ConfigStore.onlineEnabled()) {
            val choice = if (routeMode == RouteMode.ONLY_ONLINE) {
                BackendChoice.Local("${OnlineSelector.ONLY_ONLINE_BLOCKED_PREFIX}online_disabled")
            } else {
                BackendChoice.Local("online_disabled")
            }
            return RouteDecision(routeMode, choice)
        }
        // 角色音色（RQ-507 / ADR-013）：**请求级一次性快照**；仅支持角色造音色的引擎启用
        val providerKind = ConfigStore.onlineProviderKind()
        val roleEnabled = providerKind.supportsRoleVoiceDesign && ConfigStore.roleVoiceEnabled()
        val roles = if (roleEnabled) RoleRegistry.snapshot() else emptyMap()
        // 在线音色：MiMo 仍按本地池性别/语种映射；OpenAI/自定义 HTTP 直接取用户配置音色
        val onlineVoice = if (providerKind == TtsProviderKind.MIMO) {
            mappedOnlineVoice(voiceHint)
        } else {
            ConfigStore.onlineVoice().trim().ifEmpty { providerKind.defaultVoice }
        }
        val choice = OnlineSelector.select(
            onlineEnabled = true, // 已在上层短路（在线关闭零额外动作）
            apiKey = ConfigStore.onlineApiKey(),
            keyKind = ConfigStore.onlineKeyKind(),
            baseUrl = ConfigStore.onlineBaseUrl(),
            model = ConfigStore.onlineModel(),
            voice = onlineVoice,
            style = ConfigStore.onlineStyle(),
            tokenPlanAccepted = ConfigStore.onlineTokenPlanAccepted(),
            allowMobileData = ConfigStore.netAllowMobileData(),
            onCellular = NetState.onCellular(context),
            roleEnabled = roleEnabled,
            roles = roles,
            routeMode = routeMode,
            localModelAvailable = localAvailable,
            providerKind = providerKind,
            responseFormat = ConfigStore.onlineResponseFormat(),
            sampleRate = ConfigStore.onlineSampleRate(),
            customHeaders = ConfigStore.onlineCustomHeaders(),
            customBodyTemplate = ConfigStore.onlineCustomBodyTemplate(),
        )
        if (choice is BackendChoice.Local) {
            if (routeMode == RouteMode.ONLY_ONLINE) {
                Log.e(TAG, "ONLINE|only_online_blocked|reason=${choice.reason}")
            } else {
                Log.i(TAG, "ONLINE|fallback=local|reason=${choice.reason}")
            }
        }
        return RouteDecision(routeMode, choice)
    }

    data class SynthResult(
        val started: Boolean,
        val error: Boolean,
        val aborted: Boolean,
        val sampleRate: Int,
    )

    /**
     * 系统合成线程调用。回调契约：start ≤1 次；成功 done；失败 error(+done)；中止静默。
     */
    fun synthesize(
        rawText: String?,
        speechRateAosp: Int,
        explicitVoiceName: String?,
        callback: SynthesisCallback,
    ): SynthResult {
        val reqId = reqSeq.incrementAndGet()
        val startedAtMs = android.os.SystemClock.uptimeMillis()
        /** 本请求是否应停止：销毁，或"启动后超过宽限窗口才到达的 stop" */
        fun isStopped(): Boolean =
            destroyed || (stopAtMs - startedAtMs >= STOP_GRACE_MS)
        val t0 = System.currentTimeMillis()

        // 红线 4：推理只允许在 :tts_service 进程（主进程零推理）
        if (!isEngineProcess) {
            Log.e(TAG, "ABORT|INFERENCE_OUTSIDE_ENGINE_PROCESS|req=$reqId")
            callback.error(android.speech.tts.TextToSpeech.ERROR_SYNTHESIS)
            callback.done() // AOSP 契约：start 前的 error 必须后接 done 才派发 onError
            return SynthResult(false, true, false, 24000)
        }

        // 保留原始换行：整体归一化会把行压平，导致章节标题识别（RQ-108）与行内分句边界失效；
        // 归一化与噪声过滤由 TextAnalyzer.analyze() 逐行完成。
        val text = rawText ?: ""

        // ---- 请求级后端接缝（ADR-009 分级生效：请求开始时决定，先于本地等待）----
        // 在线链路不依赖本地模型：无本地模型时 ONLY_ONLINE / PREFER_ONLINE / PREFER_LOCAL(回落在线) 均可直接工作。
        val route = resolveRoute(explicitVoiceName)
        route.hardBlockReason?.let { blockReason ->
            // RQ-513：仅在线且在线不可用 ⇒ **如实报错**（禁止静默回落本地）
            Log.e(TAG, "ONLINE|only_online_blocked|abort|req=$reqId|reason=$blockReason")
            callback.error(android.speech.tts.TextToSpeech.ERROR_SYNTHESIS)
            callback.done() // AOSP 契约：start 前的 error 必须后接 done，否则客户端永久挂起
            return SynthResult(false, true, false, OnlineSettings.SAMPLE_RATE)
        }
        val onlineChoice = route.online
        val onlineRequest = onlineChoice?.request
        var fallbackLogged = false
        if (onlineRequest != null) {
            Log.i(
                TAG,
                "ONLINE|chosen=online|model=${onlineRequest.model}" +
                    "|voice=${onlineRequest.voice}|sr=${OnlineSettings.SAMPLE_RATE}"
            )
            onlineChoice.warning?.let { Log.w(TAG, "ONLINE|warn=key_kind_mismatch|$it") }
        }
        var useOnline = onlineRequest != null

        var engine: SherpaBackend? = if (useOnline) {
            backend?.takeIf { it.isReady() }
        } else {
            awaitBackend { isStopped() }
        }
        if (!useOnline && engine == null) {
            val reason = when {
                destroyed -> "destroyed"
                stopAtMs - startedAtMs >= STOP_GRACE_MS -> "stopped"
                sm.state == EngineState.NO_MODEL -> "no_model"
                sm.state == EngineState.ERROR -> "engine_error"
                else -> "backend_not_ready"
            }
            Log.w(TAG, "WARN|SYNTH_REJECT|req=$reqId|reason=$reason|state=${sm.state}")
            val err = if (sm.state == EngineState.NO_MODEL) {
                android.speech.tts.TextToSpeech.ERROR_NOT_INSTALLED_YET
            } else {
                android.speech.tts.TextToSpeech.ERROR_SYNTHESIS
            }
            callback.error(err)
            callback.done() // AOSP 契约：同上，否则客户端永久挂起
            return SynthResult(false, true, false, 24000)
        }

        val initialSr = if (useOnline) {
            onlineRequest!!.sampleRate.takeIf { it > 0 } ?: OnlineSettings.SAMPLE_RATE
        } else {
            engine?.sampleRate ?: 24000
        }
        if (text.isBlank()) {
            callback.start(initialSr, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return SynthResult(true, false, false, initialSr)
        }

        activeSyntheses.incrementAndGet()
        val enteredSm = if (!useOnline || sm.canAcceptRequest()) sm.onSynthesizeStart() else false
        if (!useOnline && !enteredSm) {
            activeSyntheses.decrementAndGet()
            callback.error(android.speech.tts.TextToSpeech.ERROR_SYNTHESIS)
            callback.done() // AOSP 契约：同上
            return SynthResult(false, true, false, initialSr)
        }

        val pool = voicePool
        val mode = if (ConfigStore.roleMode() == "respectReader") {
            RoleMode.RESPECT_READER
        } else {
            RoleMode.SMART_MULTI
        }
        // RQ-106：smart 模式忽略客户端自动注入的默认旁白名
        val explicit = explicitVoiceName
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { mode == RoleMode.RESPECT_READER || it != ConfigStore.narratorVoice() }
            ?.takeIf { VoiceCatalog.isValid(pool, it) }

        val speed = SpeedMapper.resolveSpeed(
            speechRateAosp = speechRateAosp,
            appSpeed = ConfigStore.speedValue(),
            speedMode = ConfigStore.speedMode(),
        )
        val segments = TextAnalyzer.analyze(text)
        data class SpeakUnit(
            val seg: TextSegment,
            val textForLocal: String,
            val textForOnline: String,
            val noTrailingPause: Boolean,
        )
        val units = mutableListOf<SpeakUnit>()
        var didMicroCut = false
        for (seg in segments) {
            if (seg.kind == SegmentKind.EMPTY) continue
            val onlineBase = if (seg.kind == SegmentKind.TITLE) TextClean.chapterSpeakText(seg.text) else seg.text
            if (onlineBase.isBlank()) continue
            if (!useOnline && !didMicroCut && seg.kind != SegmentKind.TITLE && onlineBase.length > 16) {
                didMicroCut = true
                val (head, rest) = TextAnalyzer.firstSegmentMicroCut(onlineBase)
                if (rest.isNotBlank()) {
                    val headLocal = com.kermond.ebook2tts.core.NumberReader.speakDigits(head)
                    val restLocal = com.kermond.ebook2tts.core.NumberReader.speakDigits(rest)
                    if (headLocal.isNotBlank()) {
                        units += SpeakUnit(seg, headLocal, head, noTrailingPause = true)
                    }
                    if (restLocal.isNotBlank()) {
                        units += SpeakUnit(seg, restLocal, rest, noTrailingPause = false)
                    }
                    continue
                }
            }
            val localText = prepareSpeakText(seg)
            if (localText.isNotBlank()) {
                units += SpeakUnit(seg, localText, onlineBase, noTrailingPause = false)
            }
        }

        val queue = ArrayBlockingQueue<AudioBlock>(QUEUE_CAPACITY)
        val started = AtomicBoolean(false)
        val anyAudio = AtomicBoolean(false)
        /** 是否已有音频进入推流队列（在线回落时机判定；含排队未推送） */
        val audioCommitted = AtomicBoolean(false)
        val aborted = AtomicBoolean(false)
        val chunksPushed = AtomicInteger(0)
        val bytesPushed = AtomicLong(0L)
        val firstPushAt = AtomicLong(0L)

        /** callback.start 的采样率＝实际产出首个音频的后端采样率（原子读写供推手线程同步可见） */
        val streamSr = AtomicInteger(initialSr)
        var onlineTtfbLogged = false

        val needOnlineStretch = onlineRequest != null &&
            !onlineRequest.usesNativeSpeed() &&
            !PcmSpeedStretcher.isNoop(speed)
        var localFallbackResampler: StreamingPcmResampler? = null

        /** 在线失败 → 回落决策（支持采样率不同时自动重采样接续） */
        fun onOnlineFailure(failure: OnlineOutcome.Failed) {
            if (!route.routeMode.allowsLocal) {
                if (!fallbackLogged) {
                    fallbackLogged = true
                    Log.e(
                        TAG,
                        "ONLINE|only_online_failed|reason=${failure.reason}|summary=${failure.summary.take(160)}"
                    )
                }
                return
            }
            if (engine == null && isLocalModelAvailable()) {
                engine = awaitBackend { isStopped() }
            }
            val local = engine?.takeIf { it.isReady() }
            val curSr = streamSr.get()
            val action = OnlineFallback.decide(
                anyAudioEnqueued = audioCommitted.get(),
                localSampleRate = local?.sampleRate ?: 0,
                onlineSampleRate = curSr,
                allowsLocal = route.routeMode.allowsLocal,
                localBackendAvailable = local != null,
            )
            if (!fallbackLogged) {
                fallbackLogged = true
                val suffix = when {
                    local == null -> "|no_local_backend"
                    action == FallbackAction.STAY_ONLINE -> "|resample=${local.sampleRate}->$curSr"
                    else -> ""
                }
                Log.w(
                    TAG,
                    "ONLINE|fallback=${if (local == null) "none" else "local"}" +
                        "|reason=${failure.reason}$suffix|summary=${failure.summary.take(160)}"
                )
            }
            if (local != null) {
                useOnline = false
                if (!audioCommitted.get()) {
                    streamSr.set(local.sampleRate)
                    localFallbackResampler = null
                } else if (local.sampleRate != curSr) {
                    localFallbackResampler = StreamingPcmResampler(local.sampleRate, curSr)
                }
            }
        }

        fun pushBytes(pcm: ByteArray): Boolean {
            var i = 0
            while (i < pcm.size) {
                if (isStopped()) {
                    aborted.set(true)
                    return false
                }
                try {
                    val activeSr = streamSr.get()
                    if (!started.get()) {
                        val rc = callback.start(activeSr, AudioFormat.ENCODING_PCM_16BIT, 1)
                        if (rc != android.speech.tts.TextToSpeech.SUCCESS) {
                            aborted.set(true)
                            return false
                        }
                        started.set(true)
                    }
                    val end = minOf(i + MAX_AUDIO_BYTES, pcm.size)
                    val slice = if (i == 0 && end == pcm.size) pcm else pcm.copyOfRange(i, end)
                    val rc = callback.audioAvailable(slice, 0, slice.size)
                    if (rc != android.speech.tts.TextToSpeech.SUCCESS) {
                        aborted.set(true)
                        return false
                    }
                    if (!anyAudio.getAndSet(true)) firstPushAt.set(System.currentTimeMillis())
                    chunksPushed.incrementAndGet()
                    bytesPushed.addAndGet(slice.size.toLong())
                    i = end
                } catch (e: Exception) {
                    // Binder DeadObjectException / 框架 IllegalArgumentException：必须置 aborted，
                    // 否则合成线程在 enqueue 超时循环里空转（agy 复核【11】-2）
                    Log.e(TAG, "ABORT|IPC_PUSH_FAIL|req=$reqId", e)
                    aborted.set(true)
                    return false
                }
            }
            return true
        }

        // 推送线程：IPC 不持守卫锁（红线 7）；首块头 / 末块尾微淡化（红线 6）
        //
        // 在线语速（RQ-508 / ADR-014）：在**本（推手）线程**对在线块做变速不变调（Sonic 时域伸缩），
        // 采样率/声道不变；本地路径由 AudioBlock.stretch=false 标记，不经 Sonic（避免二次变速）。
        val pusher = pushExecutor.submit {
            var held: ByteArray? = null
            var firstPending = true
            var stretchedAny = false
            val stretcher = PcmSpeedStretcher(speed)

            /** 推送一块（含首/尾微淡化）；返回 false = 已中止 */
            fun pushOne(block: ByteArray, isLast: Boolean): Boolean {
                if (block.isEmpty()) return true
                val srNow = streamSr.get()
                val out = when {
                    isLast && firstPending -> {
                        firstPending = false
                        PcmChunker.fadeEdges(block, srNow, 3)
                    }
                    isLast -> PcmChunker.fadeTail(block, srNow, 3)
                    firstPending -> {
                        firstPending = false
                        PcmChunker.fadeHead(block, srNow, 3)
                    }
                    else -> block
                }
                return pushBytes(out)
            }

            try {
                while (true) {
                    val blk = queue.take()
                    if (blk.pcm.isEmpty()) break
                    if (!blk.stretch && stretchedAny) {
                        stretchedAny = false
                        val flushed = stretcher.end()
                        if (flushed.isNotEmpty()) {
                            held?.let { if (!pushOne(it, isLast = false)) return@submit }
                            held = flushed
                        }
                    }
                    val processed = if (blk.stretch) {
                        stretchedAny = true
                        stretcher.process(blk.pcm, streamSr.get())
                    } else {
                        blk.pcm
                    }
                    if (processed.isEmpty()) continue // Sonic 可能先缓冲、暂无输出
                    held?.let { if (!pushOne(it, isLast = false)) return@submit }
                    held = processed
                }
                // 榨出 Sonic 内部残余样本，否则末段会被截断
                val tail = if (stretchedAny) stretcher.end() else ByteArray(0)
                if (tail.isNotEmpty()) {
                    held?.let { if (!pushOne(it, isLast = false)) return@submit }
                    held = tail
                }
                held?.let { pushOne(it, isLast = true) }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (t: Throwable) {
                Log.e(TAG, "ABORT|PUSHER_DIED|req=$reqId", t)
                aborted.set(true)
            }
        }

        // 供给：合成线程持守卫锁调用 native/在线；IPC 由推手完成
        fun enqueue(pcm: ByteArray, stretch: Boolean = false) {
            if (pcm.isEmpty()) return
            val block = AudioBlock(pcm, stretch)
            while (!isStopped() && !aborted.get()) {
                try {
                    if (queue.offer(block, 200, TimeUnit.MILLISECONDS)) {
                        audioCommitted.set(true)
                        return
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }

        val designedLogged = HashSet<String>()

        fun buildSegmentOnlineRequest(seg: TextSegment, base: OnlineRequest, candidatesSeen: MutableSet<String>): OnlineRequest {
            val roleName = if (mode == RoleMode.SMART_MULTI) {
                seg.speakerHint?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                null
            }
            if (roleName != null) candidatesSeen.add(roleName)
            val segPlan = if (base.providerKind.supportsRoleVoiceDesign && roleName != null) {
                OnlineSegmentPlanner.plan(
                    baseModel = base.model,
                    baseVoice = base.voice,
                    baseStyle = base.style,
                    roleEnabled = base.roleEnabled,
                    roleName = roleName,
                    design = base.roles[roleName],
                )
            } else {
                null
            }
            if (segPlan?.useDesign == true && roleName != null && designedLogged.add(roleName)) {
                Log.i(TAG, "ONLINE|role=$roleName|design=1|model=${segPlan.model}")
            }
            return segPlan?.let { base.forSegment(it) } ?: base
        }

        fun emitOnlineChunk(chunk: TtsAudioChunk, activeResampler: StreamingPcmResampler?): StreamingPcmResampler? {
            if (chunk.pcm16Mono.isEmpty()) return activeResampler
            val curSr = streamSr.get()
            val chunkSr = if (chunk.sampleRate > 0) chunk.sampleRate else curSr
            val targetSr = if (!audioCommitted.get() && chunkSr != curSr) {
                streamSr.set(chunkSr)
                chunkSr
            } else {
                curSr
            }
            val resampler = if (chunkSr != targetSr) {
                activeResampler ?: StreamingPcmResampler(chunkSr, targetSr)
            } else {
                null
            }
            val pcm = resampler?.process(chunk.pcm16Mono) ?: chunk.pcm16Mono
            if (pcm.isNotEmpty()) {
                enqueue(pcm, stretch = needOnlineStretch)
            }
            return resampler
        }

        /**
         * 在线单段合成（**不持锁**）：支持多引擎解码与动态采样率对齐。
         */
        fun synthesizeOnlineSegment(request: OnlineRequest, text: String): OnlineOutcome {
            var segResampler: StreamingPcmResampler? = null
            val outcome = OnlineBackendHolder.get().synthesizeChunks(
                request = request,
                text = text,
                speed = speed,
                onChunk = onChunk@{ chunk ->
                    if (isStopped() || aborted.get()) return@onChunk false
                    segResampler = emitOnlineChunk(chunk, segResampler)
                    !(isStopped() || aborted.get())
                },
                isCancelled = { isStopped() || aborted.get() },
            )
            if (outcome is OnlineOutcome.Ok) {
                val tail = segResampler?.flush()
                if (tail != null && tail.isNotEmpty() && !isStopped() && !aborted.get()) {
                    enqueue(tail, stretch = needOnlineStretch)
                }
            }
            return outcome
        }

        data class PrefetchResult(
            val chunks: List<TtsAudioChunk>,
            val outcome: OnlineOutcome,
        )

        var pendingPrefetchIdx = -1
        var pendingPrefetchCancel: AtomicBoolean? = null
        var pendingPrefetchFuture: Future<PrefetchResult>? = null

        fun cancelPrefetch() {
            pendingPrefetchCancel?.set(true)
            pendingPrefetchFuture?.cancel(false)
            pendingPrefetchIdx = -1
            pendingPrefetchCancel = null
            pendingPrefetchFuture = null
        }

        fun startPrefetchIfNeeded(nextIdx: Int, baseReq: OnlineRequest, candidatesSeen: MutableSet<String>) {
            if (!useOnline || nextIdx >= units.size) return
            if (isStopped() || aborted.get()) return
            val nextUnit = units[nextIdx]
            val nextReq = buildSegmentOnlineRequest(nextUnit.seg, baseReq, candidatesSeen)
            val pfCancel = AtomicBoolean(false)
            val fut = runCatching {
                prefetchExecutor.submit(Callable {
                    val collected = ArrayList<TtsAudioChunk>()
                    val out = OnlineBackendHolder.get().synthesizeChunks(
                        request = nextReq,
                        text = nextUnit.textForOnline,
                        speed = speed,
                        onChunk = { chunk ->
                            if (pfCancel.get() || isStopped() || aborted.get()) return@synthesizeChunks false
                            if (chunk.pcm16Mono.isNotEmpty()) collected.add(chunk)
                            true
                        },
                        isCancelled = { pfCancel.get() || isStopped() || aborted.get() },
                    )
                    PrefetchResult(collected, out)
                })
            }.getOrNull()
            if (fut != null) {
                pendingPrefetchIdx = nextIdx
                pendingPrefetchCancel = pfCancel
                pendingPrefetchFuture = fut
            }
        }

        // 待精标候选（角色名，按首次出现顺序）——本次朗读可见的说话人
        val candidatesSeen = LinkedHashSet<String>()

        try {
            try {
                for (idx in units.indices) {
                    if (isStopped() || aborted.get()) break
                    val unit = units[idx]
                    val seg = unit.seg
                    val spokenLocal = unit.textForLocal
                    val spokenOnline = unit.textForOnline

                    val voice = if (explicit != null && mode == RoleMode.RESPECT_READER) {
                        VoiceCatalog.resolve(pool, explicit)
                    } else {
                        roleAssigner.assign(seg)
                    }

                    var produced = false
                    if (useOnline && onlineRequest != null) {
                        val prefetched: PrefetchResult? = if (pendingPrefetchIdx == idx && pendingPrefetchFuture != null) {
                            val fut = pendingPrefetchFuture!!
                            pendingPrefetchIdx = -1
                            pendingPrefetchCancel = null
                            pendingPrefetchFuture = null
                            runCatching { fut.get() }.getOrNull()
                        } else {
                            cancelPrefetch()
                            null
                        }

                        // 启动下一段 Lookahead 预取（若在线仍在生效）
                        startPrefetchIfNeeded(idx + 1, onlineRequest, candidatesSeen)

                        val outcome = if (prefetched != null) {
                            if (prefetched.outcome is OnlineOutcome.Ok) {
                                var pfResampler: StreamingPcmResampler? = null
                                for (c in prefetched.chunks) {
                                    if (isStopped() || aborted.get()) break
                                    pfResampler = emitOnlineChunk(c, pfResampler)
                                }
                                val tail = pfResampler?.flush()
                                if (tail != null && tail.isNotEmpty() && !isStopped() && !aborted.get()) {
                                    enqueue(tail, stretch = needOnlineStretch)
                                }
                            }
                            prefetched.outcome
                        } else {
                            val segRequest = buildSegmentOnlineRequest(seg, onlineRequest, candidatesSeen)
                            synthesizeOnlineSegment(segRequest, spokenOnline)
                        }

                        when (outcome) {
                            is OnlineOutcome.Ok -> {
                                produced = true
                                if (ConfigStore.onlineLastError().isNotEmpty()) {
                                    ConfigStore.setOnlineLastError("")
                                    ConfigStore.notifyStatus(context, "ONLINE_OK")
                                }
                                if (!onlineTtfbLogged && outcome.ttfbMs >= 0L) {
                                    onlineTtfbLogged = true
                                    Log.i(TAG, "ONLINE|ttfb_ms=${outcome.ttfbMs}")
                                }
                            }
                            OnlineOutcome.Cancelled -> Unit // 停止/中止：循环顶部统一退出
                            is OnlineOutcome.Failed -> {
                                ConfigStore.setOnlineLastError(outcome.summary)
                                ConfigStore.notifyStatus(context, "ONLINE_ERR")
                                cancelPrefetch()
                                onOnlineFailure(outcome)
                            }
                        }
                    }
                    val localEngine = engine
                    if (!produced && !useOnline && localEngine != null && localEngine.isReady()) {
                        fun emitLocalPcm(rawPcm: ByteArray): Boolean {
                            if (isStopped() || aborted.get()) return false
                            val pcm = localFallbackResampler?.process(rawPcm) ?: rawPcm
                            if (pcm.isEmpty()) return true
                            produced = true
                            enqueue(pcm, stretch = false)
                            return !(isStopped() || aborted.get())
                        }
                        localEngine.generateStreamingCached(spokenLocal, voice.speakerId, speed, ::emitLocalPcm)
                        if (!produced) {
                            // 字符强清洗重试（AR-§4.8.6；G2P 未映射字符是零音频唯一确定性根因）
                            val cleaned = TextClean.hardClean(spokenLocal)
                            if (cleaned.isNotBlank()) {
                                localEngine.generateStreamingCached(cleaned, voice.speakerId, speed, ::emitLocalPcm)
                            }
                        }
                        localFallbackResampler?.flush()?.takeIf { it.isNotEmpty() }?.let { tail ->
                            if (!isStopped() && !aborted.get()) enqueue(tail, stretch = false)
                        }
                    }
                    val srPause = streamSr.get()
                    if (!produced) {
                        dropUnits++
                        ConfigStore.bumpDropUnit()
                        Log.w(TAG, "WARN|DROP_UNIT|$dropUnits|len=${spokenLocal.length}")
                        if (anyAudio.get()) {
                            // 保段序静音占位（绝不因单元失败中止在途音频，红线 2）
                            enqueue(PcmChunker.silenceMs(150, speed, srPause), stretch = false)
                        }
                    } else if (seg.kind != SegmentKind.TITLE && !unit.noTrailingPause) {
                        enqueue(PcmChunker.silenceMs(120, speed, srPause), stretch = false)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "synthesize error req=$reqId", t)
            } finally {
                cancelPrefetch()
                // 角色精标输入（ADR-013）：把本次可见文本与说话人交给后台线程限频落盘，
                // 由**主进程**在后续调用精标（本进程零 LLM）。合成线程此处零 IO。
                // 注：即便本请求中途回落本地，文本已被用户"听到"，仍应作为精标输入（角色与后端无关）。
                if (onlineRequest?.roleEnabled == true && candidatesSeen.isNotEmpty()) {
                    RoleRegistry.offerExcerpt(text, candidatesSeen)
                }
                // 无条件（含中止/异常路径）释放推送线程：毒丸 + 有界收尾
                val poisonDeadline = System.currentTimeMillis() + 2_000L
                while (!pusher.isDone && System.currentTimeMillis() < poisonDeadline) {
                    try {
                        if (queue.offer(END, 200, TimeUnit.MILLISECONDS)) break
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
                runCatching { queue.offer(END) }
                runCatching { pusher.get(PUSH_JOIN_MS, TimeUnit.MILLISECONDS) }
                    .onFailure { pusher.cancel(true) } // 超时/中断：中断推手，防污染单线程执行器
            }
        } finally {
            if (enteredSm) sm.onSynthesizeEnd()
            activeSyntheses.decrementAndGet()
        }

        // 接缝换装（ADR-009）：在请求结束窗口完成 swap，绝不在请求中打断
        if (sm.pendingBackendReload && !isStopped()) {
            reloadExecutor.execute { reloadInternal() }
        }

        val sr = streamSr.get()
        val ttfb = if (firstPushAt.get() > 0L) firstPushAt.get() - t0 else -1L
        val totalMs = System.currentTimeMillis() - t0
        if (anyAudio.get()) {
            ConfigStore.recordLastPerf(ttfb, totalMs, dropUnits, if (useOnline) "online" else "local")
            ConfigStore.notifyStatus(context, "PERF")
        }
        Log.i(
            TAG,
            "PERF|req=$reqId|segs=${segments.size}|chunks=${chunksPushed.get()}" +
                "|ttfb_ms=$ttfb|total_ms=$totalMs|drop=$dropUnits"
        )
        // 合成侧完成打点（AR-§4.8.5：引擎看不到"播完"时刻，提供字节数供客户端估算）
        Log.i(
            TAG,
            "synth_done|req=$reqId|bytes=${bytesPushed.get()}|sr=$sr" +
                "|est_ms=${bytesPushed.get() / 2L * 1000L / sr.coerceAtLeast(1)}"
        )

        if (isStopped() && !started.get()) {
            // 中止且从未 start：静默（不 done、不 error）
            return SynthResult(false, false, true, sr)
        }
        if (!started.get()) {
            if (text.isNotBlank() && segments.any { it.kind != SegmentKind.EMPTY }) {
                // 红线 3：非空文本零音频必须 error 可见（假成功禁令）
                callback.error(android.speech.tts.TextToSpeech.ERROR_SYNTHESIS)
                callback.done() // AOSP 契约：同上
                return SynthResult(false, true, false, sr)
            }
            callback.start(sr, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return SynthResult(true, false, false, sr)
        }
        // start 过必须收尾（官方契约：done() 必须调用）
        callback.done()
        return SynthResult(true, false, aborted.get(), sr)
    }

    private fun prepareSpeakText(seg: TextSegment): String {
        var t = seg.text
        if (seg.kind == SegmentKind.TITLE) {
            t = TextClean.chapterSpeakText(t)
        }
        t = com.kermond.ebook2tts.core.NumberReader.speakDigits(t)
        return t
    }

    /** 有界等待后端就绪（条件变量，非 sleep 轮询；红线 7） */
    /**
     * 预览通道借用常驻后端（P7 / R1，BUG-P7-015）。
     *
     * 真机实测：预览通道原先每次试听都自建后端并 `load()`+`release()`（SDM845 单次 12.2 s，
     * 连续第二次试听总耗时 80.0 s）。现改为借用本协调器的常驻实例：
     * **借用方严禁调用 `release()`**（释放权归协调器：模型切换 / 服务销毁）。
     *
     * @param isStopped 中止判定（预览侧传自身播放标志），确保等待就绪期间可被及时打断。
     */
    fun borrowBackend(isStopped: () -> Boolean): SherpaBackend? = awaitBackend(isStopped)

    private fun awaitBackend(isStopped: () -> Boolean): SherpaBackend? {
        backend?.takeIf { it.isReady() }?.let { return it }
        if (destroyed || isStopped()) return null
        if (sm.state == EngineState.NO_MODEL || sm.state == EngineState.ERROR) return null
        if (idleReleased || (backend == null && sm.state == EngineState.READY)) {
            if (autoRewarmPending.compareAndSet(false, true)) {
                Log.i(TAG, "RELOAD|auto_rewarm|reason=await_backend")
                reloadExecutor.execute { reloadInternal() }
            }
        }
        readyLock.withLock {
            val deadline = System.nanoTime() + READY_WAIT_MS * 1_000_000L
            while (true) {
                backend?.takeIf { it.isReady() }?.let { return it }
                if (destroyed || isStopped()) return null
                if (sm.state == EngineState.NO_MODEL || sm.state == EngineState.ERROR) return null
                val remain = deadline - System.nanoTime()
                if (remain <= 0L) return null
                try {
                    readyCond.await(minOf(remain, 50_000_000L), TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
    }

    /** 当前进程是否为 :tts_service（红线 4）。进程身份在生命周期内不可变 → 懒加载一次，合成路径零 IO。 */
    private val isEngineProcess: Boolean by lazy {
        val cmdline = runCatching { File("/proc/self/cmdline").readBytes() }.getOrNull()
        ProcessNames.isEngineProcess(ProcessNames.parseCmdline(cmdline))
    }
}

/**
 * P7 / R7（BUG-P7-001）：原 `TextToSpeechErrors` 常量镜像已**删除**——其值错位
 * （SYNTHESIS=-4 实为平台 ERROR_SERVICE；NOT_INSTALLED_YET=-12 为未定义值，平台应为 -9），
 * 已导致客户端（Legado）实测收到 `onError errorCode:-4`。现一律直接引用平台常量
 * `android.speech.tts.TextToSpeech.ERROR_*` / `SUCCESS`。
 */
