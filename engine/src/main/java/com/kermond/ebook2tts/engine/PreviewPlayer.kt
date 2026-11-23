package com.kermond.ebook2tts.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.kermond.ebook2tts.core.OnlineSegmentPlanner
import com.kermond.ebook2tts.core.OnlineSettings
import com.kermond.ebook2tts.core.OnlineVoiceCatalog
import com.kermond.ebook2tts.core.SegmentKind
import com.kermond.ebook2tts.core.SpeedMapper
import com.kermond.ebook2tts.core.StreamingPcmResampler
import com.kermond.ebook2tts.core.TextAnalyzer
import com.kermond.ebook2tts.core.TextClean
import com.kermond.ebook2tts.core.TtsProviderKind
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 预览播放器（运行于 `:tts_service` 进程）：
 * 在线多引擎优先；弱网超时、无网、在线关闭或失败时立即在同一试听流程中无缝回退系统自带 TTS。
 */
class PreviewPlayer(private val context: Context) {

    interface Listener {
        fun onStart(sampleRate: Int)
        fun onProgress(percent: Int)
        fun onDone()
        fun onError(code: Int, message: String)
        fun onStage(stage: String) {}
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor {
        Thread(it, "preview-player")
    }
    private val playing = AtomicBoolean(false)
    private val sessionSeq = AtomicLong(0L)
    private var track: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null

    fun isPlaying(): Boolean = playing.get()

    fun stop() {
        sessionSeq.incrementAndGet()
        playing.set(false)
        releaseTrack()
        abandonFocus()
    }

