package com.mimo.ebook2tts.local.tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import com.mimo.ebook2tts.local.LocalPrefs
import com.mimo.ebook2tts.local.analysis.TextClean
import com.mimo.ebook2tts.local.voice.LocalVoice
import java.util.Locale

/**
 * 本地标准 TTS 引擎。
 * applicationId: com.mimo.ebook2tts.local
 */
class LocalTextToSpeechService : TextToSpeechService() {

    companion object {
        private const val TAG = "LocalTtsService"
    }

    private lateinit var engine: LocalSynthesisEngine

    @Volatile
    private var currentRequestStopped = false

    override fun onCreate() {
        super.onCreate()
        engine = LocalSynthesisEngine(applicationContext)
        LocalPrefs.onEnginePrefsChanged = {
            Log.i(TAG, "prefs changed → reload backend")
            Thread {
                try {
                    engine.onPrefsChanged()
                } catch (t: Throwable) {
                    Log.e(TAG, "prefs reload failed", t)
                }
            }.start()
        }
        Thread {
            try {
                engine.initBackend()
                Log.i(TAG, "backend ready")
            } catch (t: Throwable) {
                Log.e(TAG, "backend init failed", t)
            }
        }.start()
    }

    override fun onDestroy() {
        currentRequestStopped = true
        LocalPrefs.onEnginePrefsChanged = null
        engine.stop()
        engine.release()
        super.onDestroy()
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val l = lang ?: ""
        return when {
            l.equals("zho", true) || l.equals("chi", true) || l.equals("zh", true) -> {
                if (country.isNullOrBlank() ||
                    country.equals("CN", true) ||
                    country.equals("Hans", true)
                ) TextToSpeech.LANG_AVAILABLE
                else TextToSpeech.LANG_COUNTRY_AVAILABLE
            }
            l.equals("eng", true) || l.equals("en", true) ->
                TextToSpeech.LANG_COUNTRY_AVAILABLE
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onGetLanguage(): Array<String> = arrayOf("zho", "CN", "")

    override fun onStop() {
        currentRequestStopped = true
        engine.stop()
    }

    /** 第三方 App（Legado 等）需要 Voice API 才能列出多音色 */
    override fun onGetVoices(): List<Voice> {
        val pool = LocalVoice.poolForModel(LocalPrefs.modelId(this))
        return pool.map { v ->
            Voice(
                v.id,
                Locale.SIMPLIFIED_CHINESE,
                Voice.QUALITY_NORMAL,
                Voice.LATENCY_NORMAL,
                false,
                mutableSetOf<String>()
            )
        }
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        if (voiceName.isNullOrBlank()) return TextToSpeech.ERROR
        val ok = LocalVoice.poolForModel(LocalPrefs.modelId(this)).any { it.id == voiceName }
        return if (ok) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    }

    override fun onLoadVoice(voiceName: String?): Int = onIsValidVoiceName(voiceName)

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String {
        return LocalPrefs.narratorVoice(this)
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val raw = request.charSequenceText?.toString() ?: request.text ?: ""
        val text = TextClean.normalize(raw)
        if (text.isBlank() || TextClean.isJunkLine(text)) {
            callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return
        }

        currentRequestStopped = false
        engine.resetStopped()

        // 阅读器语速（request.speechRate，1000 = 1.0）
        val readerRate = (request.speechRate / 1000f).coerceIn(0.5f, 2.5f)
        // 显式 setVoice 覆盖自动多角色（仅非空且合法时）
        val explicitVoice = request.voiceName?.takeIf { it.isNotBlank() }
            ?.takeIf { onIsValidVoiceName(it) == TextToSpeech.SUCCESS }

        Log.i(TAG, "onSynthesizeText len=${text.length} rate=$readerRate voice=$explicitVoice")

        val result = try {
            engine.synthesize(text, readerRate, explicitVoice)
        } catch (t: Throwable) {
            Log.e(TAG, "synthesize failed", t)
            null
        }

        if (result == null || result.pcm.isEmpty()) {
            Log.w(TAG, "no pcm result stopped=$currentRequestStopped")
            callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.error()
            return
        }

        Log.i(
            TAG,
            "pcm ready bytes=${result.pcm.size} sr=${result.sampleRate} voice=${result.voiceId} stopped=$currentRequestStopped"
        )

        val startRc = callback.start(result.sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
        if (startRc != TextToSpeech.SUCCESS) {
            Log.e(TAG, "callback.start failed rc=$startRc")
            return
        }

        val max = callback.maxBufferSize.coerceAtLeast(8192)
        var offset = 0
        val pcm = result.pcm
        while (offset < pcm.size) {
            if (currentRequestStopped || engine.isStopped()) {
                Log.w(TAG, "stopped during write offset=$offset/${pcm.size}")
                break
            }
            val len = minOf(max, pcm.size - offset)
            val rc = callback.audioAvailable(pcm, offset, len)
            if (rc != TextToSpeech.SUCCESS) {
                Log.e(TAG, "audioAvailable failed rc=$rc offset=$offset len=$len")
                break
            }
            offset += len
        }
        callback.done()
    }
}
