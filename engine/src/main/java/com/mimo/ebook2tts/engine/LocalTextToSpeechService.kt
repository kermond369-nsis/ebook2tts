package com.mimo.ebook2tts.engine

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
import com.mimo.ebook2tts.core.VoiceCatalog
import java.util.Locale

/**
 * 系统 TTS 服务（AR-§1 / RQ-101）。声明于 :tts_service 进程。
 */
class LocalTextToSpeechService : TextToSpeechService() {

    companion object {
        private const val TAG = "LocalTtsService"
    }

    private lateinit var coordinator: SynthesisCoordinator
    private var reloadReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        MigrationHelper.checkAndMigrate(this)
        coordinator = SynthesisCoordinator(filesDir)
        coordinator.initAsync()
        ConfigStore.setStatusState("INITIALIZING")
        ConfigStore.notifyStatus(this, "INIT")

        val filter = IntentFilter(ConfigStore.ACTION_ENGINE_RELOAD)
        reloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val reason = intent?.getStringExtra("reason") ?: "config"
                Log.i(TAG, "ENGINE_RELOAD reason=$reason")
                coordinator.scheduleReload(reason)
            }
        }
        ContextCompat.registerReceiver(
            this,
            reloadReceiver!!,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ConfigStore.notifyStatus(this, "CREATED")
    }

    override fun onDestroy() {
        coordinator.requestStop()
        reloadReceiver?.let { runCatching { unregisterReceiver(it) } }
        // ADR-008：置停止 → super → 守卫锁内释放（shutdown 内 withLock）
        try {
            super.onDestroy()
        } finally {
            coordinator.shutdown()
        }
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val hasModel = ConfigStore.statusState() != "NO_MODEL"
        val l = lang ?: ""
        return when {
            l.equals("zho", true) || l.equals("chi", true) || l.equals("zh", true) -> {
                if (!hasModel) TextToSpeech.LANG_MISSING_DATA
                else if (country.isNullOrBlank() ||
                    country.equals("CN", true) ||
                    country.equals("Hans", true)
                ) TextToSpeech.LANG_AVAILABLE
                else TextToSpeech.LANG_COUNTRY_AVAILABLE
            }
            l.equals("eng", true) || l.equals("en", true) -> {
                if (!hasModel) TextToSpeech.LANG_MISSING_DATA
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
        if (ConfigStore.statusState() == "NO_MODEL") return emptyList()
        val modelId = ConfigStore.modelId()
        val pool = VoiceCatalog.poolForModel(modelId)
        val quality = if (modelId.startsWith("kokoro")) Voice.QUALITY_HIGH else Voice.QUALITY_NORMAL
        return pool.map { v ->
            Voice(
                // ADR-007：系统侧展示中文名；onSynthesizeText 回传同名，由 VoiceCatalog 兼容解析
                v.displayName,
                Locale.SIMPLIFIED_CHINESE,
                quality,
                Voice.LATENCY_NORMAL,
                false,
                // 离线引擎特性声明（全本地合成，无需网络）
                mutableSetOf(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS)
            )
        }
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        if (ConfigStore.statusState() == "NO_MODEL") return TextToSpeech.ERROR
        val pool = VoiceCatalog.poolForModel(ConfigStore.modelId())
        return if (VoiceCatalog.isValid(pool, voiceName)) TextToSpeech.SUCCESS
        else TextToSpeech.ERROR
    }

    override fun onLoadVoice(voiceName: String?): Int {
        if (ConfigStore.statusState() == "NO_MODEL") return TextToSpeech.ERROR
        return onIsValidVoiceName(voiceName)
    }

    override fun onGetDefaultVoiceNameFor(
        lang: String?,
        country: String?,
        variant: String?,
    ): String? {
        if (ConfigStore.statusState() == "NO_MODEL") return null
        // 返回**展示名**（与 onGetVoices 一致）
        val pool = VoiceCatalog.poolForModel(ConfigStore.modelId())
        return VoiceCatalog.resolve(pool, ConfigStore.narratorVoice()).displayName
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val raw = request.charSequenceText?.toString() ?: request.text ?: ""
        val rate = request.speechRate
        val voice = request.voiceName
        coordinator.resetStop()
        val result = coordinator.synthesize(raw, rate, voice, callback)
        Log.i(
            TAG,
            "synth started=${result.started} err=${result.error} abort=${result.aborted}"
        )
    }
}
