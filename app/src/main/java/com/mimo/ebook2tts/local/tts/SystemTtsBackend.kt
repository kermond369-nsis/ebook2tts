package com.mimo.ebook2tts.local.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 系统 TTS 机械音兜底：零下载、任意机型可用。
 * 用 pitch/rate 模拟多角色。
 */
class SystemTtsBackend(context: Context) : LocalTtsBackend {

    companion object {
        private const val TAG = "SystemTtsBackend"
    }

    private val appContext = context.applicationContext

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    // 机械音默认略放慢，减轻“赶/喘不上气”感
    private val pitchTable = floatArrayOf(1.0f, 1.15f, 0.85f, 1.25f, 0.75f, 1.05f, 0.9f, 1.2f)
    private val rateTable = floatArrayOf(0.88f, 0.92f, 0.82f, 0.95f, 0.80f, 0.88f, 0.85f, 0.90f)

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.CHINESE)
                }
                ready = true
                Log.i(TAG, "system tts ready")
            } else {
                Log.e(TAG, "system tts init failed: $status")
            }
        }
    }

    override var sampleRate: Int = 16000
        private set

    override fun isReady(): Boolean = ready && tts != null

    override fun synthesize(text: String, speakerId: Int, speed: Float): ByteArray {
        val engine = tts ?: return ByteArray(0)
        if (!ready || text.isBlank()) return ByteArray(0)

        engine.setPitch(pitchTable[speakerId % pitchTable.size])
        engine.setSpeechRate(rateTable[speakerId % rateTable.size] * speed)

        val id = UUID.randomUUID().toString()
        val outFile = File(appContext.cacheDir, "sys_$id.wav")
        val latch = LinkedBlockingQueue<Boolean>(1)

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == id) latch.offer(true)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == id) latch.offer(false)
            }
        })

        @Suppress("DEPRECATION")
        val r = engine.synthesizeToFile(text, null, outFile, id)
        if (r != TextToSpeech.SUCCESS) return ByteArray(0)

        val ok = try {
            latch.poll(15, TimeUnit.SECONDS) == true
        } catch (_: InterruptedException) {
            false
        }
        if (!ok) {
            outFile.delete()
            return ByteArray(0)
        }

        return try {
            readWavPcm(outFile)
        } catch (t: Throwable) {
            Log.e(TAG, "read wav failed", t)
            ByteArray(0)
        } finally {
            outFile.delete()
        }
    }

    override fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun readWavPcm(file: File): ByteArray {
        val raw = file.readBytes()
        if (raw.size < 44) return ByteArray(0)
        var i = 12
        while (i + 8 <= raw.size) {
            val chunkId = String(raw, i, 4, Charsets.US_ASCII)
            val chunkSize = (raw[i + 4].toInt() and 0xff) or
                ((raw[i + 5].toInt() and 0xff) shl 8) or
                ((raw[i + 6].toInt() and 0xff) shl 16) or
                ((raw[i + 7].toInt() and 0xff) shl 24)
            if (chunkId == "data") {
                val start = i + 8
                val end = minOf(raw.size, start + chunkSize)
                return raw.copyOfRange(start, end)
            }
            i += 8 + chunkSize + (chunkSize and 1)
        }
        return raw.copyOfRange(44, raw.size)
    }
}
