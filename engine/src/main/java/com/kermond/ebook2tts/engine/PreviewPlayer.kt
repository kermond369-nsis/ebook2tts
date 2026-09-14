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
import com.kermond.ebook2tts.core.OnlineSettings
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
                // 请求级后端接缝：在线优先；失败静默回落本地完整重播
                if (tryOnlinePreview(cleaned, listener)) return@execute
                if (!playing.get()) return@execute // 在线失败后用户已停止：不再回落
                val modelId = ConfigStore.modelId()
                val spec = ModelCatalog.byId(modelId)
                val dir = File(context.filesDir, "models/${spec.id}")
                val backend = SherpaBackend(dir, spec, ConfigStore.threads())
                backend.load()
                if (!backend.isReady()) {
                    listener.onError(-12, backend.loadError ?: "模型未就绪")
                    backend.release()
                    playing.set(false)
                    return@execute
                }
                val pool = VoiceCatalog.poolForModel(modelId, backend.numSpeakers())
                val voice = VoiceCatalog.resolve(pool, voiceId ?: ConfigStore.narratorVoice())
                startTrack(backend.sampleRate, listener)
                val segments = TextAnalyzer.analyze(cleaned)
                var doneSeg = 0
                for (seg in segments) {
                    if (!playing.get()) break
                    if (seg.text.isBlank()) continue
                    val spoken = seg.text
                    val pcm = backend.generatePcm(spoken, voice.speakerId, SpeedMapper.actualSpeed(100 * speed.toInt().coerceAtLeast(1), 0f).let {
                        // speed here is already multiplier 0.5..2
                        speed.coerceIn(0.5f, 2.0f)
                    })
                    if (pcm.isNotEmpty() && playing.get()) {
                        var i = 0
                        while (i < pcm.size && playing.get()) {
                            val end = minOf(i + 4096, pcm.size)
                            track?.write(pcm, i, end - i)
                            i = end
                        }
                    }
                    doneSeg++
                    listener.onProgress((doneSeg * 100) / segments.size.coerceAtLeast(1))
                }
                // fade out
                runCatching { track?.stop() }
                backend.release()
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
    private fun tryOnlinePreview(cleaned: String, listener: Listener): Boolean {
        val choice = selectOnline() ?: return false
        val request = choice.request
        Log.i(
            TAG,
            "ONLINE|chosen=online|model=${request.model}|voice=${request.voice}" +
                "|sr=${OnlineSettings.SAMPLE_RATE}"
        )
        choice.warning?.let { Log.w(TAG, "ONLINE|warn=key_kind_mismatch|$it") }

        // 在线试听不套用语速参数（MiMo TTS 协议无 speed 字段）
        val segments = TextAnalyzer.analyze(cleaned)
        var doneSeg = 0
        var trackFailed = false
        var anyPcm = false
        for (seg in segments) {
            if (!playing.get()) return true
            if (seg.text.isBlank()) continue
            val outcome = OnlineBackendHolder.get().synthesize(
                request = request,
                text = seg.text,
                onPcm = onPcm@{ pcm ->
                    if (!playing.get()) return@onPcm false
                    if (track == null && !startTrack(OnlineSettings.SAMPLE_RATE, listener)) {
                        trackFailed = true
                        return@onPcm false
                    }
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
        runCatching { track?.stop() }
        releaseTrack()
        abandonFocus()
        playing.set(false)
        listener.onDone()
        return true
    }

    /**
     * 请求级在线选择（同 SynthesisCoordinator.selectOnline；在线关闭 → 单次 MMKV 布尔读后短路）。
     */
    private fun selectOnline(): BackendChoice.Online? {
        if (!ConfigStore.onlineEnabled()) return null
        return when (
            val choice = OnlineSelector.select(
                onlineEnabled = true, // 已在上层短路（在线关闭零额外动作）
                apiKey = ConfigStore.onlineApiKey(),
                keyKind = ConfigStore.onlineKeyKind(),
                baseUrl = ConfigStore.onlineBaseUrl(),
                model = ConfigStore.onlineModel(),
                voice = ConfigStore.onlineVoice(),
                style = ConfigStore.onlineStyle(),
                tokenPlanAccepted = ConfigStore.onlineTokenPlanAccepted(),
                allowMobileData = ConfigStore.netAllowMobileData(),
                onCellular = NetState.onCellular(context),
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
