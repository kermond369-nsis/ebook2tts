package com.mimo.ebook2tts.engine

import com.mimo.ebook2tts.core.ModelFiles
import com.mimo.ebook2tts.core.ModelLimits
import com.mimo.ebook2tts.core.ModelManifest
import com.mimo.ebook2tts.core.ModelSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * 清单编解码与严格校验（IM-201 / AR-§6.2）。
 *
 * 安全口径：**任一字段不合法 → 整份清单作废（返回 null）**，由调用方回退到缓存或内置清单；
 * 绝不接受「部分可用」的清单（伪造清单可能塞入超大 SHA/URL/体积，导致爆盘或下载投毒）。
 */
object ManifestCodec {

    /** 当前支持的清单 schema 版本 */
    const val SCHEMA = 1

    fun parse(json: String): ModelManifest? = runCatching {
        val root = JSONObject(json)
        val schema = root.optInt("schema", -1)
        require(schema == SCHEMA) { "schema=$schema" }
        val version = root.optLong("version", -1L)
        require(version > 0L) { "version=$version" }
        val arr = root.optJSONArray("models") ?: throw IllegalArgumentException("models missing")
        require(arr.length() > 0) { "models empty" }
        val models = (0 until arr.length()).map { entry(arr.getJSONObject(it)) }
        require(models.map { it.id }.distinct().size == models.size) { "duplicate id" }
        ModelManifest(
            version = version,
            updatedAt = root.optString("updatedAt", ""),
            models = models,
        )
    }.getOrNull()

    private fun entry(o: JSONObject): ModelSpec {
        val id = o.getString("id").trim()
        require(id.isNotEmpty()) { "id empty" }
        val downloadBytes = o.getLong("downloadBytes")
        val extractedBytes = o.getLong("extractedBytes")
        require(downloadBytes in 1..ModelLimits.MAX_BYTES) { "downloadBytes=$downloadBytes" }
        require(extractedBytes in 1..ModelLimits.MAX_BYTES) { "extractedBytes=$extractedBytes" }
        val sha256 = o.getString("sha256").trim().lowercase()
        require(ModelLimits.SHA256.matches(sha256)) { "sha256 malformed" }
        val primary = requireHttps(o.getString("url"))
        val mirrorsJson = o.optJSONArray("mirrors") ?: JSONArray()
        val mirrors = (0 until mirrorsJson.length()).map { requireHttps(mirrorsJson.getString(it)) }
        val archive = o.getString("archive").trim().also { require(it.isNotEmpty()) { "archive empty" } }
        val f = o.getJSONObject("files")
        val kind = f.getString("kind").trim().also { require(it.isNotEmpty()) { "files.kind empty" } }
        val modelName = f.getString("model").trim().also { require(it.isNotEmpty()) { "files.model empty" } }
        return ModelSpec(
            id = id,
            label = o.optString("label", id),
            desc = o.optString("desc", ""),
            archiveName = archive,
            primaryUrl = primary,
            mirrorUrl = mirrors.firstOrNull() ?: "",
            extraMirrors = if (mirrors.size > 1) mirrors.drop(1) else emptyList(),
            downloadBytes = downloadBytes,
            extractedBytes = extractedBytes,
            sha256 = sha256,
            files = ModelFiles(
                kind = kind,
                modelName = modelName,
                voices = f.optString("voices", ""),
                lexicon = f.optString("lexicon", ""),
                dataDir = f.optString("dataDir", ""),
                ruleFsts = f.optString("ruleFsts", ""),
                tokens = f.optString("tokens", "tokens.txt"),
            ),
            recommended = o.optBoolean("recommended", false),
            highEndOnly = o.optBoolean("highEndOnly", false),
            lowEndDefault = o.optBoolean("lowEndDefault", false),
        )
    }

    /** 下载源强制 https（清单与自定义镜像一律 https：应用 `usesCleartextTraffic=false`，明文必被系统拒绝） */
    private fun requireHttps(url: String): String {
        val s = url.trim()
        require(s.startsWith("https://") && s.length > "https://".length) { "non-https url" }
        return s
    }
}
