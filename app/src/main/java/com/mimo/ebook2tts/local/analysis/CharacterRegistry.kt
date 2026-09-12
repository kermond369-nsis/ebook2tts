package com.mimo.ebook2tts.local.analysis

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 角色档案：机械分析 + MiMo 精标结论，供 TTS 查表。
 */
data class CharacterProfile(
    val name: String,
    val gender: String = "unknown", // male | female | unknown
    val ageHint: String = "",
    val personality: String = "",
    val speechStyle: String = "",
    val relation: String = "",
    val toneTags: String = "",
    /** voicedesign 描述；非空则走 mimo-v2.5-tts-voicedesign */
    val voiceDesign: String = "",
    val builtinVoice: String? = null,
    val refined: Boolean = false,
    /** 精标前累计出现次数 / 字数，用于触发阈值 */
    val mentionCount: Int = 0,
    val pendingChars: Int = 0,
    /** 书名/章节提示，换书可重置，避免串戏 */
    val bookHint: String = "",
    /** 手动锁定：不被自动清理，也不被 AI 精标覆盖 */
    val locked: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toStyleInstruction(emotionLabel: String, isNarrator: Boolean): String {
        if (voiceDesign.isNotBlank()) {
            return if (isNarrator) "旁白，$emotionLabel" else "$emotionLabel，小说角色对白"
        }
        return buildString {
            append(personality.ifBlank { "自然" })
            if (speechStyle.isNotBlank()) { append("，"); append(speechStyle) }
            append("，")
            append(emotionLabel)
            append(if (isNarrator) "，旁白" else "，对白")
        }
    }
}

/**
 * 本地角色查表 + JSON 持久化。
 */
class CharacterRegistry(context: Context) {
    private val file = File(context.filesDir, "cast_profiles.json")
    private val lock = Any()
    private val map = LinkedHashMap<String, CharacterProfile>()

    init {
        load()
    }

    fun get(name: String): CharacterProfile? = synchronized(lock) { map[name] }

    fun put(profile: CharacterProfile) {
        synchronized(lock) {
            map[profile.name] = profile
            saveLocked()
        }
    }

    fun all(): List<CharacterProfile> = synchronized(lock) { map.values.toList() }

    fun knownNames(): Set<String> = synchronized(lock) { map.keys.toSet() }

    fun delete(name: String): Boolean = synchronized(lock) {
        val ok = map.remove(name) != null
        if (ok) saveLocked()
        ok
    }

    /** 自动清理：只删未锁定角色 */
    fun clearUnlocked(): Int = synchronized(lock) {
        val doomed = map.values.filter { !it.locked }.map { it.name }
        for (n in doomed) map.remove(n)
        if (doomed.isNotEmpty()) saveLocked()
        doomed.size
    }

    fun clearAll() = synchronized(lock) {
        map.clear()
        saveLocked()
    }

    fun setLocked(name: String, locked: Boolean): Boolean = synchronized(lock) {
        val p = map[name] ?: return false
        map[name] = p.copy(locked = locked, updatedAt = System.currentTimeMillis())
        saveLocked()
        true
    }

    fun saveProfile(profile: CharacterProfile) = put(profile)

    private fun load() {
        try {
            if (!file.exists()) return
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val p = CharacterProfile(
                    name = o.optString("name"),
                    gender = o.optString("gender", "unknown"),
                    ageHint = o.optString("ageHint"),
                    personality = o.optString("personality"),
                    speechStyle = o.optString("speechStyle"),
                    relation = o.optString("relation"),
                    toneTags = o.optString("toneTags"),
                    voiceDesign = o.optString("voiceDesign"),
                    builtinVoice = if (o.isNull("builtinVoice")) null else o.optString("builtinVoice"),
                    refined = o.optBoolean("refined"),
                    mentionCount = o.optInt("mentionCount"),
                    pendingChars = o.optInt("pendingChars"),
                    bookHint = o.optString("bookHint"),
                    locked = o.optBoolean("locked"),
                    updatedAt = o.optLong("updatedAt"),
                )
                if (p.name.isNotBlank()) map[p.name] = p
            }
        } catch (e: Exception) {
            Log.w("CastRegistry", "load failed: ${e.message}")
        }
    }

    private fun saveLocked() {
        try {
            val arr = JSONArray()
            for (p in map.values) {
                arr.put(JSONObject().apply {
                    put("name", p.name)
                    put("gender", p.gender)
                    put("ageHint", p.ageHint)
                    put("personality", p.personality)
                    put("speechStyle", p.speechStyle)
                    put("relation", p.relation)
                    put("toneTags", p.toneTags)
                    put("voiceDesign", p.voiceDesign)
                    if (p.builtinVoice != null) put("builtinVoice", p.builtinVoice) else put("builtinVoice", JSONObject.NULL)
                    put("refined", p.refined)
                    put("mentionCount", p.mentionCount)
                    put("pendingChars", p.pendingChars)
                    put("bookHint", p.bookHint)
                    put("locked", p.locked)
                    put("updatedAt", p.updatedAt)
                })
            }
            file.writeText(arr.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w("CastRegistry", "save failed: ${e.message}")
        }
    }
}
