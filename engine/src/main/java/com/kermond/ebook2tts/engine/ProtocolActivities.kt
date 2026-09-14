package com.kermond.ebook2tts.engine

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.io.File

/** CHECK_TTS_DATA：如实返回（RQ-112 / RQ-101） */
class CheckTtsDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hasModel = ModelPresence.hasAnyInstalled(this)
        val result = Intent()
        if (hasModel) {
            result.putExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, "zho-CN")
            setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result)
        } else {
            result.putExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, "zho-CN")
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
    fun hasAnyInstalled(context: android.content.Context): Boolean {
        val models = File(context.filesDir, "models")
        if (!models.isDirectory) return false
        return models.listFiles()?.any { File(it, ".completed").exists() } == true
    }
}
