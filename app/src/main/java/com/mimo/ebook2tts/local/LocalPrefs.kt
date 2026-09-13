package com.mimo.ebook2tts.local

import android.content.Context
import android.content.SharedPreferences
import com.mimo.ebook2tts.local.model.ModelCatalog

/** 本地版设置。无需 API Key。 */
object LocalPrefs {
    private const val NAME = "ebook2tts_local_prefs"

    private const val KEY_BACKEND = "backend"
    private const val KEY_MODEL_ID = "model_id"
    private const val KEY_NARRATOR_VOICE = "narrator_voice"
    private const val KEY_SMART_CHARACTER = "smart_character"
    private const val KEY_EMOTION = "emotion"
    private const val KEY_NUM_THREADS = "num_threads"
    private const val KEY_SPEED = "speed"
    private const val KEY_BUFFER = "buffer_size"
    private const val KEY_CUSTOM_URL_PREFIX = "custom_url_"

    fun customUrl(c: Context, modelId: String): String =
        prefs(c).getString(KEY_CUSTOM_URL_PREFIX + modelId, "") ?: ""

    fun setCustomUrl(c: Context, modelId: String, url: String) =
        prefs(c).edit().putString(KEY_CUSTOM_URL_PREFIX + modelId, url.trim()).apply()

    fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun backend(c: Context): String =
        prefs(c).getString(KEY_BACKEND, Backend.SHERPA.id) ?: Backend.SHERPA.id

    fun setBackend(c: Context, v: String) = prefs(c).edit().putString(KEY_BACKEND, v).apply()

    fun modelId(c: Context): String =
        prefs(c).getString(KEY_MODEL_ID, ModelCatalog.DEFAULT_ID) ?: ModelCatalog.DEFAULT_ID

    fun setModelId(c: Context, v: String) = prefs(c).edit().putString(KEY_MODEL_ID, v).apply()

    fun narratorVoice(c: Context): String =
        prefs(c).getString(KEY_NARRATOR_VOICE, "zm_yunyang") ?: "zm_yunyang"

    fun setNarratorVoice(c: Context, v: String) =
        prefs(c).edit().putString(KEY_NARRATOR_VOICE, v).apply()

    fun smartCharacter(c: Context): Boolean =
        prefs(c).getBoolean(KEY_SMART_CHARACTER, true)

    fun setSmartCharacter(c: Context, v: Boolean) =
        prefs(c).edit().putBoolean(KEY_SMART_CHARACTER, v).apply()

    fun emotionEnabled(c: Context): Boolean =
        prefs(c).getBoolean(KEY_EMOTION, true)

    fun setEmotionEnabled(c: Context, v: Boolean) =
        prefs(c).edit().putBoolean(KEY_EMOTION, v).apply()

    fun numThreads(c: Context): Int =
        prefs(c).getInt(KEY_NUM_THREADS, 2).coerceIn(1, 4)

    fun setNumThreads(c: Context, v: Int) =
        prefs(c).edit().putInt(KEY_NUM_THREADS, v.coerceIn(1, 4)).apply()

    fun speed(c: Context): Float =
        prefs(c).getFloat(KEY_SPEED, 1.0f).coerceIn(0.5f, 2.0f)

    fun setSpeed(c: Context, v: Float) =
        prefs(c).edit().putFloat(KEY_SPEED, v.coerceIn(0.5f, 2.0f)).apply()

    fun bufferSize(c: Context): Int =
        prefs(c).getInt(KEY_BUFFER, 8).coerceIn(1, 32)

    fun setBufferSize(c: Context, v: Int) =
        prefs(c).edit().putInt(KEY_BUFFER, v.coerceIn(1, 32)).apply()

    enum class Backend(val id: String, val label: String) {
        SHERPA("sherpa", "本地模型（SherpaONNX）"),
        SYSTEM("system", "系统 TTS（机械音兜底）");

        companion object {
            fun byId(id: String?): Backend =
                entries.firstOrNull { it.id == id } ?: SHERPA
        }
    }
}
