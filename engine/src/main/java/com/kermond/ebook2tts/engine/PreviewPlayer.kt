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
import com.kermond.ebook2tts.core.OnlineSegmentPlanner
import com.kermond.ebook2tts.core.OnlineSettings
import com.kermond.ebook2tts.core.OnlineVoiceMap
import com.kermond.ebook2tts.core.PcmChunker
import com.kermond.ebook2tts.core.SpeedMapper
import com.kermond.ebook2tts.core.TextAnalyzer
import com.kermond.ebook2tts.core.TextClean
import com.kermond.ebook2tts.core.VoiceCatalog
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
    private var track: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null

    fun isPlaying(): Boolean = playing.get()

    fun stop() {
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
        playing.set(true)
        executor.execute {
            try {
                val cleaned = TextClean.normalize(text)
                if (cleaned.isBlank()) {
                    listener.onError(-1, "文本为空")
                    playing.set(false)
                    return@execute
                }
                // 试听语义分离（P6 / IM-518）：
                // - voiceId != null ⇒「试听某个本地音色」（音色库场景）⇒ **强制本地合成**，
                //   因为在线模型只有 8 个预置音色，用它"试听本地音色"毫无意义（甲方 P0 报障①）；
                // - voiceId == null ⇒「跟随旁白朗读」（示例朗读场景）⇒ 允许在线，在线音色按旁白性别映射（IM-517）。
                val explicitVoice = voiceId?.takeIf { it.isNotBlank() }
                if (explicitVoice == null && tryOnlinePreview(cleaned, listener, speed, null)) return@execute
                if (!playing.get()) return@execute // 在线失败后用户已停止：不再回落
                val modelId = ConfigStore.modelId()
                val spec = ModelCatalog.byId(modelId)
                val dir = File(context.filesDir, "models/${spec.id}")
                // ── P7 / R1 + R3（BUG-P7-015）：优先**借用**协调器的常驻后端，且用后不释放 ──
                // 原实现每次试听都 new + load() + release()：真机（SDM845）实测单次整模型加载 12.2 s，
                // 连续第二次试听总耗时 80.0 s。共享后这些代价只在服务首启（预热）发生一次。
                // 回落条件：协调器不存在（例如进程被 PreviewService 单独拉起、TTS 服务尚未创建）。
                // 回落条件：仅在协调器创建失败等异常情形下才自建实例（正常路径不会走到）
                val borrowed = CoordinatorHolder.getOrCreate(context).borrowBackend { !playing.get() }
                val owned = borrowed == null
                val backend = borrowed
                    ?: SherpaBackend(dir, spec, ConfigStore.threads()).also { it.load() }
                if (!backend.isReady()) {
                    listener.onError(-12, backend.loadError ?: "模型未就绪")
                    if (owned) backend.release() // 仅自有实例才释放；借用实例的释放权归协调器
                    playing.set(false)
                    return@execute
                }
                Log.i(TAG, "PREVIEW|backend_ready|borrowed=${!owned}|sr=${backend.sampleRate}")
                val pool = VoiceCatalog.poolForModel(modelId, backend.numSpeakers())
                val voice = VoiceCatalog.resolve(pool, voiceId ?: ConfigStore.narratorVoice())
                // P7 插桩（BUG-P7-016）：定位"首个请求 playing 之后迟迟不结束"的阻塞点
                val tTask = System.currentTimeMillis()
                val tTrack = System.currentTimeMillis()
                val trackOk = startTrack(backend.sampleRate, listener)
                Log.i(TAG, "PREVIEW|track_open|ok=$trackOk|ms=${System.currentTimeMillis() - tTrack}")
                val segments = TextAnalyzer.analyze(cleaned)
                var doneSeg = 0
                for (seg in segments) {
                    if (!playing.get()) break
                    if (seg.text.isBlank()) continue
                    val spoken = seg.text
                    val tSynth = System.currentTimeMillis()
                    val pcm = backend.generatePcm(spoken, voice.speakerId, SpeedMapper.actualSpeed(100 * speed.toInt().coerceAtLeast(1), 0f).let {
                        // speed here is already multiplier 0.5..2
                        speed.coerceIn(0.5f, 2.0f)
                    })
                    Log.i(TAG, "PREVIEW|synth|chars=${spoken.length}|bytes=${pcm.size}|ms=${System.currentTimeMillis() - tSynth}")
                    if (pcm.isNotEmpty() && playing.get()) {
                        var i = 0
                        val tWrite = System.currentTimeMillis()
                        while (i < pcm.size && playing.get()) {
                            val end = minOf(i + 4096, pcm.size)
                            track?.write(pcm, i, end - i)
                            i = end
                        }
                        Log.i(TAG, "PREVIEW|write|bytes=$i/${pcm.size}|ms=${System.currentTimeMillis() - tWrite}|playing=${playing.get()}")
                    }
                    doneSeg++
                    listener.onProgress((doneSeg * 100) / segments.size.coerceAtLeast(1))
                }
                // fade out
                Log.i(TAG, "PREVIEW|segments_done|count=$doneSeg|total_ms=${System.currentTimeMillis() - tTask}")
                runCatching { track?.stop() }
                // P7 / R1：仅"自建实例"才在此释放；借用协调器的常驻后端一律不释放
                if (owned) backend.release()
                stop()
                listener.onDone()
            } catch (t: Throwable) {
                Log.e(TAG, "preview failed", t)
                stop()
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
        cleaned: String,
        listener: Listener,
        speed: Float,
        voiceHint: String?,
    ): Boolean {
        val choice = selectOnline(voiceHint) ?: return false
        val request = choice.request
        Log.i(
            TAG,
            "ONLINE|chosen=online|model=${request.model}|voice=${request.voice}" +
                "|sr=${OnlineSettings.SAMPLE_RATE}"
        )
        choice.warning?.let { Log.w(TAG, "ONLINE|warn=key_kind_mismatch|$it") }

        // 角色精标输入（ADR-013）：试听内容是用户"听到"的正文，同样应作为角色分析输入
        // （否则只有系统朗读路径会积累角色，试听多角色永远走不到 voicedesign）。
        // 注意：offerExcerpt 内部走后台线程 + 限频，本线程零阻塞。
        runCatching {
            val cands = TextAnalyzer.analyze(cleaned).mapNotNull { seg ->
                seg.speakerHint?.trim()?.takeIf { it.isNotEmpty() }
            }
            if (cands.isNotEmpty()) RoleRegistry.offerExcerpt(cleaned, cands)
        }

        // 在线语速（RQ-508 / ADR-014）：试听同样在本机做变速不变调（与系统朗读路径同语义）
        val stretchSpeed = speed.coerceIn(0.5f, 2.0f)
        val stretcher = if (PcmSpeedStretcher.isNoop(stretchSpeed)) null else PcmSpeedStretcher(stretchSpeed)
        val segments = TextAnalyzer.analyze(cleaned)
        var doneSeg = 0
        var trackFailed = false
        var anyPcm = false
        val designedLogged = HashSet<String>()
        for (seg in segments) {
            if (!playing.get()) return true
            if (seg.text.isBlank()) continue
            // 角色音色（RQ-507）：试听同样按角色档案走 voicedesign（可区分即可，允许偏差）
            val roleName = seg.speakerHint?.trim()?.takeIf { it.isNotEmpty() }
            val plan = OnlineSegmentPlanner.plan(
                baseModel = request.model,
                baseVoice = request.voice,
                baseStyle = request.style,
                roleEnabled = request.roleEnabled,
                roleName = roleName,
                design = roleName?.let { request.roles[it] },
            )
            if (plan.useDesign && roleName != null && designedLogged.add(roleName)) {
                Log.i(TAG, "ONLINE|role=$roleName|design=1|model=${plan.model}|preview=1")
            }
            val outcome = OnlineBackendHolder.get().synthesize(
                request = request.forSegment(plan),
                text = seg.text,
                onPcm = onPcm@{ raw ->
                    if (!playing.get()) return@onPcm false
                    if (track == null && !startTrack(OnlineSettings.SAMPLE_RATE, listener)) {
                        trackFailed = true
                        return@onPcm false
                    }
                    val pcm = stretcher?.process(raw, OnlineSettings.SAMPLE_RATE) ?: raw
                    if (pcm.isEmpty()) return@onPcm playing.get()
                    anyPcm = true
                    runCatching { track?.write(pcm, 0, pcm.size) }
                    playing.get()
                },
                isCancelled = { !playing.get() },
            )
            when (outcome) {
                is OnlineOutcome.Ok -> {
                    doneSeg++
                    listener.onProgress((doneSeg * 100) / segments.size.coerceAtLeast(1))
                }
                OnlineOutcome.Cancelled -> {
                    if (trackFailed) listener.onError(-4, "音频输出初始化失败")
                    return true
                }
                is OnlineOutcome.Failed -> {
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
        if (!playing.get()) return true
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

    private fun selectOnline(voiceHint: String?): BackendChoice.Online? {
        if (!ConfigStore.onlineEnabled()) return null
        // 角色音色（RQ-507）：请求级一次性快照（试听同样适用）
        val roleEnabled = ConfigStore.roleVoiceEnabled()
        val roles = if (roleEnabled) RoleRegistry.snapshot() else emptyMap()
        // 在线音色映射（P6 / IM-517）：不再写死 ConfigStore.onlineVoice()（默认白桦＝男声）
        val mapped = mappedOnlineVoice(voiceHint)
        return when (
            val choice = OnlineSelector.select(
                onlineEnabled = true, // 已在上层短路（在线关闭零额外动作）
                apiKey = ConfigStore.onlineApiKey(),
                keyKind = ConfigStore.onlineKeyKind(),
                baseUrl = ConfigStore.onlineBaseUrl(),
                model = ConfigStore.onlineModel(),
                voice = mapped,
                style = ConfigStore.onlineStyle(),
                tokenPlanAccepted = ConfigStore.onlineTokenPlanAccepted(),
                allowMobileData = ConfigStore.netAllowMobileData(),
                onCellular = NetState.onCellular(context),
                roleEnabled = roleEnabled,
                roles = roles,
            )
        ) {
            is BackendChoice.Online -> choice
            is BackendChoice.Local -> {
                Log.i(TAG, "ONLINE|fallback=local|reason=${choice.reason}")
                null
            }
        }
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
                .setBufferSizeInBytes(minBuf * 2)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                sr,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
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
                    if (change != AudioManager.AUDIOFOCUS_GAIN) stop()
                }
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                { change -> if (change != AudioManager.AUDIOFOCUS_GAIN) stop() },
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
