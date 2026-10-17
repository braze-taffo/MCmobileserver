package com.mcmobile.server.core.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class ModMetadataTextTest {
    private val chinese = """{"id":"zh-test-mod","name":"中文测试模组","description":"这是一段中文描述"}"""

    @Test fun utf8RoundTrips() {
        assertEquals(chinese, ModMetadataText.decode(chinese.toByteArray(StandardCharsets.UTF_8)))
    }

    @Test fun utf8TwoByteLatinRoundTripsAheadOfGbk() {
        // é is C3 A9: legal UTF-8 and legal GBK. GBK would yield U+8305. Strict UTF-8 must win.
        listOf("café", "Résumé").forEach { text ->
            val utf8 = text.toByteArray(StandardCharsets.UTF_8)
            val decoded = ModMetadataText.decode(utf8)
            assertEquals(text, decoded)
            assertFalse(decoded.any { it in '\u4E00'..'\u9FFF' })
            val asGbk = String(utf8, Charset.forName("GBK"))
            assertTrue(asGbk != text)
            assertTrue(asGbk.any { it in '\u4E00'..'\u9FFF' })
        }
    }

    @Test fun latin1HighByteDoesNotDecodeAsGbk() {
        val latin1 = Charset.forName("ISO-8859-1")
        val bare = ModMetadataText.decode("Übermod".toByteArray(latin1))
        assertEquals("\uFFFDbermod", bare)
        assertFalse(bare.contains('\u8EA1'))
        assertFalse(bare.any { it in '\u4E00'..'\u9FFF' })

        val json = """{"name":"Übermod","description":"Ein Mod für Freunde"}"""
        val decoded = ModMetadataText.decode(json.toByteArray(latin1))
        assertEquals(
            "{\"name\":\"\uFFFDbermod\",\"description\":\"Ein Mod f\uFFFDr Freunde\"}",
            decoded,
        )
        assertFalse(decoded.contains('\u8EA1'))
        assertFalse(decoded.any { it in '\u4E00'..'\u9FFF' })
    }

    @Test fun utf8BomIsStripped() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals(chinese, ModMetadataText.decode(bom + chinese.toByteArray(StandardCharsets.UTF_8)))
    }

    @Test fun multipleWesternAccentsDoNotPassUsingFileWideCounts() {
        listOf("ISO-8859-1", "windows-1252").forEach { encoding ->
            listOf("""{"name":"für schöne Größe"}""", """{"name":"Übermod","description":"für schöne Größe, très élégant"}""").forEach { text ->
                val bytes = text.toByteArray(Charset.forName(encoding))
                val decoded = ModMetadataText.decode(bytes)
                assertEquals(String(bytes, StandardCharsets.UTF_8), decoded)
                assertFalse(decoded.any { it in '\u4E00'..'\u9FFF' })
            }
        }
    }

    @Test fun gb18030FourByteChineseRoundTrips() {
        val text = """{"name":"中文𠀀模组","description":"扩展汉字𠮷"}"""
        assertEquals(text, ModMetadataText.decode(text.toByteArray(Charset.forName("GB18030"))))
    }

    @Test fun malformedLegacySequencesUseUtf8Replacement() {
        listOf(byteArrayOf(0x81.toByte()), byteArrayOf(0x81.toByte(), 0x30),
            byteArrayOf(0x81.toByte(), 0x30, 0x81.toByte()), byteArrayOf(0xFF.toByte(), 0xFF.toByte())).forEach { bytes ->
            assertEquals(String(bytes, StandardCharsets.UTF_8), ModMetadataText.decode(bytes))
        }
    }

    @Test fun ambiguousGbkAsciiTrailUsesConservativeFallbackEvenBesideChinese() {
        // 丂 is GBK 81 40. Like a Western accent followed by ASCII, it is ambiguous.
        val bytes = "中文丂".toByteArray(Charset.forName("GBK"))
        assertEquals(String(bytes, StandardCharsets.UTF_8), ModMetadataText.decode(bytes))
    }

    @Test fun utf16BeBomDecodes() {
        val bom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        assertEquals(chinese, ModMetadataText.decode(bom + chinese.toByteArray(StandardCharsets.UTF_16BE)))
    }

    @Test fun utf16LeBomDecodes() {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        assertEquals(chinese, ModMetadataText.decode(bom + chinese.toByteArray(StandardCharsets.UTF_16LE)))
    }

    @Test fun gbkFallsBackAfterUtf8Rejects() {
        val bytes = chinese.toByteArray(Charset.forName("GBK"))
        assertEquals(chinese, ModMetadataText.decode(bytes))
        assertFalse(bytes.contentEquals(chinese.toByteArray(StandardCharsets.UTF_8)))
    }

    @Test fun asciiRoundTrips() {
        val ascii = """{"id":"ascii-mod","name":"ASCII Mod","description":"An ASCII description"}"""
        assertEquals(ascii, ModMetadataText.decode(ascii.toByteArray(StandardCharsets.US_ASCII)))
    }

    @Test fun emptyBytes() {
        assertEquals("", ModMetadataText.decode(ByteArray(0)))
    }
}
