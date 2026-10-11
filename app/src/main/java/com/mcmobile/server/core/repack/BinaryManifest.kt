package com.mcmobile.server.core.repack

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Rebuild only the manifest string pool; DEX class names and resource IDs stay intact. */
object BinaryManifest {
    fun validPackage(value: String): Boolean = value.length <= 200 &&
        Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(value) &&
        !value.startsWith("android.") && value != "android"

    fun rename(input: ByteArray, old: String, new: String): ByteArray {
        require(validPackage(new)) { "包名须包含至少两段，每段以字母开头，仅使用字母、数字和下划线" }
        val b = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN)
        require(input.size >= 8 && b.getShort(0).toInt() == 3 && b.getInt(4) == input.size) { "无效二进制清单" }
        var pos = b.getShort(2).toInt() and 0xffff
        while (pos + 8 <= input.size) {
            val size = b.getInt(pos + 4)
            require(size >= 8 && size <= input.size - pos)
            if ((b.getShort(pos).toInt() and 0xffff) == 1) {
                val header = b.getShort(pos + 2).toInt() and 0xffff
                val count = b.getInt(pos + 8)
                val styles = b.getInt(pos + 12)
                val flags = b.getInt(pos + 16)
                val start = b.getInt(pos + 20)
                val styleStart = b.getInt(pos + 24)
                require(header >= 28 && count in 1..100000 && styles >= 0 && start >= header + (count + styles) * 4)
                val utf8 = flags and 0x100 != 0
                val data = ByteArrayOutputStream()
                val offsets = IntArray(count)
                var found = false
                for (i in 0 until count) {
                    var cursor = pos + start + b.getInt(pos + header + i * 4)
                    fun length(): Int {
                        if (utf8) {
                            val first = input[cursor++].toInt() and 255
                            return if (first and 128 == 0) first else ((first and 127) shl 8) or (input[cursor++].toInt() and 255)
                        }
                        val first = b.getShort(cursor).toInt() and 65535; cursor += 2
                        return if (first and 32768 == 0) first else {
                            val second = b.getShort(cursor).toInt() and 65535; cursor += 2
                            ((first and 32767) shl 16) or second
                        }
                    }
                    if (utf8) length()
                    val length = length()
                    val text = String(input, cursor, length * if (utf8) 1 else 2, if (utf8) Charsets.UTF_8 else Charsets.UTF_16LE)
                    val replacement = when {
                        text == old -> { found = true; new }
                        text == "$old.apkfiles" || text == "$old.androidx-startup" ||
                            text == "$old.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" -> new + text.removePrefix(old)
                        else -> text
                    }
                    offsets[i] = data.size()
                    fun writeLength(n: Int) {
                        if (utf8) {
                            require(n <= 32767)
                            if (n >= 128) data.write((n shr 8) or 128)
                            data.write(n and 255)
                        } else {
                            require(n <= 32767)
                            data.write(n and 255); data.write(n shr 8)
                        }
                    }
                    val bytes = replacement.toByteArray(if (utf8) Charsets.UTF_8 else Charsets.UTF_16LE)
                    writeLength(replacement.length)
                    if (utf8) writeLength(bytes.size)
                    data.write(bytes); data.write(0); if (!utf8) data.write(0)
                }
                require(found) { "清单包名与当前应用不匹配" }
                while (data.size() % 4 != 0) data.write(0)
                val styleBytes = if (styleStart > 0) input.copyOfRange(pos + styleStart, pos + size) else byteArrayOf()
                val pool = ByteBuffer.allocate(start + data.size() + styleBytes.size).order(ByteOrder.LITTLE_ENDIAN)
                pool.put(input, pos, start)
                pool.putInt(4, pool.capacity()); pool.putInt(16, flags and 1.inv())
                pool.putInt(24, if (styleBytes.isEmpty()) 0 else start + data.size())
                offsets.forEachIndexed { i, offset -> pool.putInt(header + i * 4, offset) }
                pool.position(start); pool.put(data.toByteArray()); pool.put(styleBytes)
                val output = input.copyOfRange(0, pos) + pool.array() + input.copyOfRange(pos + size, input.size)
                ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).putInt(4, output.size)
                return output
            }
            pos += size
        }
        error("清单缺少字符串池")
    }
}
