package com.kermond.ebook2tts.engine

import com.kermond.ebook2tts.core.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * 清单编解码与校验（IM-201）。
 *
 * 关键防线：
 * - 非法清单必须整份作废（不得部分采纳）；
 * - 仓库根 `manifest.json` 与内置兜底 `ModelCatalog` **逐字段一致**（防漂移）。
 */
class ManifestCodecTest {

    private val sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    private fun entry(
        id: String = "demo",
        url: String = "https://example.com/demo.tar.bz2",
        sha256: String = sha,
        downloadBytes: Long = 100L,
        extractedBytes: Long = 200L,
        mirrors: String = "\"https://m1.example.com/demo.tar.bz2\",\"https://m2.example.com/demo.tar.bz2\"",
    ) = """
        {
          "id":"$id","label":"示例","desc":"d","archive":"demo.tar.bz2",
          "url":"$url","mirrors":[$mirrors],"sha256":"$sha256",
          "downloadBytes":$downloadBytes,"extractedBytes":$extractedBytes,
          "files":{"kind":"kokoro","model":"model.onnx","voices":"voices.bin"},
          "recommended":true
        }
    """.trimIndent()

    private fun manifest(
        version: Long = 2026091302L,
        schema: Int = 1,
        models: String = entry(),
    ) = """{"schema":$schema,"version":$version,"updatedAt":"2026-09-14","models":[$models]}"""

    @Test
    fun parse_valid() {
        val m = ManifestCodec.parse(manifest())
        assertNotNull(m)
        val model = m!!.models.single()
        assertEquals(2026091302L, m.version)
        assertEquals("2026-09-14", m.updatedAt)
        assertEquals("demo", model.id)
        assertEquals(100L, model.downloadBytes)
        assertEquals(200L, model.extractedBytes)
        assertEquals("model.onnx", model.files.modelName)
        assertEquals("tokens.txt", model.files.tokens) // 缺省字段
        // mirrors[0] 入官方镜像位，其余并入附加源
        assertEquals("https://m1.example.com/demo.tar.bz2", model.mirrorUrl)
        assertEquals(listOf("https://m2.example.com/demo.tar.bz2"), model.extraMirrors)
        assertEquals(3, model.sources.size)
        assertEquals("demo", m.byId("demo")?.id)
        assertEquals(null, m.byId("nope"))
    }

    /** 六类非法清单：任一命中都必须整份作废（返回 null） */
    @Test
    fun parse_rejectsIllegal() {
        val cases = listOf(
            "schema 不符" to manifest(schema = 2),
            "版本非正" to manifest(version = 0L),
            "无 models" to manifest(models = ""),
            "非 https 源" to manifest(models = entry(url = "http://example.com/demo.tar.bz2")),
            "sha 非法" to manifest(models = entry(sha256 = "deadbeef")),
            "体积为零" to manifest(models = entry(downloadBytes = 0L)),
            "体积超上限" to manifest(models = entry(extractedBytes = 9L * 1024 * 1024 * 1024)),
            "id 为空" to manifest(models = entry(id = " ")),
        )
        for ((name, json) in cases) {
            assertNull("应拒绝：$name", ManifestCodec.parse(json))
        }
    }

    @Test
    fun parse_rejectsDuplicateId() {
        assertNull(ManifestCodec.parse(manifest(models = "${entry()},${entry()}")))
    }

    @Test
    fun parse_rejectsGarbage() {
        assertNull(ManifestCodec.parse("not json"))
        assertNull(ManifestCodec.parse("{}"))
        assertNull(ManifestCodec.parse(""))
    }

    /** 防漂移：仓库根 manifest.json ⟷ 内置兜底 ModelCatalog（含实测字节与 sha256） */
    @Test
    fun rootManifest_matchesBuiltin() {
        val file = listOf("manifest.json", "../manifest.json", "../../manifest.json")
            .map { File(it) }.firstOrNull { it.exists() }
        assertNotNull("仓库根 manifest.json 未找到", file)
        val parsed = ManifestCodec.parse(file!!.readText())
        assertNotNull(parsed)
        val builtin = ModelCatalog.manifest()
        assertEquals(builtin.version, parsed!!.version)
        assertEquals(builtin.updatedAt, parsed.updatedAt)
        assertEquals(builtin.models, parsed.models)
    }
}
