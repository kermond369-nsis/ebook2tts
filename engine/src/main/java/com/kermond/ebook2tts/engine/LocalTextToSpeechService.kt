package com.kermond.ebook2tts.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import androidx.core.content.ContextCompat
import com.kermond.ebook2tts.core.OnlineVoiceCatalog
import com.kermond.ebook2tts.core.TtsProviderKind
import java.util.Locale

/**
 * 系统 TTS 服务：声明于 `:tts_service` 进程，保留类名以确保旧版覆盖升级后无需在系统设置重新选取。
 */
class LocalTextToSpeechService : TextToSpeechService() {

    companion object {
        private const val TAG = "LocalTtsService"
    }

    private lateinit var coordinator: SynthesisCoordinator
    private var reloadReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        coordinator = CoordinatorHolder.getOrCreate(this)
        ConfigStore.notifyStatus(this, "INIT")

        val filter = IntentFilter(ConfigStore.ACTION_ENGINE_RELOAD)
        reloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val reason = intent?.getStringExtra("reason") ?: "config"
                Log.i(TAG, "ENGINE_RELOAD reason=$reason")
                coordinator.reloadConfig(reason)
            }
        }
        ContextCompat.registerReceiver(
            this,
            reloadReceiver!!,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ConfigStore.notifyStatus(this, "CREATED")
    }

    override fun onDestroy() {
        coordinator.requestStop()
        reloadReceiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    private fun isServiceAvailable(): Boolean = EngineAvailability.isAvailable(this)

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val available = isServiceAvailable()
        val l = lang ?: ""
        return when {
            l.equals("zho", true) || l.equals("chi", true) || l.equals("zh", true) -> {
                if (!available) TextToSpeech.LANG_MISSING_DATA
                else if (country.isNullOrBlank() ||
                    country.equals("CN", true) ||
                    country.equals("Hans", true)
                ) TextToSpeech.LANG_AVAILABLE
                else TextToSpeech.LANG_COUNTRY_AVAILABLE
            }
            l.equals("eng", true) || l.equals("en", true) -> {
                if (!available) TextToSpeech.LANG_MISSING_DATA
                else TextToSpeech.LANG_COUNTRY_AVAILABLE
            }
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onGetLanguage(): Array<String> = arrayOf("zho", "CN", "")

    override fun onStop() {
        coordinator.requestStop()
    }

    override fun onGetVoices(): List<Voice> {
        if (!isServiceAvailable()) return emptyList()
        val kind = ConfigStore.onlineProviderKind()
        if (kind == TtsProviderKind.MIMO) {
            return OnlineVoiceCatalog.MIMO_PRESETS.map { preset ->
                val locale = if (preset.lang == "en") Locale.US else Locale.SIMPLIFIED_CHINESE
                Voice(
                    preset.id,
                    locale,
                    Voice.QUALITY_HIGH,
                    Voice.LATENCY_NORMAL,
                    true,
                    emptySet(),
                )
            }
        }
        val voiceName = ConfigStore.onlineVoice().trim().ifEmpty { kind.defaultVoice }.ifEmpty { "默认在线音色" }
        return listOf(
            Voice(
                voiceName,
                Locale.SIMPLIFIED_CHINESE,
                Voice.QUALITY_HIGH,
                Voice.LATENCY_NORMAL,
                true,
                emptySet(),
            ),
        )
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        if (!isServiceAvailable()) return TextToSpeech.ERROR
        if (voiceName.isNullOrBlank()) return TextToSpeech.ERROR
        return TextToSpeech.SUCCESS
    }

    override fun onLoadVoice(voiceName: String?): Int = onIsValidVoiceName(voiceName)

    override fun onGetDefaultVoiceNameFor(
        lang: String?,
        country: String?,
        variant: String?,
    ): String? {
        if (!isServiceAvailable()) return null
        val kind = ConfigStore.onlineProviderKind()
        return if (kind == TtsProviderKind.MIMO) {
            OnlineVoiceCatalog.resolveMimoVoice(null, ConfigStore.onlineVoice())
        } else {
            ConfigStore.onlineVoice().trim().ifEmpty { kind.defaultVoice }.ifEmpty { "默认在线音色" }
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        @Suppress("DEPRECATION")
        val raw = request.charSequenceText?.toString() ?: request.text ?: ""
        val rate = request.speechRate
        val voice = request.voiceName
        val result = coordinator.synthesize(raw, rate, voice, callback)
        Log.i(
            TAG,
            "synth started=${result.started} err=${result.error} abort=${result.aborted}"
        )
    }
}
