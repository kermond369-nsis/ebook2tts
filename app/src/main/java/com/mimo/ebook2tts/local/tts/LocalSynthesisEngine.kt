package com.mimo.ebook2tts.local.tts

import android.content.Context
import android.util.Log
import com.mimo.ebook2tts.local.LocalPrefs
import com.mimo.ebook2tts.local.analysis.CharacterRegistry
import com.mimo.ebook2tts.local.analysis.Emotion
import com.mimo.ebook2tts.local.analysis.NovelTextBuffer
import com.mimo.ebook2tts.local.analysis.SpeakerIds
import com.mimo.ebook2tts.local.analysis.TextAnalyzer
import com.mimo.ebook2tts.local.buffer.SegmentCache
import com.mimo.ebook2tts.local.model.ModelCatalog
import com.mimo.ebook2tts.local.model.ModelDownloader
import com.mimo.ebook2tts.local.voice.LocalVoice
import com.mimo.ebook2tts.local.voice.LocalVoiceAssignment
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地合成流水线：
 * 1. 文本进滚动缓存
 * 2. 机械分析说话人/情绪
 * 3. 角色 → 本地音色分配
 * 4. SherpaONNX 或 系统 TTS 合成
 */
class LocalSynthesisEngine(context: Context) {

    companion object {
        private const val TAG = "LocalSynthEngine"
    }

    private val appContext = context.applicationContext

    @Volatile
    private var backend: LocalTtsBackend? = null

    @Volatile
    private var voicePool: List<LocalVoice> = LocalVoice.KOKORO_ZH

    @Volatile
    private var cast: Map<String, LocalVoice> = emptyMap()

    @Volatile
    private var cache = SegmentCache(LocalPrefs.bufferSize(appContext))

    @Volatile
    private var analyzer = TextAnalyzer(
        smartCharacter = LocalPrefs.smartCharacter(appContext),
        emotionEnabled = LocalPrefs.emotionEnabled(appContext),
    )

    private val textBuffer = NovelTextBuffer(200_000)
    private val registry = CharacterRegistry(appContext)
    private val castLock = Any()
    private val stopped = AtomicBoolean(false)

    fun registry(): CharacterRegistry = registry

    fun initBackend() {
        val mode = LocalPrefs.Backend.byId(LocalPrefs.backend(appContext))
        backend?.release()
        backend = when (mode) {
            LocalPrefs.Backend.SHERPA -> {
                val spec = ModelCatalog.byId(LocalPrefs.modelId(appContext))
                if (ModelDownloader.isReady(appContext, spec)) {
                    val b = SherpaBackend(appContext, spec, LocalPrefs.numThreads(appContext))
                    voicePool = LocalVoice.poolForModel(spec.id, b.numSpeakers())
                    if (!b.isReady()) Log.e(TAG, "sherpa not ready: ${b.lastError()}")
                    b
                } else {
                    Log.w(TAG, "model not downloaded, fallback system")
                    null
                }
            }
            LocalPrefs.Backend.SYSTEM -> null
        }
        if (backend == null) {
            backend = SystemTtsBackend(appContext)
            voicePool = LocalVoice.VITS_ZH_LL
        }
        rebuildCast()
    }

    fun onPrefsChanged() {
        cache = SegmentCache(LocalPrefs.bufferSize(appContext))
        analyzer = TextAnalyzer(
            smartCharacter = LocalPrefs.smartCharacter(appContext),
            emotionEnabled = LocalPrefs.emotionEnabled(appContext),
        )
        initBackend()
    }

    private fun rebuildCast() {
        val narratorId = LocalPrefs.narratorVoice(appContext)
        val pool = voicePool
        val known = analyzer.knownSpeakerIds()
        synchronized(castLock) {
            cast = LocalVoiceAssignment.assign(
                speakerIds = known,
                genderOf = { analyzer.genderOf(it) },
                narratorVoiceId = narratorId,
                pool = pool,
            )
        }
    }

    fun stop() = stopped.set(true)
    fun resetStopped() = stopped.set(false)
    fun isStopped(): Boolean = stopped.get()

    fun clearCache() {
        cache.clear()
        textBuffer.clear()
    }

    fun cacheStats(): String =
        "buffer ${cache.size()}/${LocalPrefs.bufferSize(appContext)} · hit ${cache.hits} · miss ${cache.misses}"

    fun knownCast(): Map<String, LocalVoice> = synchronized(castLock) { cast.toMap() }

    data class SynthResult(
        val pcm: ByteArray,
        val sampleRate: Int,
        val speakerId: String,
        val voiceId: String,
        val emotionId: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SynthResult) return false
            return pcm.contentEquals(other.pcm) && sampleRate == other.sampleRate
        }

        override fun hashCode(): Int = pcm.contentHashCode() * 31 + sampleRate
    }

    fun synthesize(raw: String): SynthResult? {
        if (stopped.get()) return null
        val analysis = analyzer.analyze(raw)
        if (analysis.speakText.isBlank()) return null

        textBuffer.append(analysis.text)
        if (analysis.speakerId != SpeakerIds.NARRATOR) {
            rebuildCast()
        }

        val voice = synchronized(castLock) {
            cast[analysis.speakerId] ?: cast[SpeakerIds.NARRATOR]
        } ?: LocalVoice.KOKORO_ZH.last()

        val speed = LocalPrefs.speed(appContext) * analysis.emotion.rateMul
        val key = cacheKey(analysis.speakText, voice.id, analysis.emotion.id, speed)
        cache.get(key)?.let {
            return SynthResult(it.pcm, it.sampleRate, analysis.speakerId, voice.id, analysis.emotion.id)
        }

        val eng = backend ?: return null
        val pcm = eng.synthesize(analysis.speakText, voice.speakerId, speed)
        if (pcm.isEmpty()) {
            Log.w(TAG, "empty pcm speaker=${analysis.speakerId} voice=${voice.id}")
            return null
        }
        cache.put(key, SegmentCache.Entry(pcm, eng.sampleRate, 1))
        return SynthResult(pcm, eng.sampleRate, analysis.speakerId, voice.id, analysis.emotion.id)
    }

    fun release() {
        backend?.release()
        backend = null
    }

    private fun cacheKey(text: String, voice: String, emotion: String, speed: Float): String {
        val s = String.format("%.2f", speed)
        return "$voice|$emotion|$s|$text"
    }
}
