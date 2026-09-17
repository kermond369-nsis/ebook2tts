package com.kermond.ebook2tts.engine

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.kermond.ebook2tts.core.ModelSpec
import com.kermond.ebook2tts.core.SynthPcmCache
import com.kermond.ebook2tts.core.PcmChunker
import com.kermond.ebook2tts.core.ProcessNames
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sherpa 后端。仅允许在 :tts_service 进程加载（AR-§1.7）。
 *
 * 锁纪律（ADR-008 / 红线 7）：本类所有 native 调用（load/release/generate 系列 / numSpeakers）
 * 一律经 [NativeGate] 串行；**锁内绝不做网络与阻塞等待**（详见 NativeGate 注释）。
 * 流式：generateWithCallback（回调返回 0=停止、1=继续）。
 */
class SherpaBackend(
    private val modelDir: File,
    private val spec: ModelSpec,
    private val numThreads: Int = 2,
) {

    /** P7 第二批 / ADR-019：合成结果缓存（命中即跳过 native 调用） */
    private val cache = SynthPcmCache()
    companion object {
        private const val TAG = "SherpaBackend"
    }

    private var tts: OfflineTts? = null
    var sampleRate: Int = 24000
        private set
    var loadError: String? = null
        private set

    private val released = AtomicBoolean(false)

    fun isReady(): Boolean = tts != null && !released.get()
    /** native 读取：进同一把门（与换装/释放互斥） */
    fun numSpeakers(): Int =
        NativeGate.withLock { runCatching { tts?.numSpeakers() ?: 0 }.getOrDefault(0) }

    fun load() = NativeGate.withLock { loadLocked() }

    private fun loadLocked() {
        // 红线 4：native 模型只允许在 :tts_service 进程加载（主进程零推理）
        val cmdline = runCatching { File("/proc/self/cmdline").readBytes() }.getOrNull()
        if (!ProcessNames.isEngineProcess(ProcessNames.parseCmdline(cmdline))) {
            loadError = "inference_outside_engine_process"
            Log.e(TAG, "ABORT|load attempted outside :tts_service")
            return
        }
        try {
            if (!modelReady()) {
                loadError = "模型未就绪"
                return
            }
            fun abs(name: String): String =
                if (name.isEmpty()) "" else File(modelDir, name).absolutePath
            fun absList(names: String): String =
                if (names.isEmpty()) ""
                else names.split(",").joinToString(",") { File(modelDir, it.trim()).absolutePath }

            val modelCfg = if (spec.files.kind == "kokoro") {
                OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = "",
                        lexicon = "",
                        tokens = "",
                        dataDir = "",
                        dictDir = "",
                    ),
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = abs(spec.files.modelName),
                        voices = abs(spec.files.voices),
                        tokens = abs(spec.files.tokens),
                        dataDir = abs(spec.files.dataDir),
                        lexicon = absList(spec.files.lexicon),
                        lang = "zh",
                        dictDir = "",
                        lengthScale = 1.0f,
                    ),
                    numThreads = numThreads,
                    provider = "cpu",
                )
            } else {
                OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = abs(spec.files.modelName),
                        lexicon = absList(spec.files.lexicon),
                        tokens = abs(spec.files.tokens),
                        dataDir = abs(spec.files.dataDir),
                        dictDir = "",
                        lengthScale = 1.0f,
                    ),
                    numThreads = numThreads,
                    provider = "cpu",
                )
            }
            val config = OfflineTtsConfig(
                model = modelCfg,
                ruleFsts = absList(spec.files.ruleFsts),
                ruleFars = "",
            )
            val engine = OfflineTts(config = config)
            tts = engine
            sampleRate = engine.sampleRate()
            cache.clear() // 模型（重）加载后旧缓存一律失效（ADR-019 §16.2）
            Log.i(TAG, "loaded sr=$sampleRate speakers=${engine.numSpeakers()} threads=$numThreads")  // P7: 打印实际生效线程数（原为 ConfigStore 值，与覆盖钩子不符）
        } catch (t: Throwable) {
            loadError = t.message ?: t.toString()
            Log.e(TAG, "load failed", t)
        }
    }

    private fun modelReady(): Boolean {
        val model = File(modelDir, spec.files.modelName)
        return model.exists() && model.length() > 0
        // .completed 可由迁移回填；有模型文件即可尝试加载
    }

    /**
     * 逐段生成，回调推 PCM。
     * @param onPcm return true 继续，false 停止
     * @return 是否被客户端中止
     */
    fun generateStreaming(
        text: String,
        speakerId: Int,
        speed: Float,
        onPcm: (FloatArray) -> Boolean,
    ): Boolean = NativeGate.withLock { generateStreamingLocked(text, speakerId, speed, onPcm) }

    private fun generateStreamingLocked(
        text: String,
        speakerId: Int,
        speed: Float,
        onPcm: (FloatArray) -> Boolean,
    ): Boolean {
        val engine = tts ?: return false
        if (text.isBlank()) return false
        var stopped = false
        try {
            engine.generateWithCallback(text, speakerId, speed) { samples ->
                if (samples.isEmpty()) return@generateWithCallback 1
                val cont = onPcm(samples)
                if (!cont) {
                    stopped = true
                    0
                } else {
                    1
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "generateWithCallback failed", t)
        }
        return stopped
    }

    fun generatePcm(text: String, speakerId: Int, speed: Float): ByteArray {
        // P7 第二批（ADR-019）：先查缓存 —— 命中则跳过 native 调用（**不改变**外部行为）
        val key = SynthPcmCache.keyFor(spec.id, speakerId, speed, text)
        cache.get(key)?.let {
            Log.i(TAG, "CACHE|hit|model=${spec.id}|spk=$speakerId|bytes=${it.size}")
            return it
        }
        Log.i(TAG, "CACHE|miss|model=${spec.id}|spk=$speakerId|chars=${text.length}")
        val pcm = NativeGate.withLock { generatePcmLocked(text, speakerId, speed) }
        cache.put(key, pcm)
        return pcm
    }

    private fun generatePcmLocked(text: String, speakerId: Int, speed: Float): ByteArray {
        val engine = tts ?: return ByteArray(0)
        if (text.isBlank()) return ByteArray(0)
        return try {
            val audio = engine.generate(text, speakerId, speed)
            PcmChunker.floatToPcm16(audio.samples)
        } catch (t: Throwable) {
            Log.e(TAG, "generate failed", t)
            ByteArray(0)
        }
    }

    fun release() = NativeGate.withLock { releaseLocked() }

    private fun releaseLocked() {
        if (!released.compareAndSet(false, true)) return
        try {
            tts?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "release failed", t)
        }
        tts = null
    }
}
