package com.kermond.ebook2tts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VoiceCatalogTest {

    @Test
    fun kokoro_has103() {
        val pool = VoiceCatalog.kokoroVoices()
        assertEquals(103, pool.size)
        assertEquals(0, pool.first().speakerId)
        assertEquals(102, pool.last().speakerId)
    }

    @Test
    fun defaultNarrator_is_zm58() {
        val pool = VoiceCatalog.kokoroVoices()
        val d = VoiceCatalog.defaultNarrator(pool)
        assertEquals("zm_58", d.id)
        assertEquals(58, d.speakerId)
    }

    @Test
    fun legacy_zm_058_stillValid() {
        val pool = VoiceCatalog.kokoroVoices()
        assertTrue(VoiceCatalog.isValid(pool, "zm_058"))
        val v = VoiceCatalog.resolve(pool, "zm_058")
        assertEquals("zm_58", v.id)
    }

    @Test
    fun vits5() {
        assertEquals(5, VoiceCatalog.vitsZhLlVoices().size)
    }
}

class RoleAssignerTest {

    private val pool = VoiceCatalog.kokoroVoices()

    @Test
    fun emptyPool_noCrash() {
        val a = RoleAssigner(emptyList())
        val v = a.assign(TextSegment(SegmentKind.DIALOGUE, "你好"))
        // empty pool falls back to narrator (defaultNarrator of empty - need guard)
        assertTrue(v.speakerId >= 0)
    }

    @Test
    fun singleGender_noModuloCrash() {
        val females = pool.filter { it.gender == VoiceGender.FEMALE }
        val a = RoleAssigner(females)
        repeat(10) {
            a.assign(TextSegment(SegmentKind.DIALOGUE, "话$it", speakerHint = "小明"))
        }
    }

    @Test
    fun smart_assignsDifferentForNamed() {
        val a = RoleAssigner(pool)
        val v1 = a.assign(TextSegment(SegmentKind.DIALOGUE, "…", speakerHint = "张三"), nowMs = 1000)
        val v2 = a.assign(TextSegment(SegmentKind.DIALOGUE, "…", speakerHint = "李四"), nowMs = 2000)
        assertTrue(v1.id != v2.id || pool.size < 2)
    }

    @Test
    fun respectReader_allNarrator() {
        val a = RoleAssigner(pool, mode = RoleMode.RESPECT_READER)
        val n = a.assign(TextSegment(SegmentKind.DIALOGUE, "…", speakerHint = "张三"))
        val m = a.assign(TextSegment(SegmentKind.NARRATION, "旁白"))
        assertEquals(n.id, m.id)
    }

    @Test
    fun silenceResets() {
        val a = RoleAssigner(pool)
        a.assign(TextSegment(SegmentKind.DIALOGUE, "…", speakerHint = "张三"), nowMs = 0)
        a.assign(TextSegment(SegmentKind.DIALOGUE, "…", speakerHint = "李四"), nowMs = 300_000)
        // should not crash; known speakers reset may reassign
        assertTrue(true)
    }
}

class ModelCatalogTest {

    @Test
    fun measuredSizes() {
        val int8 = ModelCatalog.byId("kokoro-int8")
        assertEquals(147_031_220L, int8.downloadBytes)
        assertEquals(215_321_602L, int8.extractedBytes)
        val ll = ModelCatalog.byId("vits-zh-ll")
        assertEquals(118_810_709L, ll.downloadBytes)
        val fp = ModelCatalog.byId("kokoro-fp32")
        assertEquals(364_816_464L, fp.downloadBytes)
    }

    @Test
    fun spacePrecheck_longMath() {
        // zh-ll: 135457418 * 2.5 = 338643545
        assertEquals(338_643_545L, ModelCatalog.requiredSpaceBytes(135_457_418L))
        assertTrue(ModelCatalog.hasEnoughSpace(135_457_418L, 338_643_545L))
        assertFalse(ModelCatalog.hasEnoughSpace(135_457_418L, 200_000_000L))
    }

