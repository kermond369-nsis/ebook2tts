package com.kermond.ebook2tts.engine

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.util.Log
import com.kermond.ebook2tts.core.EngineState
import com.kermond.ebook2tts.core.EngineStateMachine
import com.kermond.ebook2tts.core.ModelCatalog
import com.kermond.ebook2tts.core.PcmChunker
import com.kermond.ebook2tts.core.ProcessNames
import com.kermond.ebook2tts.core.RoleAssigner
import com.kermond.ebook2tts.core.RoleMode
import com.kermond.ebook2tts.core.SegmentKind
import com.kermond.ebook2tts.core.SpeedMapper
import com.kermond.ebook2tts.core.TextAnalyzer
import com.kermond.ebook2tts.core.TextClean
import com.kermond.ebook2tts.core.TextSegment
import com.kermond.ebook2tts.core.VoiceCatalog
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
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
 * - **锁纪律**（红线 7）：守卫锁只包住 native 调用（合成/换装/释放/试听），IPC 推送在推手线程完成。
 * - **微淡化**（红线 6）：仅请求首块头部（fadeHead）与末块尾部（fadeTail）；单块请求用 fadeEdges。
 * - **有界销毁**（ADR-008）：置停止 → tryLock(≤3s) → 锁内释放；超时**不释放**（防 UAF），交进程回收。
 * - **假成功禁令**（红线 3）：非空文本零音频必须 error 可见。
 *
 * 仅在 :tts_service 进程使用（红线 4 硬断言）。
 */
