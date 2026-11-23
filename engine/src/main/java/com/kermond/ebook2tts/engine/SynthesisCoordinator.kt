package com.kermond.ebook2tts.engine

import android.content.Context
import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.TextToSpeech
import android.util.Log
import com.kermond.ebook2tts.core.OnlineSegmentPlanner
import com.kermond.ebook2tts.core.OnlineSettings
import com.kermond.ebook2tts.core.OnlineVoiceCatalog
import com.kermond.ebook2tts.core.PcmChunker
import com.kermond.ebook2tts.core.ProcessNames
import com.kermond.ebook2tts.core.SegmentKind
import com.kermond.ebook2tts.core.SpeedMapper
import com.kermond.ebook2tts.core.StreamingPcmResampler
import com.kermond.ebook2tts.core.TextAnalyzer
import com.kermond.ebook2tts.core.TextClean
import com.kermond.ebook2tts.core.TextSegment
import com.kermond.ebook2tts.core.TtsProviderKind
import com.kermond.ebook2tts.core.WeakNetworkBreaker
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 合成协调器（单一模式：在线多引擎优先 + 弱网超时自动回退系统自带 TTS）。
 *
 * 设计要点：
 * - 真流式：在线块或系统 TTS 块进入有界队列 → 独立推送线程实时 `callback.audioAvailable`；
 * - 弱网防卡顿：在线单段命中首包超时/网络错误 → 立即取消预取、记入弱网熔断器，
 *   并在本请求内无缝切换到设备系统自带 TTS 合成当前失败段与后续段；
 * - 采样率自动对齐：若已有音频入队后切换至不同采样率的系统 TTS，通过 [StreamingPcmResampler] 实时重采样；
 * - 仅在 `:tts_service` 进程使用。
 */