    @Test
    fun spacePrecheck_allModels_matchErrata001() {
        // ERR-001 §4：int8 538MB / fp32 1067MB / zh-ll 339MB（全程 Long，勿溢出）
        assertEquals(538_304_005L, ModelCatalog.requiredSpaceBytes(215_321_602L))
        assertEquals(1_066_635_940L, ModelCatalog.requiredSpaceBytes(426_654_376L))
        assertEquals(338_643_545L, ModelCatalog.requiredSpaceBytes(135_457_418L))
    }

    @Test
    fun builtinManifest_isWellFormed() {
        val m = ModelCatalog.manifest()
        assertEquals(ModelCatalog.BUILTIN_MANIFEST_VERSION, m.version)
        assertEquals(listOf("kokoro-int8", "vits-zh-ll", "kokoro-fp32"), m.models.map { it.id })
        assertEquals(ModelCatalog.DEFAULT_ID, m.byId(ModelCatalog.DEFAULT_ID)?.id)
        // 每个模型都必须有可测速的源，且主源为 https
        for (spec in m.models) {
            assertTrue(spec.sources.isNotEmpty())
            assertTrue(spec.sources.all { it.startsWith("https://") })
            assertTrue(spec.downloadBytes > 0 && spec.extractedBytes > 0)
            assertTrue(ModelLimits.SHA256.matches(spec.sha256))
        }
    }
}

class MirrorResolverTest {

    private val spec = ModelCatalog.ALL.first()

    @Test
    fun noCustom_givesOfficialSources() {
        // 基线＝内置清单自带源（官方主源 + 加速镜像，数量随清单演进，勿写死）
        val base = spec.sources
        assertTrue(base.size >= 2)
        assertEquals(spec.primaryUrl, base.first())
        assertEquals(base, MirrorResolver.sources(spec, null))
        assertEquals(base, MirrorResolver.sources(spec, "   "))
        // 无协议前缀 → 视为非法配置，忽略
        assertEquals(base, MirrorResolver.sources(spec, "mirror.lan/tts"))
        // 明文 http → 忽略（应用禁用明文流量，收下只会"配了却下不动"）
        assertEquals(base, MirrorResolver.sources(spec, "http://mirror.lan/tts"))
        assertEquals(base, MirrorResolver.sources(spec, "https://"))
    }

    @Test
    fun customMirror_appendedAndTrimmed() {
        val base = spec.sources
        val s = MirrorResolver.sources(spec, "https://mirror.lan/tts/")
        assertEquals(base.size + 1, s.size)
        assertEquals("https://mirror.lan/tts/${spec.archiveName}", s.last())
        // 重复配置去重
        val custom = "https://mirror.lan/tts/${spec.archiveName}"
        val dup = MirrorResolver.sources(
            spec.copy(extraMirrors = spec.extraMirrors + custom),
            "https://mirror.lan/tts",
        )
        assertEquals(base.size + 1, dup.size)
    }

    @Test
    fun customMirror_keepsOfficialFirst() {
        val modified = MirrorResolver.withCustomMirror(spec, "https://mirror.lan/tts")
        assertEquals(spec.primaryUrl, modified.sources.first())
        assertEquals(spec.mirrorUrl, modified.sources[1])
    }

    @Test
    fun customMirror_directLinkUsedAsIs() {
        // 用户决策 2026-09-14：允许直接填「加速直链」，此时原样使用、不再拼归档名
        val direct = "https://cdn.example.com/tts/${spec.archiveName}"
        assertEquals(direct, MirrorResolver.sources(spec, direct).last())
        // 末段带归档扩展名亦视为直链
        val other = "https://cdn.example.com/tts/whatever.tar.gz"
        assertEquals(other, MirrorResolver.sources(spec, other).last())
        // 末段无扩展名 → 视为基址，自动拼归档名
        assertEquals(
            "https://cdn.example.com/tts/${spec.archiveName}",
            MirrorResolver.sources(spec, "https://cdn.example.com/tts").last(),
        )
        // 尾斜杠归一化后行为一致
        assertEquals(direct, MirrorResolver.sources(spec, "$direct/").last())
    }
}

