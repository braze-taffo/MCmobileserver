package com.mcmobile.server.core.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

data class PublicEndpoints(val ipv4: String?, val ipv6: String?)

/** One IPv6 address plus the kernel `ifa_flags` from `LinkAddress.getFlags()`. */
data class Ipv6Candidate(
    val address: Inet6Address,
    val flags: Int = 0,
)

/**
 * Kernel `if_addr.h` bits. These match `android.system.OsConstants.IFA_F_*` on a device.
 * The android.jar stubs initialize those fields to 0, so the filter must not read them.
 */
object Ipv6AddressFlags {
    const val TEMPORARY = 0x01
    const val DADFAILED = 0x08
    const val DEPRECATED = 0x20
    const val TENTATIVE = 0x40
    const val UNUSABLE = TEMPORARY or DADFAILED or DEPRECATED or TENTATIVE

    fun unusable(flags: Int): Boolean = flags and UNUSABLE != 0
}

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

    /**
     * Global unicast only. Drops temporary, DAD-failed, deprecated, and tentative flags, and never
     * returns ULA (`fc00::/7`). No usable global address yields null.
     */
    fun pickIpv6(candidates: List<Ipv6Candidate>): String? {
        val pick = candidates.firstOrNull { candidate ->
            !Ipv6AddressFlags.unusable(candidate.flags) &&
                !excluded(candidate.address) &&
                globalUnicast(candidate.address)
        } ?: return null
        return hostOf(pick.address).ifEmpty { null }
    }

    /** Keep a public IPv4 and a global unicast IPv6. ULA and other non-routable text become null. */
    fun sanitize(endpoints: PublicEndpoints): PublicEndpoints = PublicEndpoints(
        ipv4 = endpoints.ipv4?.let(::parsePublicIpv4),
        ipv6 = publishableIpv6(endpoints.ipv6),
    )

    fun publishableIpv6(host: String?): String? {
        if (host.isNullOrBlank()) return null
        val bare = host.substringBefore('%').trim()
        val addr = runCatching { InetAddress.getByName(bare) }.getOrNull() as? Inet6Address ?: return null
        if (excluded(addr) || !globalUnicast(addr)) return null
        return bare
    }

    fun detect(
        http: OkHttpClient,
        nics: List<Inet6Address>,
        echoUrls: List<String> = DEFAULT_ECHO_URLS,
        userAgent: String = userAgent(),
    ): PublicEndpoints {
        val ipv6 = pickIpv6(nics.map { Ipv6Candidate(it) })
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
}
