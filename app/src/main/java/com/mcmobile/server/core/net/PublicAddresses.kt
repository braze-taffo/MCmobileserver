package com.mcmobile.server.core.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

data class PublicEndpoints(val ipv4: String?, val ipv6: String?)

object PublicAddresses {
    val DEFAULT_ECHO_URLS = listOf(
        "https://api4.ipify.org",
        "https://ipv4.icanhazip.com",
        "https://v4.ident.me",
        "https://4.ipw.cn",
    )

    fun userAgent(version: String = "dev") = "MC-Mobile-Server/$version (ddns)"

    fun hostOf(addr: Inet6Address): String = (addr.hostAddress ?: "").substringBefore('%')

    fun parsePublicIpv4(text: String): String? {
        val trimmed = text.trim()
        val match = V4.matchEntire(trimmed) ?: return null
        val parts = match.groupValues.drop(1).map { it.toInt() }
        if (parts.any { it > 255 }) return null
        val addr = InetAddress.getByAddress(parts.map { it.toByte() }.toByteArray()) as Inet4Address
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
            addr.isMulticastAddress || addr.isSiteLocalAddress
        ) return null
        return addr.hostAddress
    }

    fun pickIpv6(
        nics: List<Inet6Address>,
        isTemporary: (Inet6Address) -> Boolean = { temporaryOf(it) },
        isDeprecated: (Inet6Address) -> Boolean = { deprecatedOf(it) },
    ): String? {
        val usable = nics.filterNot(::excluded)
        val global = usable.filter(::globalUnicast)
        val pick = global.firstOrNull { !isTemporary(it) && !isDeprecated(it) }
            ?: global.firstOrNull { !isDeprecated(it) }
            ?: global.firstOrNull()
            ?: usable.firstOrNull(::ula)
        val host = pick?.let(::hostOf).orEmpty()
        return host.ifEmpty { null }
    }

    fun detect(
        http: OkHttpClient,
        nics: List<Inet6Address>,
        echoUrls: List<String> = DEFAULT_ECHO_URLS,
        userAgent: String = userAgent(),
        isTemporary: (Inet6Address) -> Boolean = { temporaryOf(it) },
        isDeprecated: (Inet6Address) -> Boolean = { deprecatedOf(it) },
    ): PublicEndpoints {
        val ipv6 = pickIpv6(nics, isTemporary, isDeprecated)
        val client = http.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
        var ipv4: String? = null
        for (url in echoUrls) {
            ipv4 = runCatching {
                client.newCall(
                    Request.Builder().url(url).header("User-Agent", userAgent).get().build(),
                ).execute().use { response ->
                    if (!response.isSuccessful) null
                    else parsePublicIpv4(response.body?.string().orEmpty())
                }
            }.getOrNull()
            if (ipv4 != null) break
        }
        return PublicEndpoints(ipv4, ipv6)
    }

    private val V4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    private fun excluded(addr: Inet6Address): Boolean {
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isMulticastAddress || addr.isLinkLocalAddress) {
            return true
        }
        val bytes = addr.address
        if (bytes.size == 16 && bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte() &&
            bytes.slice(0..9).all { it == 0.toByte() }
        ) return true
        return addr.isIPv4CompatibleAddress && bytes.slice(0..11).all { it == 0.toByte() }
    }

    private fun globalUnicast(addr: Inet6Address): Boolean {
        val top = addr.address[0].toInt() and 0xff
        return top in 0x20..0x3f
    }

    private fun ula(addr: Inet6Address): Boolean = (addr.address[0].toInt() and 0xfe) == 0xfc

    private fun temporaryOf(addr: Inet6Address): Boolean =
        runCatching { addr.javaClass.getMethod("isTemporary").invoke(addr) as Boolean }.getOrDefault(false)

    private fun deprecatedOf(addr: Inet6Address): Boolean =
        runCatching { addr.javaClass.getMethod("isDeprecated").invoke(addr) as Boolean }.getOrDefault(false)
}