class EngineStateMachineTest {

    @Test
    fun ready_to_synthesizing() {
        val sm = EngineStateMachine()
        sm.onInitSuccess()
        assertEquals(EngineState.READY, sm.state)
        assertTrue(sm.onSynthesizeStart())
        assertEquals(EngineState.SYNTHESIZING, sm.state)
        sm.onSynthesizeEnd()
        assertEquals(EngineState.READY, sm.state)
    }

    @Test
    fun noModel_rejectsRequest() {
        val sm = EngineStateMachine()
        sm.onNoModel()
        assertFalse(sm.onSynthesizeStart())
        assertFalse(sm.canAcceptRequest())
    }

    @Test
    fun pendingReload_atSeam() {
        val sm = EngineStateMachine()
        sm.onInitSuccess()
        sm.requestBackendReload()
        assertEquals(EngineState.RELOADING, sm.state)
        sm.onReloadSuccess()
        assertEquals(EngineState.READY, sm.state)
    }

    @Test
    fun reloadDuringSynthesize_defers() {
        val sm = EngineStateMachine()
        sm.onInitSuccess()
        sm.onSynthesizeStart()
        sm.requestBackendReload()
        // still SYNTHESIZING, pending true
        assertEquals(EngineState.SYNTHESIZING, sm.state)
        assertTrue(sm.pendingBackendReload)
        sm.onSynthesizeEnd()
        assertEquals(EngineState.RELOADING, sm.state)
    }

    @Test
    fun reloading_acceptsRequests() {
        // RELOADING 必须与 onSynthesizeStart 口径一致：换装接缝不得吞掉阅读器的下一句
        val sm = EngineStateMachine()
        sm.onInitSuccess()
        sm.requestBackendReload()
        assertEquals(EngineState.RELOADING, sm.state)
        assertTrue(sm.canAcceptRequest())
        assertTrue(sm.onSynthesizeStart())
    }

}

class PathSafetyTest {

    @Test
    fun zipSlip_names() {
        assertTrue(PathSafety.hasUnsafeZipEntryName("../evil"))
        assertTrue(PathSafety.hasUnsafeZipEntryName("/abs/path"))
        assertTrue(PathSafety.hasUnsafeZipEntryName("foo/../../bar"))
        assertFalse(PathSafety.hasUnsafeZipEntryName("model.onnx"))
        assertFalse(PathSafety.hasUnsafeZipEntryName("espeak-ng-data/en_dict"))
    }

    @Test
    fun canonicalChild() {
        assertTrue(PathSafety.isSafeChild("/data/models/tmp", "/data/models/tmp/a.onnx"))
        assertFalse(PathSafety.isSafeChild("/data/models/tmp", "/data/models/evil"))
    }
}

class ProcessNamesTest {
    @Test
    fun engineProcess() {
        assertTrue(ProcessNames.isEngineProcess("com.kermond.ebook2tts:tts_service"))
        assertFalse(ProcessNames.isEngineProcess("com.kermond.ebook2tts"))
        assertFalse(ProcessNames.isEngineProcess(null))
    }
}

class SpeedMapperTest {
    @Test
    fun aospUnits() {
        assertEquals(1.0f, SpeedMapper.readerSpeed(100), 1e-4f)
        assertEquals(0.5f, SpeedMapper.actualSpeed(50), 1e-4f)
        assertEquals(2.0f, SpeedMapper.actualSpeed(300), 1e-4f)
        // emotion limited ±10%
        assertEquals(1.1f, SpeedMapper.actualSpeed(100, 0.5f), 1e-4f)
        assertEquals(0.9f, SpeedMapper.actualSpeed(100, -0.5f), 1e-4f)
    }
}

