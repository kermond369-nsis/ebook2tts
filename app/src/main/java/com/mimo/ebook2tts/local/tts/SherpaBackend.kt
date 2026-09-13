package com.mimo.ebook2tts.local.tts

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.mimo.ebook2tts.local.model.ModelDownloader
import com.mimo.ebook2tts.local.model.ModelSpec
import java.io.File

/**
 * SherpaONNX 本地模型后端。
 * 模型在 filesDir/models，用绝对路径构造 OfflineTtsConfig。
 */
class SherpaBackend(
    context: Context,
    private val spec: ModelSpec,
    numThreads: Int = 2,
) : LocalTtsBackend {

    companion object {
        private const val TAG = "SherpaBackend"
    }

    private val appContext = context.applicationContext

    @Volatile
    private var tts: OfflineTts? = null

    @Volatile
    override var sampleRate: Int = 24000
        private set

    @Volatile
    private var loadError: String? = null

    init {
        try {
            val dir = ModelDownloader.modelDir(appContext, spec)
            if (!ModelDownloader.isReady(appContext, spec)) {
                loadError = "模型未就绪，请先下载"
                Log.e(TAG, "model not ready: ${dir.absolutePath}")
            } else {
                fun abs(name: String): String =
                    if (name.isEmpty()) "" else File(dir, name).absolutePath

                fun absList(names: String): String =
                    if (names.isEmpty()) ""
                    else names.split(",").joinToString(",") { File(dir, it.trim()).absolutePath }

                val modelPath = abs(spec.modelName)
                val voicesPath = abs(spec.voices)
                val tokensPath = abs(spec.tokens)
                val dataDirPath = abs(spec.dataDir)
                val lexiconPath = absList(spec.lexicon)
                val ruleFstsPath = absList(spec.ruleFsts)
                val ruleFarsPath = abs(spec.ruleFars)

                Log.i(TAG, "loading kind=${spec.kind} dir=$dir")

                val modelCfg = if (spec.kind == "kokoro") {
                    OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = "",
                            lexicon = "",
                            tokens = "",
                            dataDir = "",
                            dictDir = "",
                        ),
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = modelPath,
                            voices = voicesPath,
                            tokens = tokensPath,
                            dataDir = dataDirPath,
                            lexicon = lexiconPath,
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
                            model = modelPath,
                            lexicon = lexiconPath,
                            tokens = tokensPath,
                            dataDir = dataDirPath,
                            dictDir = "",
                            lengthScale = 1.0f,
                        ),
                        numThreads = numThreads,
                        provider = "cpu",
                    )
                }

                val config = OfflineTtsConfig(
                    model = modelCfg,
                    ruleFsts = ruleFstsPath,
                    ruleFars = ruleFarsPath,
                )

                val engine = OfflineTts(config = config)
                tts = engine
                sampleRate = engine.sampleRate()
                Log.i(TAG, "loaded ok sr=$sampleRate speakers=${engine.numSpeakers()}")
            }
        } catch (t: Throwable) {
            loadError = t.message ?: t.toString()
            Log.e(TAG, "failed to init sherpa", t)
        }
    }

    fun numSpeakers(): Int = try {
        tts?.numSpeakers() ?: 0
    } catch (_: Throwable) {
        0
    }

    fun lastError(): String? = loadError

    override fun isReady(): Boolean = tts != null

    override fun synthesize(text: String, speakerId: Int, speed: Float): ByteArray {
        val engine = tts ?: return ByteArray(0)
        if (text.isBlank()) return ByteArray(0)
        val audio = try {
            engine.generate(text = text, sid = speakerId, speed = speed)
        } catch (t: Throwable) {
            Log.e(TAG, "generate failed sid=$speakerId", t)
            return ByteArray(0)
        }
        return floatToPcm16(audio.samples)
    }

    override fun release() {
        try {
            // SDK 提供 native 释放，避免等 GC
            tts?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "release failed", t)
        }
        tts = null
    }

    private fun floatToPcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var i = 0
        while (i < samples.size) {
            val s = samples[i]
            val v = (s * 32767f).toInt().coerceIn(-32768, 32767)
            out[i * 2] = (v and 0xff).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
            i++
        }
        return out
    }
}
