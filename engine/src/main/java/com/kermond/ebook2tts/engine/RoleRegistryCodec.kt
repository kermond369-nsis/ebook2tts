package com.kermond.ebook2tts.engine

import com.kermond.ebook2tts.core.OnlineRolePolicy
import com.kermond.ebook2tts.core.RoleVoiceDesign
import org.json.JSONObject

/**
 * 角色库编解码（ADR-013）：MMKV 单键整体 JSON。
 *
 * 分层：`RoleVoiceDesign` 是 `:core` 的纯数据；JSON 解析在本层（Android 提供 org.json，
 * 与 `ModelManifest` → `ManifestCodec` 的既有分工一致）。
 *
 * 纪律：**任何畸形整份作废**（返回空表），绝不半采纳；解析失败不抛异常。
 */
object RoleRegistryCodec {

    fun encodeAll(profiles: Map<String, RoleVoiceDesign>): String {
        val root = JSONObject()
        for ((name, p) in profiles) {
            if (name.isBlank()) continue
            root.put(
                name,
                JSONObject()
                    .put("gender", p.gender)
                    .put("ageHint", p.ageHint)
                    .put("personality", p.personality)
                    .put("speechStyle", p.speechStyle)
                    .put("toneTags", p.toneTags)
                    .put("design", p.design),
            )
        }
        return root.toString()
    }

    fun decodeAll(json: String?): Map<String, RoleVoiceDesign> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val root = JSONObject(json)
            val out = LinkedHashMap<String, RoleVoiceDesign>()
            val it = root.keys()
            while (it.hasNext()) {
                val name = it.next()
                val o = root.optJSONObject(name) ?: continue
                out[name] = RoleVoiceDesign(
                    name = name,
                    gender = OnlineRolePolicy.normalizeGender(o.optString("gender")),
                    ageHint = o.optString("ageHint"),
                    personality = o.optString("personality"),
                    speechStyle = o.optString("speechStyle"),
                    toneTags = o.optString("toneTags"),
                    design = OnlineRolePolicy.sanitizeDesign(o.optString("design")),
                )
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * 解析 LLM 返回（可能含 ```json 围栏与前后废话）→ 档案；失败返回 `null`
     * （调用方静默放弃该角色，朗读零影响）。
     */
    fun parseLlm(raw: String, fallbackName: String): RoleVoiceDesign? {
        val cleaned = raw
            .replace("```json", " ", ignoreCase = true)
            .replace("```", " ")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val o = JSONObject(cleaned.substring(start, end + 1))
            RoleVoiceDesign(
                name = o.optString("name").trim().ifBlank { fallbackName },
                gender = OnlineRolePolicy.normalizeGender(o.optString("gender")),
                ageHint = o.optString("ageHint").trim(),
                personality = o.optString("personality").trim(),
                speechStyle = o.optString("speechStyle").trim(),
                toneTags = o.optString("toneTags").trim(),
                design = OnlineRolePolicy.sanitizeDesign(
                    o.optString("voiceDesign").ifBlank { o.optString("design") },
                ),
            )
        } catch (_: Exception) {
            null
        }
    }
}