class PcmChunkerTest {
    @Test
    fun chunk_limits() {
        val pcm = ByteArray(20_000) { 1 }
        val chunks = PcmChunker.chunk(pcm)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.isNotEmpty() && it.size <= PcmChunker.MAX_CHUNK_BYTES })
    }

    @Test
    fun silence_positive() {
        val s = PcmChunker.silenceMs(120, 1.0f, 24000)
        assertTrue(s.size > 1000)
    }

    @Test
    fun fade_onlyAtRequestEdges() {
        // PCM16 LE 常量样本 100（帧 = 低字节 100、高字节 0）；3ms@24kHz = 144 字节淡化区（红线 6）
        val pcm = ByteArray(2_000) { if (it % 2 == 0) 100 else 0 }
        val head = PcmChunker.fadeHead(pcm, 24000, 3)
        assertEquals(pcm.size, head.size)
        assertEquals(0, head[0].toInt()) // 首帧归零
        assertEquals(100, head[200].toInt()) // 淡化区之外原样
        val tail = PcmChunker.fadeTail(pcm, 24000, 3)
        assertTrue((tail[tail.size - 2].toInt() and 0xff) < 100) // 末帧被衰减
        assertEquals(100, tail[200].toInt())
        val both = PcmChunker.fadeEdges(pcm, 24000, 3)
        assertEquals(0, both[0].toInt())
        assertEquals(100, both[1_000].toInt()) // 中间零处理
    }

    @Test
    fun fade_shortInput_untouched() {
        val tiny = ByteArray(4) { 7 }
        assertSame(tiny, PcmChunker.fadeHead(tiny, 24000, 3))
        assertSame(tiny, PcmChunker.fadeTail(tiny, 24000, 3))
    }
}

/** 归档顶层目录兼容（IM-202）：官方 sherpa 归档顶层带目录名，落盘必须扁平。 */
class ModelLayoutTest {

    private fun tmp(): File = Files.createTempDirectory("layout").toFile()

    @Test
    fun flatLayout_rootIsDirItself() {
        val d = tmp()
        File(d, "model.onnx").writeText("x")
        assertEquals(d, ModelLayout.resolveRoot(d, "model.onnx"))
    }

    @Test
    fun nestedSingleDir_returnsInnerDir() {
        // 实测形态：kokoro-int8-multi-lang-v1_1/model.int8.onnx
        val d = tmp()
        val inner = File(d, "kokoro-int8-multi-lang-v1_1").apply { mkdirs() }
        File(inner, "model.int8.onnx").writeText("x")
        File(inner, "voices.bin").writeText("y")
        assertEquals(inner, ModelLayout.resolveRoot(d, "model.int8.onnx"))
    }

    @Test
    fun ambiguousOrMissing_null() {
        val d = tmp()
        File(d, "a").mkdirs()
        val b = File(d, "b").apply { mkdirs() }
        File(b, "model.onnx").writeText("x")
        assertNull("多个子目录不得猜测", ModelLayout.resolveRoot(d, "model.onnx"))
        assertNull(ModelLayout.resolveRoot(d, "nope.onnx"))
        assertNull(ModelLayout.resolveRoot(File(d, "ghost"), "model.onnx"))
    }

    @Test
    fun cleanupScaffold_removesTopDirShell() {
        val d = tmp()
        val inner = File(d, "top").apply { mkdirs() }
        File(inner, "model.onnx").writeText("x")
        ModelLayout.cleanupScaffold(d, inner)
        assertFalse("顶层空壳应被清理", d.exists())
        // 扁平布局下不得误删
        val flat = tmp()
        File(flat, "model.onnx").writeText("x")
        ModelLayout.cleanupScaffold(flat, flat)
        assertTrue(flat.exists())
    }
}