    fun preview(
        text: String,
        voiceId: String?,
        speed: Float,
        listener: Listener,
    ) {
        stop()
        val mySeq = sessionSeq.incrementAndGet()
        fun isActive(): Boolean = playing.get() && sessionSeq.get() == mySeq
        playing.set(true)
        executor.execute {
            try {
                if (text.isBlank()) {
                    listener.onError(-1, "文本为空")
                    playing.set(false)
                    return@execute
                }
                val providerKind = ConfigStore.onlineProviderKind()
                val roleEnabled = providerKind.supportsRoleVoiceDesign &&
                    ConfigStore.roleMode() != "respectReader" &&
                    ConfigStore.roleVoiceEnabled()
                val roles = if (roleEnabled) RoleRegistry.snapshot() else emptyMap()
                val explicitVoice = voiceId?.takeIf { it.isNotBlank() }
                val onlineVoice = if (providerKind == TtsProviderKind.MIMO) {
                    OnlineVoiceCatalog.resolveMimoVoice(explicitVoice, ConfigStore.onlineVoice())
                } else {
                    ConfigStore.onlineVoice().trim().ifEmpty { providerKind.defaultVoice }
                }

                // 手动试听跳过熔断冷却，用于用户主动验证在线是否恢复
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
                    weakNetworkBypassed = false,
                    timeoutMs = ConfigStore.onlineTimeoutMs(),
                    roleEnabled = roleEnabled,
                    roles = roles,
                    providerKind = providerKind,
                    responseFormat = ConfigStore.onlineResponseFormat(),
                    sampleRate = ConfigStore.onlineSampleRate(),
                    customHeaders = ConfigStore.onlineCustomHeaders(),
                    customBodyTemplate = ConfigStore.onlineCustomBodyTemplate(),
                )

                val request = (choice as? RouteChoice.Online)?.request
                var useOnline = request != null
                var fallbackReason: String? = (choice as? RouteChoice.SystemFallback)?.reason
                var usedOnline = false
                var usedFallback = false

                if (request != null && request.roleEnabled) {
                    runCatching {
                        val cands = TextAnalyzer.analyze(text).mapNotNull { seg ->
                            seg.speakerHint?.trim()?.takeIf { it.isNotEmpty() }
                        }
                        if (cands.isNotEmpty()) RoleRegistry.offerExcerpt(text, cands)
                    }
                }

                val targetSpeed = speed.coerceIn(SpeedMapper.MIN, SpeedMapper.MAX)
                val needOnlineStretch = request != null &&
                    !request.usesNativeSpeed() &&
                    !PcmSpeedStretcher.isNoop(targetSpeed)
                val stretcher = if (needOnlineStretch) PcmSpeedStretcher(targetSpeed) else null
                var stretchedAny = false

                val segments = TextAnalyzer.analyze(text).mapNotNull { seg ->
                    if (seg.kind == SegmentKind.EMPTY) return@mapNotNull null
                    val spoken = if (seg.kind == SegmentKind.TITLE) TextClean.chapterSpeakText(seg.text) else seg.text
                    if (spoken.isBlank()) null else seg to spoken
                }
                if (segments.isEmpty()) {
                    listener.onError(-1, "无可朗读文本")
                    playing.set(false)
                    return@execute
                }

                listener.onStage(if (useOnline) "connecting_online" else "fallback_system")
                val t0 = System.currentTimeMillis()
                var firstChunkAt = 0L
                var trackSr = 0
                var totalFrames = 0L
                var trackFailed = false
                var playingStageSent = false
                var doneSeg = 0
                val designedLogged = HashSet<String>()
                var lastFailureSummary = ""

                fun writePcmAligned(chunk: TtsAudioChunk, activeResampler: StreamingPcmResampler?, stretch: Boolean): StreamingPcmResampler? {
                    if (!isActive() || chunk.pcm16Mono.isEmpty()) return activeResampler
                    val chunkSr = if (chunk.sampleRate > 0) chunk.sampleRate else OnlineSettings.SAMPLE_RATE
                    if (track == null) {
                        trackSr = chunkSr
                        if (!startTrack(trackSr, listener)) {
                            trackFailed = true
                            return activeResampler
                        }
                    }
                    val activeSr = if (trackSr > 0) trackSr else chunkSr
                    val resampler = if (chunkSr != activeSr) {
                        activeResampler ?: StreamingPcmResampler(chunkSr, activeSr)
                    } else {
                        null
                    }
                    val aligned = resampler?.process(chunk.pcm16Mono) ?: chunk.pcm16Mono
                    if (aligned.isEmpty()) return resampler
                    if (!stretch && stretchedAny) {
                        stretchedAny = false
                        stretcher?.end()?.takeIf { it.isNotEmpty() }?.let { tail ->
                            totalFrames += tail.size / 2L
                            runCatching { track?.write(tail, 0, tail.size) }
                        }
                    }
                    val pcm = if (stretch && stretcher != null) {
                        stretchedAny = true
                        stretcher.process(aligned, activeSr)
                    } else {
                        aligned
                    }
                    if (pcm.isEmpty()) return resampler
                    if (firstChunkAt == 0L) firstChunkAt = System.currentTimeMillis()
                    if (!playingStageSent) {
                        playingStageSent = true
                        listener.onStage("playing")
                    }
                    totalFrames += pcm.size / 2L
                    var off = 0
                    while (off < pcm.size && isActive()) {
                        val end = minOf(off + 4096, pcm.size)
                        runCatching { track?.write(pcm, off, end - off) }
                        off = end
                    }
                    return resampler
                }

                for ((seg, segText) in segments) {
                    if (!isActive()) return@execute
                    var segDone = false

                    if (useOnline && request != null) {
                        val roleName = seg.speakerHint?.trim()?.takeIf { it.isNotEmpty() }
                        val plan = if (request.providerKind.supportsRoleVoiceDesign) {
                            OnlineSegmentPlanner.plan(
                                baseModel = request.model,
                                baseVoice = request.voice,
                                baseStyle = request.style,
                                roleEnabled = request.roleEnabled,
                                roleName = roleName,
                                design = roleName?.let { request.roles[it] },
                            )
                        } else {
                            null
                        }
                        if (plan?.useDesign == true && roleName != null && designedLogged.add(roleName)) {
                            Log.i(TAG, "ONLINE|role=$roleName|design=1|model=${plan.model}|preview=1")
                        }
                        val segRequest = plan?.let { request.forSegment(it) } ?: request
                        var segResampler: StreamingPcmResampler? = null
                        val synthesizingNotified = AtomicBoolean(false)
                        val outcome = OnlineBackendHolder.get().synthesizeChunks(
                            request = segRequest,
                            text = segText,
                            speed = targetSpeed,
                            onChunk = { chunk ->
                                if (synthesizingNotified.compareAndSet(false, true)) {
                                    listener.onStage("synthesizing")
                                }
                                segResampler = writePcmAligned(chunk, segResampler, stretch = needOnlineStretch)
                                isActive() && !trackFailed
                            },
                            isCancelled = { !isActive() || trackFailed },
                        )
                        when (outcome) {
                            is OnlineOutcome.Ok -> {
                                segResampler?.flush()?.takeIf { it.isNotEmpty() }?.let { tail ->
                                    val activeSr = if (trackSr > 0) trackSr else request.sampleRate
                                    val pcm = if (needOnlineStretch && stretcher != null) {
                                        stretcher.process(tail, activeSr)
                                    } else {
                                        tail
                                    }
                                    if (pcm.isNotEmpty() && isActive()) {
                                        totalFrames += pcm.size / 2L
                                        runCatching { track?.write(pcm, 0, pcm.size) }
                                    }
                                }
                                usedOnline = true
                                segDone = true
                                CoordinatorHolder.getOrCreate(context).resetWeakNetBreaker()
                                if (ConfigStore.onlineLastError().isNotEmpty()) {
                                    ConfigStore.setOnlineLastError("")
                                    ConfigStore.notifyStatus(context, "ONLINE_OK")
                                }
                            }
                            OnlineOutcome.Cancelled -> {
                                if (trackFailed) {
                                    stop()
                                    listener.onError(-4, "音频输出初始化失败")
                                }
                                return@execute
                            }
                            is OnlineOutcome.Failed -> {
                                ConfigStore.setOnlineLastError(outcome.summary)
                                ConfigStore.notifyStatus(context, "ONLINE_ERR")
                                lastFailureSummary = outcome.summary
                                fallbackReason = outcome.reason
                                useOnline = false
                                listener.onStage("fallback_system")
                                Log.w(
                                    TAG,
                                    "PREVIEW|fallback=system_tts|reason=${outcome.reason}|summary=${outcome.summary.take(140)}"
                                )
                            }
                        }
                    }

                    if (!segDone && !useOnline && isActive()) {
                        var sysResampler: StreamingPcmResampler? = null
                        val sysOutcome = SystemTtsBackendHolder.get(context).synthesize(
                            text = segText,
                            speed = targetSpeed,
                            onChunk = { chunk ->
                                sysResampler = writePcmAligned(chunk, sysResampler, stretch = false)
                                isActive() && !trackFailed
                            },
                            isCancelled = { !isActive() || trackFailed },
                        )
                        when (sysOutcome) {
                            is SystemTtsOutcome.Ok -> {
                                sysResampler?.flush()?.takeIf { it.isNotEmpty() }?.let { tail ->
                                    if (isActive()) {
                                        totalFrames += tail.size / 2L
                                        runCatching { track?.write(tail, 0, tail.size) }
                                    }
                                }
                                segDone = true
                                usedFallback = true
                                ConfigStore.recordFallback(
                                    sysOutcome.engineLabel,
                                    fallbackReason ?: "system_tts",
                                )
                                ConfigStore.notifyStatus(
                                    context,
                                    "SYSTEM_FALLBACK|${sysOutcome.engineLabel}|${fallbackReason ?: "system_tts"}",
                                )
                            }
                            SystemTtsOutcome.Cancelled -> {
                                if (trackFailed) {
                                    stop()
                                    listener.onError(-4, "音频输出初始化失败")
                                }
                                return@execute
                            }
                            is SystemTtsOutcome.Failed -> {
                                lastFailureSummary = listOf(lastFailureSummary, sysOutcome.summary)
                                    .filter { it.isNotBlank() }
                                    .joinToString("；")
                            }
                        }
                    }

                    doneSeg++
                    listener.onProgress((doneSeg * 100) / segments.size.coerceAtLeast(1))
                }

                if (!isActive()) return@execute
                if (totalFrames <= 0L) {
                    val errMsg = lastFailureSummary.ifBlank { "在线合成与系统 TTS 兜底均未生成有效音频" }
                    if (sessionSeq.get() == mySeq) stop()
                    listener.onError(-4, errMsg)
                    return@execute
                }
                if (stretchedAny) {
                    runCatching {
                        stretcher?.end()?.takeIf { it.isNotEmpty() }?.let { tail ->
                            totalFrames += tail.size / 2L
                            track?.write(tail, 0, tail.size)
                        }
                    }
                }
                val ttfb = if (firstChunkAt > 0L) firstChunkAt - t0 else -1L
                val totalMs = System.currentTimeMillis() - t0
                val backendTag = when {
                    usedOnline && usedFallback -> "online+system_tts"
                    usedFallback -> "system_tts"
                    else -> "online"
                }
                ConfigStore.recordLastPerf(ttfb, totalMs, 0, backendTag)
                ConfigStore.notifyStatus(context, "PERF")

                drainTrack(totalFrames, if (trackSr > 0) trackSr else OnlineSettings.SAMPLE_RATE, ::isActive)
                if (!isActive()) return@execute
                stop()
                listener.onDone()
            } catch (t: Throwable) {
                Log.e(TAG, "preview failed", t)
                if (sessionSeq.get() == mySeq) stop()
                listener.onError(-4, t.message ?: "preview failed")
            }
        }
    }

