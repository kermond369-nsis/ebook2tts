package com.kermond.ebook2tts.engine

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.kermond.ebook2tts.core.ModelCatalog
import java.io.File

/** CHECK_TTS_DATA：如实返回（RQ-112 / RQ-101） */
class CheckTtsDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val available = ModelPresence.isAnyBackendAvailable(this)
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
        i.putExtra("sampleText", "书声本地，离线朗读测试。第十二章，风起。")
        setResult(Activity.RESULT_OK, i)
        finish()
    }
}

/** INSTALL_TTS_DATA：指向下载引导 */
class InstallTtsDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launch?.putExtra("route", "download")
        if (launch != null) startActivity(launch)
        finish()
    }
}

object ModelPresence {
    fun hasAnyInstalled(context: Context): Boolean {
        val models = File(context.filesDir, "models")
        if (!models.isDirectory) return false
        return models.listFiles()?.any { File(it, ".completed").exists() } == true
    }

    fun isActiveModelInstalled(context: Context): Boolean {
        val spec = ModelCatalog.byId(ConfigStore.modelId())
        val dir = File(context.filesDir, "models/${spec.id}")
        val model = File(dir, spec.files.modelName)
        if (!model.exists() || model.length() <= 0L) return false
        if (spec.files.vocoder.isNotEmpty()) {
            val voc = File(dir, spec.files.vocoder)
            if (!voc.exists() || voc.length() <= 0L) return false
        }
        return true
    }

    fun isOnlineConfigured(): Boolean = OnlineSelector.isOnlineConfigured(
        routeMode = ConfigStore.routeMode(),
        onlineEnabled = ConfigStore.onlineEnabled(),
        apiKey = ConfigStore.onlineApiKey(),
        keyKind = ConfigStore.onlineKeyKind(),
        tokenPlanAccepted = ConfigStore.onlineTokenPlanAccepted(),
        providerKind = ConfigStore.onlineProviderKind(),
        baseUrl = ConfigStore.onlineBaseUrl(),
    )

    fun isAnyBackendAvailable(context: Context): Boolean {
        val mode = ConfigStore.routeMode()
        val localOk = mode.allowsLocal && (isActiveModelInstalled(context) || hasAnyInstalled(context))
        return localOk || isOnlineConfigured()
    }
}
