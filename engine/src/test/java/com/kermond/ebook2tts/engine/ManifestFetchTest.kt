package com.kermond.ebook2tts.engine

import com.kermond.ebook2tts.core.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 清单远程取用链（IM-201）：来源逐个尝试、非法整份作废、版本下限防回滚。
 * 纯逻辑测试（注入内存 ManifestSource），不触网、不依赖设备。
 */
class ManifestFetchTest {

    private val sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    private fun json(version: Long, id: String = "demo", url: String = "https://example.com/a.tar.bz2") = """
        {"schema":1,"version":$version,"updatedAt":"2026-09-14","models":[
          {"id":"$id","label":"l","desc":"d","archive":"a.tar.bz2","url":"$url",
           "sha256":"$sha","downloadBytes":10,"extractedBytes":20,
           "files":{"kind":"vits","model":"model.onnx"}}]}
    """.trimIndent()

    private class MapSource(private val map: Map<String, String?>) : ManifestSource {
        val hits = mutableListOf<String>()
        override fun fetch(url: String): String? {
            hits += url
            return map[url]
        }
    }

    private val urls = listOf("https://raw.example/m.json", "https://ghfast.top/https://raw.example/m.json")

    @Test
    fun officialFirst_winsWhenValid() {
        val src = MapSource(mapOf(urls[0] to json(2026091401L)))
        val picked = ManifestRepository.pick(src, urls)
        assertEquals(urls[0], src.hits.first())
        assertEquals(1, src.hits.size)
        assertEquals(2026091401L, picked!!.second.version)
    }

    @Test
    fun firstSourceBroken_fallsThroughToAccelerator() {
        val src = MapSource(
            mapOf(
                urls[0] to "not json", // 官方通道被劫持/损坏
                urls[1] to json(2026091401L, id = "via-accel"),
            )
        )
        val picked = ManifestRepository.pick(src, urls)
        assertEquals(listOf(urls[0], urls[1]), src.hits)
        assertEquals("via-accel", picked!!.second.models.single().id)
    }

    @Test
    fun rollbackVersion_rejected() {
        val floor = ModelCatalog.BUILTIN_MANIFEST_VERSION
        val src = MapSource(mapOf(urls[0] to json(floor - 1), urls[1] to json(floor)))
        val picked = ManifestRepository.pick(src, urls)
        assertEquals("版本低于下限必须被拒并继续尝试下一来源", floor, picked!!.second.version)
        assertEquals(listOf(urls[0], urls[1]), src.hits)
    }

    @Test
    fun allSourcesFail_null() {
        val src = MapSource(mapOf(urls[0] to null, urls[1] to "{}"))
        assertNull(ManifestRepository.pick(src, urls))
        // 非法清单不得残留副作用：来源被逐个尝试即止
        assertEquals(2, src.hits.size)
    }
}
