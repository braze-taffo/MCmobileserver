package com.mcmobile.server.core.manage

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Decode fabric/quilt/toml/yml metadata bytes that may be UTF-8, BOM-prefixed, or GBK/GB18030. */
object ModMetadataText {
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val bom = bomCharset(bytes)
        val payload = dropBom(bytes, bom)
        if (payload.isEmpty()) return ""
        if (bom == StandardCharsets.UTF_16BE || bom == StandardCharsets.UTF_16LE) {
            return decodeStrict(payload, bom) ?: decodeReplacing(payload, bom)
        }
        return decodeStrict(payload, StandardCharsets.UTF_8)
            ?: charsetOrNull("GB18030")?.let { decodeStrict(payload, it) }
            ?: charsetOrNull("GBK")?.let { decodeStrict(payload, it) }
            ?: decodeReplacing(payload, StandardCharsets.UTF_8)
    }

    private fun bomCharset(bytes: ByteArray): Charset? = when {
        startsWith(bytes, 0xEF, 0xBB, 0xBF) -> StandardCharsets.UTF_8
        startsWith(bytes, 0xFE, 0xFF) -> StandardCharsets.UTF_16BE
        startsWith(bytes, 0xFF, 0xFE) -> StandardCharsets.UTF_16LE
        else -> null
    }

    private fun dropBom(bytes: ByteArray, bom: Charset?): ByteArray = when (bom) {
        StandardCharsets.UTF_8 -> bytes.copyOfRange(3, bytes.size)
        StandardCharsets.UTF_16BE, StandardCharsets.UTF_16LE -> bytes.copyOfRange(2, bytes.size)
        else -> bytes
    }

    private fun startsWith(bytes: ByteArray, vararg prefix: Int): Boolean {
        if (bytes.size < prefix.size) return false
        return prefix.indices.all { bytes[it] == prefix[it].toByte() }
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? = runCatching {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private fun decodeReplacing(bytes: ByteArray, charset: Charset): String =
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    private fun charsetOrNull(name: String): Charset? = runCatching { Charset.forName(name) }.getOrNull()
}