    private fun startTrack(sr: Int, listener: Listener): Boolean {
        requestFocus()
        listener.onStart(sr)
        val minBuf = AudioTrack.getMinBufferSize(
            sr,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufBytes = (if (minBuf > 0) minBuf else 4096) * 2
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sr)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        track = if (Build.VERSION.SDK_INT >= 23) {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufBytes)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                sr,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufBytes,
                AudioTrack.MODE_STREAM,
            )
        }
        val t = track
        t?.play()
        return t != null
    }

    private fun drainTrack(totalFrames: Long, sampleRate: Int, isActive: () -> Boolean) {
        val t = track ?: return
        if (totalFrames <= 0L) return
        runCatching { t.stop() }
        val sr = sampleRate.coerceAtLeast(8000)
        val maxWaitMs = ((totalFrames * 1000L) / sr + 800L).coerceIn(400L, 15_000L)
        val deadline = android.os.SystemClock.uptimeMillis() + maxWaitMs
        while (isActive() && android.os.SystemClock.uptimeMillis() < deadline) {
            val head = runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(totalFrames)
            if (head >= totalFrames) break
            try {
                Thread.sleep(25L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    private fun releaseTrack() {
        runCatching {
            track?.pause()
            track?.flush()
            track?.stop()
            track?.release()
        }
        track = null
    }

    private fun requestFocus() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    ) {
                        stop()
                    }
                }
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    ) {
                        stop()
                    }
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }
    }

    private fun abandonFocus() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
        focusRequest = null
    }

    companion object {
        private const val TAG = "PreviewPlayer"
    }
}
