package com.kermond.ebook2tts.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.kermond.ebook2tts.core.ModelCatalog
import com.kermond.ebook2tts.core.RouteMode
import com.kermond.ebook2tts.core.OnlineSegmentPlanner
import com.kermond.ebook2tts.core.OnlineSettings
import com.kermond.ebook2tts.core.OnlineVoiceMap
import com.kermond.ebook2tts.core.PcmChunker
import com.kermond.ebook2tts.core.SpeedMapper
import com.kermond.ebook2tts.core.StreamingPcmResampler
import com.kermond.ebook2tts.core.TextAnalyzer
import com.kermond.ebook2tts.core.TextClean
import com.kermond.ebook2tts.core.TtsProviderKind
import com.kermond.ebook2tts.core.VoiceCatalog
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 预览播放器（ADR-006）：合成+播放均在 :tts_service。
 * AudioTrack USAGE_MEDIA + CONTENT_TYPE_SPEECH；焦点 TRANSIENT_MAY_DUCK。
 *
 * 在线试听（请求级接缝，行为同系统朗读路径）：
 * - 在线开启且可用 → MiMo 流式边收边播；任何失败**静默回落本地**并完整重播，
 *   打点 `ONLINE|fallback=local|reason=..`（试听失败不打扰用户）；
 * - **试听绝不改全局旁白**：voiceId 仅作用于本次试听（既有修法，务必保持）；
 * - 在线关闭时本文件新增逻辑一次 MMKV 布尔读即短路（网络、await 零触达）。
 */
class PreviewPlayer(private val context: Context) {

