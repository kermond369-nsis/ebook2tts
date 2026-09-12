package com.mimo.ebook2tts.local.tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import com.mimo.ebook2tts.local.analysis.TextClean
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 本地标准 TTS 引擎。小说软件通过 TextToSpeech 绑定即可多角色朗读。
 * applicationId: com.mimo.ebook2tts.local
 */
class LocalTextToSpeechService : TextToSpeechService() {

    companion object {
        private const val TAG = "LocalTtsService"
    }

    private lateinit var engine: LocalSynthesisEngine

    @Volatile
    private var currentRequestStopped = false

    private val producer = AtomicReference<Thread?>(null)

    override fun onCreate() {
        super.onCreate()
        engine = LocalSynthesisEngine(applicationContext)
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
        engine.stop()
        producer.get()?.interrupt()
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
        producer.get()?.interrupt()
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

        Log.i(TAG, "onSynthesizeText len=${text.length}")
        val result = try {
            engine.synthesize(text)
        } catch (t: Throwable) {
            Log.e(TAG, "synthesize failed", t)
            null
        }

        if (result == null || result.pcm.isEmpty() || currentRequestStopped) {
            Log.w(TAG, "empty result pcm=${result?.pcm?.size ?: 0}")
            // 空结果也要 start/done，否则框架会卡住
            callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return
        }

        Log.i(TAG, "pcm ready bytes=${result.pcm.size} sr=${result.sampleRate}")
        callback.start(result.sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)

        // Android SynthesisCallback 每次 maxBufferSize 有限，分块写
        val max = callback.maxBufferSize
        var offset = 0
        val pcm = result.pcm
        while (offset < pcm.size && !currentRequestStopped) {
            val len = minOf(max, pcm.size - offset)
            if (callback.audioAvailable(pcm, offset, len) != TextToSpeech.SUCCESS) {
                break
            }
            offset += len
        }
        if (!currentRequestStopped) {
            callback.done()
        }
    }
}
