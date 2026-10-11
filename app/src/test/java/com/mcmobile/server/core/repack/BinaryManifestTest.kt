package com.mcmobile.server.core.repack

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BinaryManifestTest {
    private val old = "com.mcmobile.server"
    private val new = "com.example." + "a".repeat(140)
    private fun fixture(utf8: Boolean): ByteArray {
        val strings = listOf(old, "$old.apkfiles", "$old.androidx-startup", "$old.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", "$old.MainActivity", "中文名称", "manifest")
        val data = ByteArrayOutputStream(); val offsets = mutableListOf<Int>()
        strings.forEach { text ->
            offsets += data.size()
            val bytes = text.toByteArray(if (utf8) Charsets.UTF_8 else Charsets.UTF_16LE)
            data.write(text.length); if (utf8) data.write(bytes.size) else data.write(0)
            data.write(bytes); data.write(0); if (!utf8) data.write(0)
        }
        while (data.size() % 4 != 0) data.write(0)
        val start = 28 + strings.size * 4
        val pool = ByteBuffer.allocate(start + data.size()).order(ByteOrder.LITTLE_ENDIAN)
        pool.putShort(1).putShort(28).putInt(pool.capacity()).putInt(strings.size).putInt(0)
            .putInt(if (utf8) 256 else 0).putInt(start).putInt(0)
        offsets.forEach { pool.putInt(it) }; pool.put(data.toByteArray())
        return ByteBuffer.allocate(8 + pool.capacity() + 8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(3).putShort(8).putInt(capacity()).put(pool.array())
            putShort(0x102).putShort(8).putInt(8)
        }.array()
    }
    private fun checkPool(utf8: Boolean) {
        val source = fixture(utf8)
        val changed = BinaryManifest.rename(source, old, new)
        val b = ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(changed.size, b.getInt(4))
        assertArrayEquals(source.takeLast(8).toByteArray(), changed.takeLast(8).toByteArray())
        // Repacking a clone back to its original identity restores the same pool bytes.
        assertArrayEquals(source, BinaryManifest.rename(changed, new, old))
        val text = String(changed, if (utf8) Charsets.UTF_8 else Charsets.UTF_16LE)
        assertTrue(text.contains(new)); assertTrue(text.contains("$old.MainActivity"))
        assertTrue(text.contains("中文名称"))
    }
    @Test fun utf8LongerPackagePreservesClassesAndChunkOffsets() { checkPool(true) }
    @Test fun utf16LongerPackagePreservesClassesAndChunkOffsets() { checkPool(false) }
    @Test fun rejectsInvalidIdentityAndSource() {
        listOf("one", "a..b", "1a.b", "a.b-c", "android.app", "a." + "b".repeat(200)).forEach { assertFalse(BinaryManifest.validPackage(it)) }
        assertTrue(BinaryManifest.validPackage("com.antutu.ABenchMark"))
        assertThrows(IllegalArgumentException::class.java) { BinaryManifest.rename(fixture(true), "wrong.package", new) }
        assertThrows(IllegalArgumentException::class.java) { BinaryManifest.rename(byteArrayOf(1, 2), old, new) }
    }
}
