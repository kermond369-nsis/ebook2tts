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
        runCatching {
            track?.pause()
            track?.flush()
            track?.stop()
            track?.release()
        }
        track = null
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
                requestFocus()
                val sr = backend.sampleRate
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
                track?.play()
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