class SynthesisCoordinator(
    private val context: Context,
) {
    companion object {
        private const val TAG = "SynthCoord"
        private const val MAX_AUDIO_BYTES = 8192
        private const val QUEUE_CAPACITY = 16
        private const val STOP_GRACE_MS = 150L
        private val END = AudioBlock(ByteArray(0), false)
    }

    private class AudioBlock(val pcm: ByteArray, val stretch: Boolean)

    private val reqSeq = AtomicInteger(0)
    private val weakNetBreaker = WeakNetworkBreaker()
    private val systemTts: SystemTtsBackend = SystemTtsBackendHolder.get(context)

    @Volatile
    private var stopAtMs = 0L

    @Volatile
    private var destroyed = false

    private val pushExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tts-push").apply { isDaemon = true }
    }
    private val prefetchExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tts-prefetch").apply { isDaemon = true }
    }

    fun requestStop() {
        stopAtMs = android.os.SystemClock.uptimeMillis()
    }

    fun resetWeakNetBreaker() {
        weakNetBreaker.reset()
    }

    fun reloadConfig(reason: String) {
        Log.i(TAG, "reload config reason=$reason")
        weakNetBreaker.reset()
        systemTts.invalidateIfEngineChanged(ConfigStore.fallbackTtsEngine())
    }

    fun shutdown() {
        destroyed = true
        pushExecutor.shutdownNow()
        prefetchExecutor.shutdownNow()
        runCatching { systemTts.shutdown() }
    }

    data class SynthResult(
        val started: Boolean,
        val error: Boolean,
        val aborted: Boolean,
        val sampleRate: Int,
    )

    fun synthesize(
        rawText: String?,
        speechRateAosp: Int,
        explicitVoiceName: String?,
        callback: SynthesisCallback,
    ): SynthResult {
        val reqId = reqSeq.incrementAndGet()
        val startedAtMs = android.os.SystemClock.uptimeMillis()
        fun isStopped(): Boolean =
            destroyed || (stopAtMs - startedAtMs >= STOP_GRACE_MS)
        val t0 = System.currentTimeMillis()

        if (!isEngineProcess) {
            Log.e(TAG, "ABORT|INFERENCE_OUTSIDE_ENGINE_PROCESS|req=$reqId")
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
            callback.done()
            return SynthResult(false, true, false, OnlineSettings.SAMPLE_RATE)
        }

        val text = rawText ?: ""
        if (text.isBlank()) {
            callback.start(OnlineSettings.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return SynthResult(true, false, false, OnlineSettings.SAMPLE_RATE)
        }

        val providerKind = ConfigStore.onlineProviderKind()
        val roleEnabled = providerKind.supportsRoleVoiceDesign &&
            ConfigStore.roleMode() != "respectReader" &&
            ConfigStore.roleVoiceEnabled()
        val roles = if (roleEnabled) RoleRegistry.snapshot() else emptyMap()
        val onlineVoice = if (providerKind == TtsProviderKind.MIMO) {
            OnlineVoiceCatalog.resolveMimoVoice(explicitVoiceName, ConfigStore.onlineVoice())
        } else {
            ConfigStore.onlineVoice().trim().ifEmpty { providerKind.defaultVoice }
        }
        val bypassed = weakNetBreaker.shouldBypassOnline()
        val choice = OnlineSelector.select(
            onlineEnabled = ConfigStore.onlineEnabled(),
            apiKey = ConfigStore.onlineApiKey(),
            keyKind = ConfigStore.onlineKeyKind(),
            baseUrl = ConfigStore.onlineBaseUrl(),
            model = ConfigStore.onlineModel(),
            voice = onlineVoice,
            style = ConfigStore.onlineStyle(),
            tokenPlanAccepted = ConfigStore.onlineTokenPlanAccepted(),
            allowMobileData = ConfigStore.netAllowMobileData(),
            onCellular = NetState.onCellular(context),
            networkConnected = NetState.isConnected(context),
            weakNetworkBypassed = bypassed,
            timeoutMs = ConfigStore.onlineTimeoutMs(),
            roleEnabled = roleEnabled,
            roles = roles,
            providerKind = providerKind,
            responseFormat = ConfigStore.onlineResponseFormat(),
            sampleRate = ConfigStore.onlineSampleRate(),
            customHeaders = ConfigStore.onlineCustomHeaders(),
            customBodyTemplate = ConfigStore.onlineCustomBodyTemplate(),
        )

        val onlineChoice = choice as? RouteChoice.Online
        val onlineRequest = onlineChoice?.request
        var fallbackReason: String? = (choice as? RouteChoice.SystemFallback)?.reason
        var useOnline = onlineRequest != null
        if (onlineRequest != null) {
            Log.i(
                TAG,
                "ONLINE|chosen=online|provider=${onlineRequest.providerKind.id}|model=${onlineRequest.model}" +
                    "|voice=${onlineRequest.voice}|timeoutMs=${onlineRequest.timeoutMs}"
            )
            onlineChoice.warning?.let { Log.w(TAG, "ONLINE|warn=key_kind_mismatch|$it") }
        } else {
            Log.i(TAG, "ONLINE|fallback=system_tts|reason=$fallbackReason")
        }

        val initialSr = onlineRequest?.sampleRate?.takeIf { it > 0 } ?: OnlineSettings.SAMPLE_RATE
        val speed = SpeedMapper.resolveSpeed(
            speechRateAosp = speechRateAosp,
            appSpeed = ConfigStore.speedValue(),
            speedMode = ConfigStore.speedMode(),
        )

        val segments = TextAnalyzer.analyze(text)
        val units = segments.mapNotNull { seg ->
            if (seg.kind == SegmentKind.EMPTY) return@mapNotNull null
            val spoken = if (seg.kind == SegmentKind.TITLE) TextClean.chapterSpeakText(seg.text) else seg.text
            if (spoken.isBlank()) null else seg to spoken
        }

        val queue = ArrayBlockingQueue<AudioBlock>(QUEUE_CAPACITY)
        val started = AtomicBoolean(false)
        val anyAudio = AtomicBoolean(false)
        val audioCommitted = AtomicBoolean(false)
        val aborted = AtomicBoolean(false)
        val chunksPushed = AtomicInteger(0)
        val bytesPushed = AtomicLong(0L)
        val firstPushAt = AtomicLong(0L)
        val streamSr = AtomicInteger(initialSr)

        var usedOnlineAudio = false
        var usedSystemAudio = false
        var dropUnits = 0
        var onlineTtfbLogged = false

        val needOnlineStretch = onlineRequest != null &&
            !onlineRequest.usesNativeSpeed() &&
            !PcmSpeedStretcher.isNoop(speed)

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
                        if (rc != TextToSpeech.SUCCESS) {
                            aborted.set(true)
                            return false
                        }
                        started.set(true)
                    }
                    val end = minOf(i + MAX_AUDIO_BYTES, pcm.size)
                    val slice = if (i == 0 && end == pcm.size) pcm else pcm.copyOfRange(i, end)
                    val rc = callback.audioAvailable(slice, 0, slice.size)
                    if (rc != TextToSpeech.SUCCESS) {
                        aborted.set(true)
                        return false
                    }
                    if (!anyAudio.getAndSet(true)) firstPushAt.set(System.currentTimeMillis())
                    chunksPushed.incrementAndGet()
                    bytesPushed.addAndGet(slice.size.toLong())
                    i = end
                } catch (e: Exception) {
                    Log.e(TAG, "ABORT|IPC_PUSH_FAIL|req=$reqId", e)
                    aborted.set(true)
                    return false
                }
            }
            return true
        }

        val pusher = pushExecutor.submit {
            var held: ByteArray? = null
            var firstPending = true
            var stretchedAny = false
            val stretcher = PcmSpeedStretcher(speed)

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
                    if (processed.isEmpty()) continue
                    held?.let { if (!pushOne(it, isLast = false)) return@submit }
                    held = processed
                }
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

        fun enqueue(pcm: ByteArray, stretch: Boolean) {
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
        val candidatesSeen = LinkedHashSet<String>()

        fun buildSegmentOnlineRequest(
            seg: TextSegment,
            base: OnlineRequest,
        ): OnlineRequest {
            val roleName = seg.speakerHint?.trim()?.takeIf { it.isNotEmpty() }
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

        fun emitChunkAligned(
            chunk: TtsAudioChunk,
            activeResampler: StreamingPcmResampler?,
            stretch: Boolean,
        ): StreamingPcmResampler? {
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
                enqueue(pcm, stretch = stretch)
            }
            return resampler
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

        fun startPrefetchIfNeeded(nextIdx: Int, baseReq: OnlineRequest) {
            if (!useOnline || nextIdx >= units.size || pendingPrefetchIdx == nextIdx) return
            if (isStopped() || aborted.get()) return
            val (nextSeg, nextSpoken) = units[nextIdx]
            val nextReq = buildSegmentOnlineRequest(nextSeg, baseReq)
            val pfCancel = AtomicBoolean(false)
            val fut = runCatching {
                prefetchExecutor.submit(Callable {
                    val collected = ArrayList<TtsAudioChunk>()
                    val out = OnlineBackendHolder.get().synthesizeChunks(
                        request = nextReq,
                        text = nextSpoken,
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

        try {
            for (idx in units.indices) {
                if (isStopped() || aborted.get()) break
                val (seg, spoken) = units[idx]
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

                    val outcome = if (prefetched != null) {
                        if (prefetched.outcome is OnlineOutcome.Ok) {
                            startPrefetchIfNeeded(idx + 1, onlineRequest)
                            var pfResampler: StreamingPcmResampler? = null
                            for (c in prefetched.chunks) {
                                if (isStopped() || aborted.get()) break
                                pfResampler = emitChunkAligned(c, pfResampler, stretch = needOnlineStretch)
                            }
                            pfResampler?.flush()?.takeIf { it.isNotEmpty() }?.let { tail ->
                                if (!isStopped() && !aborted.get()) enqueue(tail, stretch = needOnlineStretch)
                            }
                        }
                        prefetched.outcome
                    } else {
                        val segRequest = buildSegmentOnlineRequest(seg, onlineRequest)
                        var segResampler: StreamingPcmResampler? = null
                        val prefetchDispatched = AtomicBoolean(false)
                        val out = OnlineBackendHolder.get().synthesizeChunks(
                            request = segRequest,
                            text = spoken,
                            speed = speed,
                            onChunk = onChunk@{ chunk ->
                                if (isStopped() || aborted.get()) return@onChunk false
                                if (chunk.pcm16Mono.isNotEmpty() && prefetchDispatched.compareAndSet(false, true)) {
                                    startPrefetchIfNeeded(idx + 1, onlineRequest)
                                }
                                segResampler = emitChunkAligned(chunk, segResampler, stretch = needOnlineStretch)
                                !(isStopped() || aborted.get())
                            },
                            isCancelled = { isStopped() || aborted.get() },
                        )
                        if (out is OnlineOutcome.Ok) {
                            segResampler?.flush()?.takeIf { it.isNotEmpty() }?.let { tail ->
                                if (!isStopped() && !aborted.get()) enqueue(tail, stretch = needOnlineStretch)
                            }
                        }
                        out
                    }

                    when (outcome) {
                        is OnlineOutcome.Ok -> {
                            produced = true
                            usedOnlineAudio = true
                            weakNetBreaker.recordSuccess()
                            if (ConfigStore.onlineLastError().isNotEmpty()) {
                                ConfigStore.setOnlineLastError("")
                                ConfigStore.notifyStatus(context, "ONLINE_OK")
                            }
                            if (!onlineTtfbLogged && outcome.ttfbMs >= 0L) {
                                onlineTtfbLogged = true
                                Log.i(TAG, "ONLINE|ttfb_ms=${outcome.ttfbMs}")
                            }
                        }
                        OnlineOutcome.Cancelled -> Unit
                        is OnlineOutcome.Failed -> {
                            cancelPrefetch()
                            ConfigStore.setOnlineLastError(outcome.summary)
                            if (outcome.kind == OnlineFailureKind.TIMEOUT || outcome.kind == OnlineFailureKind.NETWORK) {
                                weakNetBreaker.recordFailure()
                            }
                            useOnline = false
                            fallbackReason = outcome.reason
                            Log.w(
                                TAG,
                                "ONLINE|fallback=system_tts|reason=${outcome.reason}|summary=${outcome.summary.take(160)}"
                            )
                            ConfigStore.notifyStatus(context, "ONLINE_ERR")
                        }
                    }
                }

                if (!produced && !useOnline && !isStopped() && !aborted.get()) {
                    var sysResampler: StreamingPcmResampler? = null
                    val sysOutcome = systemTts.synthesize(
                        text = spoken,
                        speed = speed,
                        onChunk = onChunk@{ chunk ->
                            if (isStopped() || aborted.get()) return@onChunk false
                            sysResampler = emitChunkAligned(chunk, sysResampler, stretch = false)
                            !(isStopped() || aborted.get())
                        },
                        isCancelled = { isStopped() || aborted.get() },
                    )
                    when (sysOutcome) {
                        is SystemTtsOutcome.Ok -> {
                            sysResampler?.flush()?.takeIf { it.isNotEmpty() }?.let { tail ->
                                if (!isStopped() && !aborted.get()) enqueue(tail, stretch = false)
                            }
                            produced = true
                            usedSystemAudio = true
                            ConfigStore.recordFallback(
                                sysOutcome.engineLabel,
                                fallbackReason ?: "system_tts",
                            )
                            ConfigStore.notifyStatus(
                                context,
                                "SYSTEM_FALLBACK|${sysOutcome.engineLabel}|${fallbackReason ?: "system_tts"}",
                            )
                        }
                        SystemTtsOutcome.Cancelled -> Unit
                        is SystemTtsOutcome.Failed -> {
                            Log.w(TAG, "SYSTEM_TTS|failed|reason=${sysOutcome.reason}|${sysOutcome.summary}")
                        }
                    }
                }

                val srPause = streamSr.get()
                if (!produced) {
                    dropUnits++
                    ConfigStore.bumpDropUnit()
                    Log.w(TAG, "WARN|DROP_UNIT|$dropUnits|len=${spoken.length}")
                    if (anyAudio.get()) {
                        enqueue(PcmChunker.silenceMs(150, speed, srPause), stretch = false)
                    }
                } else if (seg.kind != SegmentKind.TITLE) {
                    enqueue(PcmChunker.silenceMs(120, speed, srPause), stretch = false)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "synthesize error req=$reqId", t)
        } finally {
            cancelPrefetch()
            if (onlineRequest?.roleEnabled == true && candidatesSeen.isNotEmpty()) {
                RoleRegistry.offerExcerpt(text, candidatesSeen)
            }
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
            runCatching { pusher.get(60_000L, TimeUnit.MILLISECONDS) }
                .onFailure { pusher.cancel(true) }
        }

        val sr = streamSr.get()
        val ttfb = if (firstPushAt.get() > 0L) firstPushAt.get() - t0 else -1L
        val totalMs = System.currentTimeMillis() - t0
        val backendTag = when {
            usedOnlineAudio && usedSystemAudio -> "online+system_tts"
            usedSystemAudio -> "system_tts"
            else -> "online"
        }
        if (anyAudio.get()) {
            ConfigStore.recordLastPerf(ttfb, totalMs, dropUnits, backendTag)
            ConfigStore.notifyStatus(context, "PERF")
        }
        Log.i(
            TAG,
            "PERF|req=$reqId|backend=$backendTag|segs=${segments.size}|chunks=${chunksPushed.get()}" +
                "|ttfb_ms=$ttfb|total_ms=$totalMs|drop=$dropUnits"
        )

        if (isStopped() && !started.get()) {
            return SynthResult(false, false, true, sr)
        }
        if (!started.get()) {
            if (text.isNotBlank() && units.isNotEmpty()) {
                callback.error(TextToSpeech.ERROR_SYNTHESIS)
                callback.done()
                return SynthResult(false, true, false, sr)
            }
            callback.start(sr, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return SynthResult(true, false, false, sr)
        }
        callback.done()
        return SynthResult(true, false, aborted.get(), sr)
    }

    private val isEngineProcess: Boolean by lazy {
        val cmdline = runCatching { File("/proc/self/cmdline").readBytes() }.getOrNull()
        ProcessNames.isEngineProcess(ProcessNames.parseCmdline(cmdline))
    }
}
