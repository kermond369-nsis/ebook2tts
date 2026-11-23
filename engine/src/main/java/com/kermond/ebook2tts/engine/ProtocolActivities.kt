package com.kermond.ebook2tts.engine

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/** CHECK_TTS_DATA：当在线已就绪或存在系统 TTS 兜底引擎时通过 */
class CheckTtsDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val available = EngineAvailability.isAvailable(this)
        val result = Intent()
        val voices = arrayListOf("zho-CN", "eng-US")
        if (available) {
            result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, voices)
            setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result)
        } else {
            result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, voices)
            setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL, result)
        }
        finish()
    }
}

/** GET_SAMPLE_TEXT */
class GetSampleTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = Intent()
        i.putExtra("sampleText", "书声朗读测试。第十二章，风起。")
        setResult(Activity.RESULT_OK, i)
        finish()
    }
}

/** INSTALL_TTS_DATA：引导进入应用首页配置在线引擎 */
class InstallTtsDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch != null) startActivity(launch)
        finish()
    }
}

object EngineAvailability {
    fun isOnlineConfigured(): Boolean = OnlineSelector.isOnlineConfigured(
        onlineEnabled = ConfigStore.onlineEnabled(),
        apiKey = ConfigStore.onlineApiKey(),
        keyKind = ConfigStore.onlineKeyKind(),
        tokenPlanAccepted = ConfigStore.onlineTokenPlanAccepted(),
        providerKind = ConfigStore.onlineProviderKind(),
        baseUrl = ConfigStore.onlineBaseUrl(),
    )

    fun isAvailable(context: Context): Boolean =
        isOnlineConfigured() || SystemTtsDiscovery.hasFallbackEngine(context)
}
