package com.mcmobile.server.core.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class ModMetadataTextTest {
    private val chinese = """{"id":"zh-test-mod","name":"中文测试模组","description":"这是一段中文描述"}"""

    @Test fun utf8RoundTrips() {
        assertEquals(chinese, ModMetadataText.decode(chinese.toByteArray(StandardCharsets.UTF_8)))
    }

    @Test fun utf8BomIsStripped() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals(chinese, ModMetadataText.decode(bom + chinese.toByteArray(StandardCharsets.UTF_8)))
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
