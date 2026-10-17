package com.mcmobile.server.core.manage

/** Reads a quoted TOML metadata value, including multiline literal and basic strings. */
internal object AddonMetadataFields {
    fun tomlString(source: String?, key: String): String? {
        val text = source?.replace("\r\n", "\n") ?: return null
        val field = Regex("(?m)^[ \\t]*${Regex.escape(key)}[ \\t]*=[ \\t]*").find(text) ?: return null
        var pos = field.range.last + 1
        val quote = text.getOrNull(pos)?.takeIf { it == '\'' || it == '"' } ?: return null
        val multiline = text.startsWith(quote.toString().repeat(3), pos)
        val delimiter = quote.toString().repeat(if (multiline) 3 else 1)
        pos += delimiter.length
        // TOML discards only the newline immediately after a multiline opening delimiter.
        if (multiline && text.getOrNull(pos) == '\n') pos++
        val result = StringBuilder()
        while (pos < text.length) {
            if (text.startsWith(delimiter, pos)) {
                pos += delimiter.length
                if (multiline) {
                    // Four/five quotes include one/two literal quotes before closing.
                    var extra = 0
                    while (text.getOrNull(pos) == quote && extra < 2) {
                        result.append(quote)
                        pos++
                        extra++
                    }
                }
                val tail = text.substring(pos).substringBefore('\n').trimStart(' ', '\t')
                if (tail.isNotEmpty() && !tail.startsWith('#')) return null
                return result.toString().takeIf { it.isNotBlank() }
            }
            val char = text[pos++]
            if (char == '\n' && !multiline) return null
            if (char != '\\' || quote == '\'') {
                result.append(char)
                continue
            }
            if (multiline) {
                var end = pos
                while (text.getOrNull(end) in listOf(' ', '\t', '\n')) end++
                if (text.substring(pos, end).contains('\n')) {
                    pos = end
                    continue
                }
            }
            when (text.getOrNull(pos++)) {
                'b' -> result.append('\b')
                't' -> result.append('\t')
                'n' -> result.append('\n')
                'f' -> result.append('\u000C')
                'r' -> result.append('\r')
                '"' -> result.append('"')
                '\\' -> result.append('\\')
                'u', 'U' -> {
                    val length = if (text[pos - 1] == 'u') 4 else 8
                    if (pos + length > text.length) return null
                    val hex = text.substring(pos, pos + length)
                    if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
                    val codePoint = hex.toLong(16)
                    if (codePoint > 0x10FFFF || codePoint in 0xD800..0xDFFF) return null
                    result.append(String(Character.toChars(codePoint.toInt())))
                    pos += length
                }
                else -> return null
            }
        }
        return null
    }
}
