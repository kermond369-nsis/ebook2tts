package com.kermond.ebook2tts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TtsProviderSpecTest {

    @Test
    fun providerKind_parsesAndExposesCapabilities() {
        assertEquals(TtsProviderKind.MIMO, TtsProviderKind.parse(null))
        assertEquals(TtsProviderKind.MIMO, TtsProviderKind.parse("unknown"))
        assertEquals(TtsProviderKind.OPENAI_COMPAT, TtsProviderKind.parse("openai"))
        assertEquals(TtsProviderKind.CUSTOM_HTTP, TtsProviderKind.parse("custom_http"))

        assertTrue(TtsProviderKind.MIMO.supportsRoleVoiceDesign)
        assertFalse(TtsProviderKind.OPENAI_COMPAT.supportsRoleVoiceDesign)
        assertTrue(TtsProviderKind.OPENAI_COMPAT.supportsNativeSpeed)
        assertFalse(TtsProviderKind.MIMO.supportsNativeSpeed)
    }

    @Test
    fun customHttpTemplate_escapesJsonAndRendersPlaceholders() {
        val body = CustomHttpTemplate.renderBody(
            template = """{"input":"{{text}}","voice":"{{voice}}","model":"{{model}}","speed":{{speed}},"key":"{{apiKey}}"}""",
            text = "他说：\"你好\n世界\"\\再见",
            voice = "zh-female",
            model = "fish-1.5",
            speed = 1.25f,
            apiKey = "sk-secret",
        )
        assertTrue(body.contains("\\\"你好\\n世界\\\"\\\\再见"))
        assertTrue(body.contains("\"voice\":\"zh-female\""))
        assertTrue(body.contains("\"model\":\"fish-1.5\""))
        assertTrue(body.contains("\"speed\":1.25"))
        assertTrue(body.contains("\"key\":\"sk-secret\""))
        assertTrue(CustomHttpTemplate.hasSpeedPlaceholder("""{"speed":{{speed}}}"""))
        assertFalse(CustomHttpTemplate.hasSpeedPlaceholder("""{"text":"{{text}}"}"""))
    }

    @Test
    fun customHttpTemplate_parsesHeadersAndSubstitutesKey() {
        val headers = CustomHttpTemplate.parseHeaders(
            "# comment\nX-Api-Key: {{apiKey}}\nX-Custom: foo:bar\nbadline\n",
            apiKey = "secret-123",
        )
        assertEquals(2, headers.size)
        assertEquals("X-Api-Key" to "secret-123", headers[0])
        assertEquals("X-Custom" to "foo:bar", headers[1])
    }

    @Test
    fun wavHeaderParser_parsesStandardAndExtraChunkHeaders() {
        val baos = ByteArrayOutputStream()
        // RIFF + size placeholder + WAVE
        baos.write("RIFF".toByteArray(Charsets.US_ASCII))
        baos.write(u32le(0))
        baos.write("WAVE".toByteArray(Charsets.US_ASCII))
        // LIST subchunk (odd size 5 → padded to 6 bytes)
        baos.write("LIST".toByteArray(Charsets.US_ASCII))
        baos.write(u32le(5))
        baos.write(byteArrayOf(1, 2, 3, 4, 5, 0))
        // fmt subchunk (PCM=1, ch=2, sr=16000, bps=16)
        baos.write("fmt ".toByteArray(Charsets.US_ASCII))
        baos.write(u32le(16))
        baos.write(u16le(1))
        baos.write(u16le(2))
        baos.write(u32le(16000))
        baos.write(u32le(64000))
        baos.write(u16le(4))
        baos.write(u16le(16))
        // data subchunk
        baos.write("data".toByteArray(Charsets.US_ASCII))
        baos.write(u32le(8))
        baos.write(ByteArray(8) { 42 })

        val info = WavHeaderParser.parse(baos.toByteArray())
        assertNotNull(info)
        assertEquals(16000, info!!.sampleRate)
        assertEquals(2, info.channels)
        assertEquals(16, info.bitsPerSample)
        assertEquals(baos.size() - 8, info.dataOffset)

        assertNull(WavHeaderParser.parse(ByteArray(10)))
    }

    @Test
    fun pcmResampler_downmixAndStreamingResampleWorkAcrossChunks() {
        // Stereo [100, 300] → mono 200
        val stereo = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(100).putShort(300).array()
        val mono = PcmResampler.downmixToMonoPcm16(stereo, 2)
        assertEquals(2, mono.size)
        val monoSample = ByteBuffer.wrap(mono).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        assertEquals(200, monoSample)

        // 16kHz -> 24kHz (~1.5x samples) across two chunks
        val pcm16k = ByteArray(1600 * 2) { idx -> if (idx % 2 == 0) 0 else 10 }
        val r = StreamingPcmResampler(16000, 24000)
        val out1 = r.process(pcm16k.copyOfRange(0, 1600))
        val out2 = r.process(pcm16k.copyOfRange(1600, 3200))
        val tail = r.flush()
        val totalBytes = out1.size + out2.size + tail.size
        assertTrue("16k->24k 字节数应接近 1.5 倍（实测 $totalBytes）", totalBytes in 4600..4900)
    }

    private fun u16le(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun u32le(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )
}