    interface Listener {
        fun onStart(sampleRate: Int)
        fun onProgress(percent: Int)
        fun onDone()
        fun onError(code: Int, message: String)
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
                val explicitVoice = voiceId?.takeIf { it.isNotBlank() }
                if (explicitVoice == null && tryOnlinePreview(text, listener, speed, null, ::isActive)) return@execute
                if (!isActive()) return@execute // 在线失败后用户已停止或重开：不再回落
                val modelId = ConfigStore.modelId()
                val spec = ModelCatalog.byId(modelId)
                val dir = File(context.filesDir, "models/${spec.id}")
                val borrowed = CoordinatorHolder.getOrCreate(context).borrowBackend { !isActive() }
                val owned = borrowed == null
                val backend = borrowed
                    ?: SherpaBackend(dir, spec, ConfigStore.threads()).also { it.load() }
                if (!backend.isReady()) {
                    listener.onError(-12, backend.loadError ?: "模型未就绪")
                    if (owned) backend.release()
                    if (sessionSeq.get() == mySeq) playing.set(false)
                    return@execute
                }
                Log.i(TAG, "PREVIEW|backend_ready|borrowed=${!owned}|sr=${backend.sampleRate}")
                val pool = VoiceCatalog.poolForModel(modelId, backend.numSpeakers())
                val voice = VoiceCatalog.resolve(pool, voiceId ?: ConfigStore.narratorVoice())
                CoordinatorHolder.previewActive = true
                var doneSeg = 0
                val tTask = System.currentTimeMillis()
                try {
                    val tTrack = System.currentTimeMillis()
                    val trackOk = startTrack(backend.sampleRate, listener)
                    Log.i(TAG, "PREVIEW|track_open|ok=$trackOk|ms=${System.currentTimeMillis() - tTrack}")
                    val segments = TextAnalyzer.analyze(text)
                    val targetSpeed = speed.coerceIn(SpeedMapper.MIN, SpeedMapper.MAX)
                    for (seg in segments) {
                        if (!isActive()) break
                        val baseText = if (seg.kind == com.kermond.ebook2tts.core.SegmentKind.TITLE) {
                            TextClean.chapterSpeakText(seg.text)
                        } else {
                            seg.text
                        }
                        if (baseText.isBlank()) continue
                        val spoken = com.kermond.ebook2tts.core.NumberReader.speakDigits(baseText)
                        val tSynth = System.currentTimeMillis()
                        var segBytes = 0
                        backend.generateStreamingCached(spoken, voice.speakerId, targetSpeed) { pcm ->
                            if (!isActive()) return@generateStreamingCached false
                            if (pcm.isNotEmpty()) {
                                segBytes += pcm.size
                                var i = 0
                                while (i < pcm.size && isActive()) {
                                    val end = minOf(i + 4096, pcm.size)
                                    track?.write(pcm, i, end - i)
                                    i = end
                                }
                            }
                            isActive()
                        }
                        Log.i(TAG, "PREVIEW|synth|chars=${spoken.length}|bytes=$segBytes|ms=${System.currentTimeMillis() - tSynth}")
                        doneSeg++
                        listener.onProgress((doneSeg * 100) / segments.size.coerceAtLeast(1))
                    }
                } finally {
                    CoordinatorHolder.previewActive = false
                    if (owned) backend.release()
                }
                if (!isActive()) return@execute
                Log.i(TAG, "PREVIEW|segments_done|count=$doneSeg|total_ms=${System.currentTimeMillis() - tTask}")
                runCatching { track?.stop() }
                stop()
                listener.onDone()
            } catch (t: Throwable) {
                Log.e(TAG, "preview failed", t)
                if (sessionSeq.get() == mySeq) stop()
                listener.onError(-4, t.message ?: "preview failed")
            }
        }
    }

    /**
     * 在线试听尝试（请求级接缝；网络 IO 在 OnlineBackend 工作线程）。
     *
     * @return true = 已处理（播放完成 / 用户停止 / 在线失败后用户已停止）；
     *         false = 在线失败且用户仍在播放 → 调用方回落本地**完整重播**（静默）。
     */
    private fun tryOnlinePreview(
        rawText: String,
        listener: Listener,
        speed: Float,
        voiceHint: String?,
        isActive: () -> Boolean,
    ): Boolean {
        val route = resolveOnlineRoute(voiceHint)
        OnlineSelector.hardBlockReason(route.choice)?.let { blockReason ->
            Log.e(TAG, "ONLINE|only_online_blocked|preview_abort|reason=$blockReason")
            playing.set(false)
            listener.onError(-4, "仅在线模式不可用：$blockReason")
            return true
        }
        val choice = route.choice as? BackendChoice.Online ?: return false
        val request = choice.request
        Log.i(
            TAG,
            "ONLINE|chosen=online|provider=${request.providerKind.id}|model=${request.model}" +
                "|voice=${request.voice}|sr=${request.sampleRate}"
        )
        choice.warning?.let { Log.w(TAG, "ONLINE|warn=key_kind_mismatch|$it") }

        // 角色精标输入（ADR-013）：仅支持角色造音色的引擎记录候选输入
        if (request.roleEnabled) {
            runCatching {
                val cands = TextAnalyzer.analyze(rawText).mapNotNull { seg ->
                    seg.speakerHint?.trim()?.takeIf { it.isNotEmpty() }
                }
                if (cands.isNotEmpty()) RoleRegistry.offerExcerpt(rawText, cands)
            }
        }

        // 在线语速（RQ-508 / ADR-014）：若服务端不支持原生语速，则在本机做变速不变调
        val stretchSpeed = speed.coerceIn(0.5f, 2.0f)
        val useStretcher = !request.usesNativeSpeed() && !PcmSpeedStretcher.isNoop(stretchSpeed)
        val stretcher = if (useStretcher) PcmSpeedStretcher(stretchSpeed) else null
        val segments = TextAnalyzer.analyze(rawText)
        var doneSeg = 0
        var trackFailed = false
        var anyPcm = false
        var trackSr = 0
        val designedLogged = HashSet<String>()
        for (seg in segments) {
            if (!isActive()) return true
            val segText = if (seg.kind == com.kermond.ebook2tts.core.SegmentKind.TITLE) {
                TextClean.chapterSpeakText(seg.text)
            } else {
                seg.text
            }
            if (segText.isBlank()) continue
            // 角色音色（RQ-507）：试听同样按角色档案走 voicedesign（可区分即可，允许偏差）
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
            val outcome = OnlineBackendHolder.get().synthesizeChunks(
                request = segRequest,
                text = segText,
                speed = stretchSpeed,
                onChunk = onChunk@{ chunk ->
                    if (!isActive()) return@onChunk false
                    val chunkSr = if (chunk.sampleRate > 0) chunk.sampleRate else request.sampleRate
                    if (track == null) {
                        trackSr = chunkSr
                        if (!startTrack(trackSr, listener)) {
                            trackFailed = true
                            return@onChunk false
                        }
                    }
                    val activeSr = if (trackSr > 0) trackSr else chunkSr
                    val aligned = if (chunkSr != activeSr) {
                        val activeResampler = segResampler ?: StreamingPcmResampler(chunkSr, activeSr).also { segResampler = it }
                        activeResampler.process(chunk.pcm16Mono)
                    } else {
                        chunk.pcm16Mono
                    }
                    if (aligned.isEmpty()) return@onChunk isActive()
                    val pcm = stretcher?.process(aligned, activeSr) ?: aligned
                    if (pcm.isEmpty()) return@onChunk isActive()
                    anyPcm = true
                    runCatching { track?.write(pcm, 0, pcm.size) }
                    isActive()
                },
                isCancelled = { !isActive() },
            )
            when (outcome) {
                is OnlineOutcome.Ok -> {
                    if (ConfigStore.onlineLastError().isNotEmpty()) {
                        ConfigStore.setOnlineLastError("")
                        ConfigStore.notifyStatus(context, "ONLINE_OK")
                    }
                    val tailResampled = segResampler?.flush()?.takeIf { it.isNotEmpty() }
                    if (tailResampled != null && isActive()) {
                        val activeSr = if (trackSr > 0) trackSr else request.sampleRate
                        val pcm = stretcher?.process(tailResampled, activeSr) ?: tailResampled
                        if (pcm.isNotEmpty()) runCatching { track?.write(pcm, 0, pcm.size) }
                    }
                    doneSeg++
                    listener.onProgress((doneSeg * 100) / segments.size.coerceAtLeast(1))
                }
                OnlineOutcome.Cancelled -> {
                    if (trackFailed) listener.onError(-4, "音频输出初始化失败")
                    return true
                }
                is OnlineOutcome.Failed -> {
                    ConfigStore.setOnlineLastError(outcome.summary)
                    ConfigStore.notifyStatus(context, "ONLINE_ERR")
                    if (!route.routeMode.allowsLocal) {
                        Log.e(
                            TAG,
                            "ONLINE|only_online_failed|preview|reason=${outcome.reason}" +
                                "|summary=${outcome.summary.take(160)}"
                        )
                        releaseTrack()
                        abandonFocus()
                        playing.set(false)
                        listener.onError(-4, "在线合成失败：${outcome.summary.take(120)}")
                        return true
                    }
                    Log.w(
                        TAG,
                        "ONLINE|fallback=local|reason=${outcome.reason}" +
                            "|summary=${outcome.summary.take(160)}" +
                            if (anyPcm) "|restart=local_full" else ""
                    )
                    releaseTrack()
                    abandonFocus()
                    return false
                }
            }
        }
        if (!isActive()) return true
        runCatching {
            // 榨出伸缩器残余样本（在线试听末段不截断）
            stretcher?.end()?.takeIf { it.isNotEmpty() }?.let { tail ->
                track?.write(tail, 0, tail.size)
            }
            track?.stop()
        }
        releaseTrack()
        abandonFocus()
        playing.set(false)
        listener.onDone()
        return true
    }

    /**
     * 请求级在线选择（同 SynthesisCoordinator.selectOnline；在线关闭 → 单次 MMKV 布尔读后短路）。
     */
    /**
     * 计算本次请求应使用的在线音色（IM-517）：
     * 请求音色/旁白 → 本地音色元数据（语言+性别）→ MiMo 预置音色。
     * 仅读 MMKV 与静态音色表，不做任何模型加载，可在请求线程安全调用。
     */
    private fun mappedOnlineVoice(voiceHint: String?): String {
        val pool = VoiceCatalog.poolForModel(ConfigStore.modelId())
        val narrator = ConfigStore.narratorVoice()
        val fallback = OnlineVoiceMap.genderOf(narrator, pool)
        val requested = voiceHint?.takeIf { VoiceCatalog.isValid(pool, it) } ?: narrator
        val mapped = OnlineVoiceMap.pick(requested, pool, fallback)
        val from = if (voiceHint != null && requested != voiceHint) "$voiceHint(未识别→旁白)" else requested
        Log.i(TAG, "ONLINE|voice_map|from=$from|gender=${OnlineVoiceMap.genderOf(requested, pool)}|to=$mapped")
        return mapped
    }

    private data class OnlineRoute(val routeMode: RouteMode, val choice: BackendChoice)

    private fun resolveOnlineRoute(voiceHint: String?): OnlineRoute {
        // RQ-513 路由模式：仅本地 ⇒ 不触网
        val routeMode = ConfigStore.routeMode()
        if (routeMode == RouteMode.ONLY_LOCAL) {
            Log.i(TAG, "ROUTE|only_local|skip_online")
            return OnlineRoute(routeMode, BackendChoice.Local("only_local"))
        }
        val localAvailable = ModelPresence.isActiveModelInstalled(context)
        if (routeMode == RouteMode.PREFER_LOCAL && localAvailable) {
            return OnlineRoute(routeMode, BackendChoice.Local("prefer_local"))
        }
        if (!ConfigStore.onlineEnabled()) {
            val choice = if (routeMode == RouteMode.ONLY_ONLINE) {
                BackendChoice.Local("${OnlineSelector.ONLY_ONLINE_BLOCKED_PREFIX}online_disabled")
            } else {
                BackendChoice.Local("online_disabled")
            }
            return OnlineRoute(routeMode, choice)
        }
        // 角色音色（RQ-507）：请求级一次性快照（仅支持角色造音色的引擎启用）
        val providerKind = ConfigStore.onlineProviderKind()
        val roleEnabled = providerKind.supportsRoleVoiceDesign && ConfigStore.roleVoiceEnabled()
        val roles = if (roleEnabled) RoleRegistry.snapshot() else emptyMap()
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
        return OnlineRoute(routeMode, choice)
    }

    /** 建立播放轨（焦点 + onStart 通知 + AudioTrack 创建并起播）；返回轨道是否就绪 */
    private fun startTrack(sr: Int, listener: Listener): Boolean {
        requestFocus()
        listener.onStart(sr)
        val minBuf = AudioTrack.getMinBufferSize(
            sr,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
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
                AudioTrack.MODE_STREAM
            )
        }
        val t = track
        t?.play()
        return t != null
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
                        .build()
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
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
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
