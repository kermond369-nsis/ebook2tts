package com.mimo.ebook2tts.engine

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.util.Log
import com.mimo.ebook2tts.core.EngineStateMachine
import com.mimo.ebook2tts.core.ModelCatalog
import com.mimo.ebook2tts.core.PcmChunker
import com.mimo.ebook2tts.core.RoleAssigner
import com.mimo.ebook2tts.core.RoleMode
import com.mimo.ebook2tts.core.SegmentKind
import com.mimo.ebook2tts.core.SpeedMapper
import com.mimo.ebook2tts.core.TextAnalyzer
import com.mimo.ebook2tts.core.TextClean
import com.mimo.ebook2tts.core.TextSegment
import com.mimo.ebook2tts.core.VoiceCatalog
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 合成协调器：流式管线 + 守卫锁 + 回调契约（AR-§4.2/4.3/4.4/4.8）。
 * 仅在 :tts_service 进程使用。
 */
class SynthesisCoordinator(
    private val filesDir: File,
) {
    companion object {
        private const val TAG = "SynthCoord"
        private const val MAX_AUDIO_BYTES = 8192
    }

    private val guard = ReentrantLock()
    private val sm = EngineStateMachine()
    private val stopped = AtomicBoolean(false)
    private val reloadExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tts-reload").apply { isDaemon = true }
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
        reloadExecutor.execute { reloadInternal(immediate = true) }
    }

    fun scheduleReload(reason: String) {
        // 400ms 去抖在重载线程（AR-§4.8.4）
        reloadExecutor.execute {
            try {
                Thread.sleep(400)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (!stopped.get()) {
                sm.requestBackendReload()
                // 若在 SYNTHESIZING，等接缝；否则立即换
                if (sm.state != com.mimo.ebook2tts.core.EngineState.SYNTHESIZING) {
                    reloadInternal(immediate = false)
                }
            }
        }
    }

    fun requestStop() {
        stopped.set(true)
    }

    fun resetStop() {
        stopped.set(false)
    }

    fun resetRoles() {
        roleAssigner.reset()
    }

    fun shutdown() {
        stopped.set(true)
        reloadExecutor.shutdownNow()
        guard.withLock {
            backend?.release()
            backend = null
        }
    }

    private fun reloadInternal(immediate: Boolean) {
        guard.withLock {
            if (sm.state == com.mimo.ebook2tts.core.EngineState.NO_MODEL ||
                sm.state == com.mimo.ebook2tts.core.EngineState.ERROR
            ) {
                // still try
            }
            if (sm.state != com.mimo.ebook2tts.core.EngineState.RELOADING &&
                sm.state != com.mimo.ebook2tts.core.EngineState.INITIALIZING
            ) {
                sm.requestBackendReload()
            }
            oldRelease()
            val modelId = ConfigStore.modelId()
            val spec = ModelCatalog.byId(modelId)
            val dir = File(filesDir, "models/${spec.id}")
            val hasModel = File(dir, spec.files.modelName).exists()
            if (!hasModel) {
                voicePool = VoiceCatalog.vitsZhLlVoices()
                backend = null
                sm.onNoModel("model_missing")
                ConfigStore.setStatusState("NO_MODEL")
                ConfigStore.setStatusModelId(spec.id)
                ConfigStore.setStatusLastError("模型未安装")
                return
            }
            sm.onInitStart()
            val b = SherpaBackend(dir, spec, ConfigStore.threads())
            b.load()
            if (!b.isReady()) {
                sm.onInitFailure(b.loadError ?: "load_failed")
                ConfigStore.setStatusState("ERROR")
                ConfigStore.setStatusLastError(b.loadError ?: "load_failed")
                return
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
            sm.onInitSuccess()
            ConfigStore.setStatusState("READY")
            ConfigStore.setStatusModelId(spec.id)
            ConfigStore.setStatusLastError("")
            Log.i(TAG, "reload ok model=$modelId")
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
     * 系统合成线程调用。回调契约：
     * start≤1；成功 done；失败 error+done；中止静默不 done。
     */
    fun synthesize(
        rawText: String?,
        speechRateAosp: Int,
        explicitVoiceName: String?,
        callback: SynthesisCallback,
    ): SynthResult {
        stopped.set(false)
        val text = TextClean.normalize(rawText ?: "")
        val b = backend
        if (b == null || !b.isReady()) {
            if (sm.state == com.mimo.ebook2tts.core.EngineState.NO_MODEL) {
                callback.error(TextToSpeechErrors.NOT_INSTALLED_YET)
                return SynthResult(false, true, false, 24000)
            }
            // 等待初始化 ≤10s
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline && !stopped.get()) {
                if (backend?.isReady() == true) break
                Thread.sleep(50)
            }
            val ready = backend
            if (ready == null || !ready.isReady()) {
                callback.error(TextToSpeechErrors.SYNTHESIS)
                return SynthResult(false, true, false, 24000)
            }
        }

        if (text.isBlank()) {
            val sr = backend?.sampleRate ?: 24000
            callback.start(sr, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return SynthResult(true, false, false, sr)
        }

        if (!sm.onSynthesizeStart()) {
            callback.error(TextToSpeechErrors.SYNTHESIS)
            return SynthResult(false, true, false, 24000)
        }

        val engine = backend!!
        val pool = voicePool
        val mode = if (ConfigStore.roleMode() == "respectReader") {
            RoleMode.RESPECT_READER
        } else {
            RoleMode.SMART_MULTI
        }
        val narrator = VoiceCatalog.resolve(pool, ConfigStore.narratorVoice())
        // RQ-106：smart 模式忽略客户端自动注入的默认旁白名
        val explicit = explicitVoiceName
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { mode == RoleMode.RESPECT_READER || it != ConfigStore.narratorVoice() }
            ?.takeIf { VoiceCatalog.isValid(pool, it) }

        val speed = SpeedMapper.actualSpeed(speechRateAosp, 0f)
        val segments = TextAnalyzer.analyze(text)
        var started = false
        var anyAudio = false
        var aborted = false
        var requestPcm = ArrayList<ByteArray>()

        fun pushStart(sr: Int): Boolean {
            if (started) return true
            val rc = callback.start(sr, AudioFormat.ENCODING_PCM_16BIT, 1)
            if (rc != TextToSpeechErrors.SUCCESS) {
                aborted = true
                return false
            }
            started = true
            return true
        }

        fun pushPcm(pcm: ByteArray): Boolean {
            if (pcm.isEmpty()) return true
            if (!anyAudio && !pushStart(engine.sampleRate)) return false
            var i = 0
            while (i < pcm.size) {
                if (stopped.get()) {
                    aborted = true
                    return false
                }
                val end = minOf(i + MAX_AUDIO_BYTES, pcm.size)
                val slice = if (i == 0 && end == pcm.size) pcm else pcm.copyOfRange(i, end)
                val rc = callback.audioAvailable(slice, 0, slice.size)
                if (rc != TextToSpeechErrors.SUCCESS) {
                    aborted = true
                    return false
                }
                anyAudio = true
                i = end
            }
            return true
        }

        try {
            guard.withLock {
                for (seg in segments) {
                    if (stopped.get()) {
                        aborted = true
                        break
                    }
                    if (seg.kind == SegmentKind.EMPTY) continue
                    val spoken = prepareSpeakText(seg)
                    if (spoken.isBlank()) continue

                    val voice = if (explicit != null && mode == RoleMode.RESPECT_READER) {
                        VoiceCatalog.resolve(pool, explicit)
                    } else {
                        roleAssigner.assign(seg)
                    }

                    var unitPcm = synthesizeUnit(engine, spoken, voice.speakerId, speed)
                    if (unitPcm.isEmpty()) {
                        // 强清洗重试（AR-§4.8.6）
                        val cleaned = TextClean.hardClean(spoken)
                        if (cleaned.isNotBlank()) {
                            unitPcm = synthesizeUnit(engine, cleaned, voice.speakerId, speed)
                        }
                    }
                    if (unitPcm.isEmpty()) {
                        dropUnits++
                        ConfigStore.bumpDropUnit()
                        Log.w(TAG, "WARN|DROP_UNIT|${dropUnits}|len=${spoken.length}")
                        if (anyAudio) {
                            // 保段序静音占位
                            val sil = PcmChunker.silenceMs(150, speed, engine.sampleRate)
                            if (!pushPcm(sil)) break
                        }
                    } else {
                        if (!pushPcm(unitPcm)) break
                        // 段间静音
                        if (seg.kind != SegmentKind.TITLE) {
                            val sil = PcmChunker.silenceMs(120, speed, engine.sampleRate)
                            if (!pushPcm(sil)) break
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "synthesize error", t)
            sm.onSynthesizeEnd()
            if (started && anyAudio) {
                // 已有音频：禁止 error 强停，正常 done
                callback.done()
                return SynthResult(true, false, false, engine.sampleRate)
            }
            if (!started) {
                callback.error(TextToSpeechErrors.SYNTHESIS)
                return SynthResult(false, true, false, engine.sampleRate)
            }
            callback.done()
            return SynthResult(true, false, false, engine.sampleRate)
        }

        sm.onSynthesizeEnd()
        // 接缝换装
        if (sm.pendingBackendReload) {
            reloadExecutor.execute {
                guard.withLock {
                    if (sm.pendingBackendReload) reloadInternal(immediate = false)
                }
            }
        }

        if (aborted && !anyAudio && stopped.get()) {
            // 中止且未 start：静默
            return SynthResult(false, false, true, engine.sampleRate)
        }
        if (!started) {
            // 非空文本零音频：整请求 error
            if (text.isNotBlank() && segments.any { it.kind != SegmentKind.EMPTY }) {
                callback.error(TextToSpeechErrors.SYNTHESIS)
                return SynthResult(false, true, false, engine.sampleRate)
            }
            callback.start(engine.sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return SynthResult(true, false, false, engine.sampleRate)
        }
        // 请求边界微淡化后 done
        callback.done()
        return SynthResult(true, false, aborted, engine.sampleRate)
    }

    private fun prepareSpeakText(seg: TextSegment): String {
        var t = seg.text
        if (seg.kind == SegmentKind.TITLE) {
            t = TextClean.chapterSpeakText(t)
        }
        t = com.mimo.ebook2tts.core.NumberReader.speakDigits(t)
        return t
    }

    private fun synthesizeUnit(
        engine: SherpaBackend,
        text: String,
        sid: Int,
        speed: Float,
    ): ByteArray {
        // 首段微切提升 TTFT
        val (first, rest) = TextAnalyzer.firstSegmentMicroCut(text)
        val chunks = ArrayList<ByteArray>(4)
        var aborted = false
        engine.generateStreaming(first, sid, speed) { samples ->
            if (stopped.get()) {
                aborted = true
                false
            } else {
                chunks += PcmChunker.floatToPcm16(samples)
                true
            }
        }
        if (aborted) return ByteArray(0)
        if (rest.isNotBlank()) {
            engine.generateStreaming(rest, sid, speed) { samples ->
                if (stopped.get()) {
                    aborted = true
                    false
                } else {
                    chunks += PcmChunker.floatToPcm16(samples)
                    true
                }
            }
        }
        if (chunks.isEmpty()) return ByteArray(0)
        val total = chunks.sumOf { it.size }
        val out = ByteArray(total)
        var o = 0
        for (c in chunks) {
            System.arraycopy(c, 0, out, o, c.size)
            o += c.size
        }
        return out
    }
}

/** TextToSpeech 常量镜像（避免引擎模块依赖 framework 常量歧义） */
object TextToSpeechErrors {
    const val SUCCESS = 0
    const val SYNTHESIS = -4
    const val NOT_INSTALLED_YET = -12
}
