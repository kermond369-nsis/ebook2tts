package com.kermond.ebook2tts.engine

import android.content.Context
import android.util.Log
import com.kermond.ebook2tts.core.OnlineRolePolicy
import com.kermond.ebook2tts.core.OnlineSettings
import com.kermond.ebook2tts.core.RoleRefinePrompt
import com.kermond.ebook2tts.core.RoleVoiceDesign
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 角色精标（RQ-507 / ADR-013）：本机文本缓存 → 文本 LLM 提炼角色档案（含 voicedesign 音色描述）。
 *
 * **进程纪律（红线 4）**：本类**只允许在主进程执行**（`:tts_service` 内零 LLM/零额外推理）。
 * 入口自带进程守卫 [RoleRegistry.mayRefineInThisProcess]，误调用时直接拒绝并打点。
 *
 * 协议（**逐字复用**甲方原项目已实锤的 `MiMoApiClient.chat` 形态，不发明新字段）：
 * `POST {base}/chat/completions`，body = `model` / `messages[system,user]` / `temperature` /
 * `max_completion_tokens` / `thinking{type:disabled}`。
 *
 * 纪律：
 * - 限量（[OnlineRolePolicy.MAX_ROLES_PER_CALL]）＋限频（[OnlineRolePolicy.MIN_REFINE_INTERVAL_MS]）；
 * - 失败**静默**（记录短原因，绝不抛给调用方，朗读零影响）；
 * - 日志一律脱敏：只记角色名与描述长度，**不记密钥、不记正文**。
 */
class OnlineRoleRefiner(
    private val context: Context,
    private val client: OkHttpClient = defaultClient(),
) {

    data class Result(val refined: Int, val skipped: String?)

    fun refinePending(): Result {
        if (!RoleRegistry.mayRefineInThisProcess(context)) {
            Log.w(TAG, "ROLE|refine|refused=engine_process")
            return Result(0, "engine_process")
        }
        if (!ConfigStore.onlineEnabled()) return Result(0, "online_disabled")
        if (!ConfigStore.roleVoiceEnabled()) return Result(0, "role_disabled")
        val key = ConfigStore.onlineApiKey().trim()
        if (key.isEmpty()) return Result(0, "no_key")
        val kind = ConfigStore.onlineKeyKind()
        if (OnlineSettings.normalizeKind(kind) == OnlineSettings.KIND_PLAN &&
            !ConfigStore.onlineTokenPlanAccepted()
        ) {
            return Result(0, "tokenplan_not_accepted")
        }
        val now = System.currentTimeMillis()
        if (!OnlineRolePolicy.mayRefineNow(ConfigStore.roleLastRefineAt(), now)) {
            return Result(0, "throttled")
        }

        val pending = OnlineRolePolicy.pendingRoles(
            candidates = RoleRegistry.candidates(),
            refined = RoleRegistry.refinedNames(),
        )
        if (pending.isEmpty()) return Result(0, "no_pending")

        // 限频打点先落：无论成败都不再短时间内重复调用（防重试风暴）
        ConfigStore.setRoleLastRefineAt(now)

        val base = OnlineSettings.resolveBaseUrl(kind, ConfigStore.onlineBaseUrl()).trimEnd('/')
        val model = ConfigStore.onlineLlmModel()
        val excerpt = RoleRegistry.excerpt()
        val known = RoleRegistry.snapshot().keys.toList()

        var done = 0
        for (name in pending) {
            val profile = refineOne(base, key, model, name, excerpt, known)
            if (profile == null) {
                Log.w(TAG, "ROLE|refine|failed|name=$name")
                continue
            }
            if (RoleRegistry.put(profile)) {
                ConfigStore.bumpRoleRefineCount()
                done++
                Log.i(TAG, "ROLE|refined|name=${profile.name}|design_len=${profile.design.length}")
            }
        }
        return Result(done, if (done == 0) "all_failed" else null)
    }

    private fun refineOne(
        base: String,
        key: String,
        model: String,
        name: String,
        excerpt: String,
        known: List<String>,
    ): RoleVoiceDesign? = try {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", RoleRefinePrompt.system()))
            .put(
                JSONObject().put("role", "user")
                    .put("content", RoleRefinePrompt.user(name, excerpt, known)),
            )
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.3)
            .put("max_completion_tokens", 800)
            .put("thinking", JSONObject().put("type", "disabled"))
        val body = payload.toString().toRequestBody(JSON_MEDIA)
        val req = Request.Builder()
            .url("$base/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // 只记状态码，不记响应体（可能含回显正文）
                Log.w(TAG, "ROLE|refine|http=${resp.code}|name=$name")
                return null
            }
            val content = JSONObject(raw)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty()
            val parsed = RoleRegistryCodec.parseLlm(content, name) ?: return null
            val withDesign = if (parsed.hasDesign) {
                parsed
            } else {
                parsed.copy(design = fallbackDesign(parsed))
            }
            withDesign.copy(name = parsed.name.ifBlank { name })
        }
    } catch (t: Throwable) {
        Log.w(TAG, "ROLE|refine|error|name=$name|${t.javaClass.simpleName}")
        null
    }

    /**
     * 兜底音色描述（模型没给/给了空）：用已解析出的性别·性格·说话风格拼一句。
     * 目的仅是"可区分"，不追求精细（甲方已明确允许偏差）。
     */
    private fun fallbackDesign(p: RoleVoiceDesign): String {
        val gender = when (p.gender) {
            "female" -> "女性"
            "male" -> "男性"
            else -> "中性"
        }
        val parts = listOfNotNull(
            p.ageHint.takeIf { it.isNotBlank() },
            gender,
            p.personality.takeIf { it.isNotBlank() },
            p.speechStyle.takeIf { it.isNotBlank() },
        )
        return OnlineRolePolicy.sanitizeDesign(
            parts.joinToString("，") + "，自然的生活化说话感，像小说人物对白。",
        )
    }

    companion object {
        private const val TAG = "RoleRefiner"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