class SynthesisCoordinator(
    private val filesDir: File,
) {
    companion object {
        private const val TAG = "SynthCoord"
        private const val MAX_AUDIO_BYTES = 8192
        private const val QUEUE_CAPACITY = 16
        private const val READY_WAIT_MS = 1500L
        private const val SHUTDOWN_WAIT_MS = 3000L
        private const val PUSH_JOIN_MS = 60_000L

        /** 陈旧 stop 宽限窗口（见 requestStop 处注释） */
        private const val STOP_GRACE_MS = 150L
        private val END = ByteArray(0) // 毒丸
    }

    /** native 串行锁：合成 / 换装 / 释放 / 试听 共用（ADR-008 守卫锁） */
    private val guard = ReentrantLock()

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

    private val reloadExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tts-reload").apply { isDaemon = true }
    }
    private val pushExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tts-push").apply { isDaemon = true }
    }

    @Volatile
    private var backend: SherpaBackend? = null

    @Volatile
    private var voicePool = VoiceCatalog.kokoroVoices()

    @Volatile
    private var roleAssigner: RoleAssigner = RoleAssigner(voicePool)

    @Volatile
    private var dropUnits = 0

    fun state() = sm.state
    fun lastError() = sm.lastError

    fun initAsync() {
        reloadExecutor.execute { reloadInternal() }
    }

    /** 记录停止意图时刻；是否生效由各请求按启动时间自行判定（见 STOP_GRACE_MS） */
    fun requestStop() {
        stopAtMs = android.os.SystemClock.uptimeMillis()
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
                oldRelease()
                val modelId = ConfigStore.modelId()
                val spec = ModelCatalog.byId(modelId)
                val dir = File(filesDir, "models/${spec.id}")
                if (!File(dir, spec.files.modelName).exists()) {
                    voicePool = VoiceCatalog.vitsZhLlVoices()
                    backend = null
                    sm.onNoModel("model_missing")
                    ConfigStore.setStatusState("NO_MODEL")
                    ConfigStore.setStatusModelId(spec.id)
                    ConfigStore.setStatusLastError("模型未安装")
                    return@withLock
                }
                sm.onInitStart()
                val b = SherpaBackend(dir, spec, ConfigStore.threads())
                b.load()
                if (!b.isReady()) {
                    sm.onInitFailure(b.loadError ?: "load_failed")
                    ConfigStore.setStatusState("ERROR")
                    ConfigStore.setStatusLastError(b.loadError ?: "load_failed")
                    return@withLock
                }
                backend = b
                voicePool = VoiceCatalog.poolForModel(spec.id, b.numSpeakers())
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
            }
        } catch (t: Throwable) {
            Log.e(TAG, "reload failed", t)
            sm.onInitFailure(t.message ?: "reload_exception")
            ConfigStore.setStatusState("ERROR")
            ConfigStore.setStatusLastError(t.message ?: "reload_exception")
        } finally {
            signalReady()
        }
    }

    private fun oldRelease() {
        backend?.release()
        backend = null
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
            callback.error(TextToSpeechErrors.SYNTHESIS)
            callback.done() // AOSP 契约：start 前的 error 必须后接 done 才派发 onError
            return SynthResult(false, true, false, 24000)
        }

        // 保留原始换行：整体归一化会把行压平，导致章节标题识别（RQ-108）与行内分句边界失效；
        // 归一化与噪声过滤由 TextAnalyzer.analyze() 逐行完成。
        val text = rawText ?: ""
        val engine = awaitBackend { isStopped() }
        if (engine == null) {
            val reason = when {
                destroyed -> "destroyed"
                stopAtMs - startedAtMs >= STOP_GRACE_MS -> "stopped"
                sm.state == EngineState.NO_MODEL -> "no_model"
                sm.state == EngineState.ERROR -> "engine_error"
                else -> "backend_not_ready"
            }
            Log.w(TAG, "WARN|SYNTH_REJECT|req=$reqId|reason=$reason|state=${sm.state}")
            val err = if (sm.state == EngineState.NO_MODEL) {
                TextToSpeechErrors.NOT_INSTALLED_YET
            } else {
                TextToSpeechErrors.SYNTHESIS
            }
            callback.error(err)
            callback.done() // AOSP 契约：同上，否则客户端永久挂起
            return SynthResult(false, true, false, 24000)
        }

        if (text.isBlank()) {
            callback.start(engine.sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return SynthResult(true, false, false, engine.sampleRate)
        }

        if (!sm.onSynthesizeStart()) {
            callback.error(TextToSpeechErrors.SYNTHESIS)
            callback.done() // AOSP 契约：同上
            return SynthResult(false, true, false, engine.sampleRate)
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

        val speed = SpeedMapper.actualSpeed(speechRateAosp, 0f)
        val segments = TextAnalyzer.analyze(text)

        val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
        val started = AtomicBoolean(false)
        val anyAudio = AtomicBoolean(false)
        val aborted = AtomicBoolean(false)
        val chunksPushed = AtomicInteger(0)
        val bytesPushed = AtomicLong(0L)
        val firstPushAt = AtomicLong(0L)

        fun pushBytes(pcm: ByteArray): Boolean {
            var i = 0
            while (i < pcm.size) {
                if (isStopped()) {
                    aborted.set(true)
                    return false
                }
                try {
                    if (!started.get()) {
                        val rc = callback.start(engine.sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
                        if (rc != TextToSpeechErrors.SUCCESS) {
                            aborted.set(true)
                            return false
                        }
                        started.set(true)
                    }
                    val end = minOf(i + MAX_AUDIO_BYTES, pcm.size)
                    val slice = if (i == 0 && end == pcm.size) pcm else pcm.copyOfRange(i, end)
                    val rc = callback.audioAvailable(slice, 0, slice.size)
                    if (rc != TextToSpeechErrors.SUCCESS) {
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
        val pusher = pushExecutor.submit {
            var held: ByteArray? = null
            var firstPending = true
            try {
                while (true) {
                    val blk = queue.take()
                    if (blk.isEmpty()) break
                    val prev = held
                    if (prev != null) {
                        val out = if (firstPending) {
                            firstPending = false
                            PcmChunker.fadeHead(prev, engine.sampleRate, 3)
                        } else {
                            prev
                        }
                        if (!pushBytes(out)) return@submit
                    }
                    held = blk
                }
                val last = held
                if (last != null) {
                    val out = if (firstPending) {
                        // 单块请求：首尾都是它
                        PcmChunker.fadeEdges(last, engine.sampleRate, 3)
                    } else {
                        PcmChunker.fadeTail(last, engine.sampleRate, 3)
                    }
                    pushBytes(out)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (t: Throwable) {
                Log.e(TAG, "ABORT|PUSHER_DIED|req=$reqId", t)
                aborted.set(true)
            }
        }

        // 供给：合成线程持守卫锁调用 native；IPC 由推手完成
        fun enqueue(pcm: ByteArray) {
            if (pcm.isEmpty()) return
            while (!isStopped() && !aborted.get()) {
                try {
                    if (queue.offer(pcm, 200, TimeUnit.MILLISECONDS)) return
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }

        try {
            for (seg in segments) {
                if (isStopped() || aborted.get()) break
                if (seg.kind == SegmentKind.EMPTY) continue
                val spoken = prepareSpeakText(seg)
                if (spoken.isBlank()) continue

                val voice = if (explicit != null && mode == RoleMode.RESPECT_READER) {
                    VoiceCatalog.resolve(pool, explicit)
                } else {
                    roleAssigner.assign(seg)
                }

                var produced = false
                guard.withLock {
                    engine.generateStreaming(spoken, voice.speakerId, speed) { samples ->
                        if (isStopped() || aborted.get()) return@generateStreaming false
                        val pcm = PcmChunker.floatToPcm16(samples)
                        if (pcm.isEmpty()) return@generateStreaming true
                        produced = true
                        enqueue(pcm)
                        !(isStopped() || aborted.get())
                    }
                }
                if (!produced) {
                    // 字符强清洗重试（AR-§4.8.6；G2P 未映射字符是零音频唯一确定性根因）
                    val cleaned = TextClean.hardClean(spoken)
                    if (cleaned.isNotBlank()) {
                        guard.withLock {
                            engine.generateStreaming(cleaned, voice.speakerId, speed) { samples ->
                                if (isStopped() || aborted.get()) return@generateStreaming false
                                val pcm = PcmChunker.floatToPcm16(samples)
                                if (pcm.isEmpty()) return@generateStreaming true
                                produced = true
                                enqueue(pcm)
                                !(isStopped() || aborted.get())
                            }
                        }
                    }
                }
                if (!produced) {
                    dropUnits++
                    ConfigStore.bumpDropUnit()
                    Log.w(TAG, "WARN|DROP_UNIT|$dropUnits|len=${spoken.length}")
                    if (anyAudio.get()) {
                        // 保段序静音占位（绝不因单元失败中止在途音频，红线 2）
                        enqueue(PcmChunker.silenceMs(150, speed, engine.sampleRate))
                    }
                } else if (seg.kind != SegmentKind.TITLE) {
                    enqueue(PcmChunker.silenceMs(120, speed, engine.sampleRate))
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "synthesize error req=$reqId", t)
        } finally {
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

        sm.onSynthesizeEnd()

        // 接缝换装（ADR-009）：在请求结束窗口完成 swap，绝不在请求中打断
        if (sm.pendingBackendReload && !isStopped()) {
            reloadExecutor.execute { reloadInternal() }
        }

        val sr = engine.sampleRate
        val ttfb = if (firstPushAt.get() > 0L) firstPushAt.get() - t0 else -1L
        val totalMs = System.currentTimeMillis() - t0
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
                callback.error(TextToSpeechErrors.SYNTHESIS)
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
    private fun awaitBackend(isStopped: () -> Boolean): SherpaBackend? {
        backend?.takeIf { it.isReady() }?.let { return it }
        if (sm.state == EngineState.NO_MODEL || sm.state == EngineState.ERROR) return null
        readyLock.withLock {
            val deadline = System.nanoTime() + READY_WAIT_MS * 1_000_000L
            while (true) {
                backend?.takeIf { it.isReady() }?.let { return it }
                if (isStopped()) return null
                if (sm.state == EngineState.NO_MODEL || sm.state == EngineState.ERROR) return null
                val remain = deadline - System.nanoTime()
                if (remain <= 0L) return null
                try {
                    readyCond.await(remain, TimeUnit.NANOSECONDS)
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

/** TextToSpeech 常量镜像（避免引擎模块依赖 framework 常量歧义） */
object TextToSpeechErrors {
    const val SUCCESS = 0
    const val SYNTHESIS = -4
    const val NOT_INSTALLED_YET = -12
}
