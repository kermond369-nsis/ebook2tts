package com.mimo.ebook2tts.local.tts

import android.content.Context
import android.util.Log
import com.mimo.ebook2tts.local.LocalPrefs
import com.mimo.ebook2tts.local.analysis.CharacterRegistry
import com.mimo.ebook2tts.local.analysis.NovelTextBuffer
import com.mimo.ebook2tts.local.analysis.SpeakerIds
import com.mimo.ebook2tts.local.analysis.TextAnalyzer
import com.mimo.ebook2tts.local.analysis.TextClean
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
 * 4. 分句合成 + 句间静音
 */
class LocalSynthesisEngine(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var backend: LocalTtsBackend? = null

    @Volatile
    private var voicePool: List<LocalVoice> = LocalVoice.poolForModel("kokoro-int8-multi-lang-v1_1")

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
    private val backendReady = java.util.concurrent.CountDownLatch(1)

    fun registry(): CharacterRegistry = registry

    /** 等待 initBackend 完成（模型加载可能要数秒） */
    fun awaitBackend(timeoutMs: Long = 30_000): Boolean {
        return backendReady.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    fun initBackend() {
        val mode = LocalPrefs.Backend.byId(LocalPrefs.backend(appContext))
        backend?.release()
        backend = when (mode) {
            LocalPrefs.Backend.SHERPA -> {
                val spec = ModelCatalog.byId(LocalPrefs.modelId(appContext))
                if (ModelDownloader.isReady(appContext, spec)) {
                    val b = SherpaBackend(appContext, spec, LocalPrefs.numThreads(appContext))
                    if (b.isReady()) {
                        voicePool = LocalVoice.poolForModel(spec.id, b.numSpeakers())
                        Log.i(
                            TAG,
                            "sherpa ready model=${spec.id} speakers=${b.numSpeakers()} sr=${b.sampleRate} narrator=${LocalPrefs.narratorVoice(appContext)} pool=${voicePool.size}"
                        )
                        b
                    } else {
                        // 不回退系统 TTS：失败就是失败
                        Log.e(TAG, "sherpa not ready: ${b.lastError()}")
                        b.release()
                        null
                    }
                } else {
                    Log.e(TAG, "model not downloaded: ${spec.id}")
                    null
                }
            }
            LocalPrefs.Backend.SYSTEM -> {
                Log.w(TAG, "system backend selected explicitly")
                SystemTtsBackend(appContext).also { voicePool = LocalVoice.VITS_ZH_LL }
            }
        }
        if (backend == null) {
            Log.e(TAG, "NO backend available — synthesis will be silent")
        }
        rebuildCast()
        backendReady.countDown()
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

    fun synthesize(
        raw: String,
        readerRate: Float = 1.0f,
        explicitVoiceId: String? = null,
    ): SynthResult? {
        if (stopped.get()) return null
        if (!awaitBackend(8_000)) {
            Log.e(TAG, "backend not ready after 8s")
            return null
        }
        if (backend == null) {
            Log.e(TAG, "backend is null, cannot synthesize")
            return null
        }

        textBuffer.append(TextClean.normalize(raw))
        val units = analyzer.analyzeSegments(raw)
        if (units.isEmpty()) return null

        // 新角色出现时刷新音色表
        if (units.any { it.speakerId != SpeakerIds.NARRATOR }) {
            rebuildCast()
        }

        val eng = backend ?: return null
        val sr = eng.sampleRate
        val gapBytes = sr * 2 * 120 / 1000
        val out = java.io.ByteArrayOutputStream(raw.length * 400)
        var firstSpeaker = SpeakerIds.NARRATOR
        var firstVoiceId = ""
        var firstEmotionId = "calm"

        Log.i(TAG, "units=${units.size} model=${LocalPrefs.modelId(appContext)} backend=${LocalPrefs.backend(appContext)}")

        for ((idx, u) in units.withIndex()) {
            if (stopped.get()) break
            // 外部 App 显式 setVoice 时整段覆盖；否则自动多角色
            val voice = if (explicitVoiceId != null) {
                LocalVoice.byId(voicePool, explicitVoiceId)
            } else {
                synchronized(castLock) {
                    cast[u.speakerId] ?: cast[SpeakerIds.NARRATOR]
                } ?: voicePool.first()
            }
            val speed = (LocalPrefs.speed(appContext) * u.emotion.rateMul * readerRate)
                .coerceIn(0.5f, 2.5f)
            val modelId = LocalPrefs.modelId(appContext)
            val key = cacheKey(modelId, u.text, voice.id, u.emotion.id, speed)

            if (idx == 0) {
                firstSpeaker = u.speakerId
                firstVoiceId = voice.id
                firstEmotionId = u.emotion.id
            }

            val cached = cache.get(key)
            val pcm = cached?.pcm ?: synthesizeSplit(u.text, voice.speakerId, speed, sr)
            Log.i(
                TAG,
                "unit[$idx] sp=${u.speakerId} voice=${voice.id} sid=${voice.speakerId} dlg=${u.isDialogue} len=${u.text.length} pcm=${pcm.size}"
            )
            if (pcm.isEmpty()) continue
            if (cached == null) {
                cache.put(key, SegmentCache.Entry(pcm, sr, 1))
            }
            if (out.size() > 0) out.write(ByteArray(gapBytes))
            out.write(pcm)
        }

        val all = out.toByteArray()
        if (all.isEmpty()) {
            Log.w(TAG, "empty pcm units=${units.size}")
            return null
        }
        return SynthResult(all, sr, firstSpeaker, firstVoiceId, firstEmotionId)
    }

    fun release() {
        backend?.release()
        backend = null
    }

    private fun cacheKey(modelId: String, text: String, voice: String, emotion: String, speed: Float): String {
        val s = String.format("%.2f", speed)
        return "$modelId|$voice|$emotion|$s|$text"
    }

    /** 分句合成 + 120ms 句间静音，避免整段赶读 */
    private fun synthesizeSplit(
        text: String,
        speakerId: Int,
        speed: Float,
        sampleRate: Int,
    ): ByteArray {
        val eng = backend ?: return ByteArray(0)
        val sentences = splitSentences(text)
        Log.i(TAG, "split into ${sentences.size} sentences, len=${text.length}")
        if (sentences.isEmpty()) return ByteArray(0)
        if (sentences.size == 1) return eng.synthesize(sentences[0], speakerId, speed)

        val gapBytes = (sampleRate * 2 * 120 / 1000)
        val out = java.io.ByteArrayOutputStream(text.length * 400)
        for ((i, s) in sentences.withIndex()) {
            if (stopped.get()) break
            val pcm = eng.synthesize(s, speakerId, speed)
            if (pcm.isNotEmpty()) {
                out.write(pcm)
                if (i < sentences.size - 1) {
                    out.write(ByteArray(gapBytes))
                }
            }
        }
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "LocalSynthEngine"
        private val SENTENCE_END = Regex("(?<=[。！？；…!?;])")

        internal fun splitSentences(text: String): List<String> {
            val cleaned = text.trim()
            if (cleaned.isEmpty()) return emptyList()
            val parts = cleaned.split(SENTENCE_END)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (parts.isEmpty()) return listOf(cleaned)
            val merged = mutableListOf<String>()
            for (p in parts) {
                val short = p.length < 6 &&
                    !p.endsWith("。") && !p.endsWith("！") && !p.endsWith("？")
                if (short && merged.isNotEmpty() && merged.last().length + p.length <= 80) {
                    merged[merged.size - 1] = merged.last() + p
                } else {
                    merged.add(p)
                }
            }
            return merged
        }
    }
}
