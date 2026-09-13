package com.mimo.ebook2tts.local.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/**
 * 系统 TTS 设置会发 ACTION_CHECK_TTS_DATA。
 * 无此 Activity 时 speakSampleText 可能直接失败、点「播放」无声。
 */
class CheckTtsDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val available = arrayListOf("zho-CN", "zho", "eng-USA")
        val unavailable = arrayListOf<String>()
        val data = Intent()
        data.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available)
        data.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, unavailable)
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, data)
        finish()
    }
}

/** 系统设置请求示例文本 */
class GetSampleTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = Intent()
        data.putExtra(
            TextToSpeech.Engine.EXTRA_SAMPLE_TEXT,
            "书声本地引擎测试。林晓站定，说道：“我答应过的事，就一定会做。”"
        )
        setResult(Activity.RESULT_OK, data)
        finish()
    }
}
