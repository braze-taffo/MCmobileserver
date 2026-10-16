package com.mcmobile.server.core.net

import java.net.Inet6Address
import java.net.InetAddress
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PublicAddressesTest {
    private fun v6(host: String, iface: String? = null): Inet6Address {
        val raw = InetAddress.getByName(host) as Inet6Address
        return if (iface == null) raw else Inet6Address.getByAddress(iface, raw.address, 2)
    }

    @Test fun pickIpv6PrefersStableGlobalThenAnyGlobalThenUla() {
        val link = v6("fe80::1")
        val ula = v6("fd12:3456::1")
        val temp = v6("2001:db8::aa")
        val stable = v6("2001:db8::1")
        val deprecated = v6("2001:db8::dead")
        assertEquals(PublicAddresses.hostOf(stable), PublicAddresses.pickIpv6(listOf(link, ula, temp, stable), isTemporary = { it == temp }))
        assertEquals(
            PublicAddresses.hostOf(temp),
            PublicAddresses.pickIpv6(listOf(temp, deprecated), isTemporary = { it == temp }, isDeprecated = { it == deprecated }),
        )
        assertEquals(PublicAddresses.hostOf(ula), PublicAddresses.pickIpv6(listOf(link, ula)))
        assertNull(PublicAddresses.pickIpv6(listOf(link)))
    }

    @Test fun pickIpv6StripsZoneSuffix() {
        val scoped = v6("2001:db8::1", iface = "wlan0")
        assertTrue(scoped.hostAddress!!.contains("%"))
        val picked = PublicAddresses.pickIpv6(listOf(scoped))
        assertEquals(PublicAddresses.hostOf(scoped), picked)
        assertFalse(picked!!.contains("%"))
    }

    @Test fun parsePublicIpv4RejectsPrivateLoopbackAndIpv6Text() {
        assertEquals("203.0.113.10", PublicAddresses.parsePublicIpv4("  203.0.113.10\n"))
        assertNull(PublicAddresses.parsePublicIpv4("192.168.1.1"))
        assertNull(PublicAddresses.parsePublicIpv4("10.0.0.1"))
        assertNull(PublicAddresses.parsePublicIpv4("127.0.0.1"))
        assertNull(PublicAddresses.parsePublicIpv4("2001:db8::1"))
        assertNull(PublicAddresses.parsePublicIpv4("<html>1.1.1.1</html>"))
    }

    @Test fun detectUsesSecondEchoAfterFirstFailsAndSetsUa() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setBody("203.0.113.8\n"))
            val nics = listOf(v6("2001:db8::5"))
            val urls = listOf(server.url("/one").toString(), server.url("/two").toString())
            val found = PublicAddresses.detect(
                OkHttpClient(), nics, urls, userAgent = PublicAddresses.userAgent("1.0.6"),
            )
            assertEquals("203.0.113.8", found.ipv4)
            assertEquals(PublicAddresses.hostOf(nics.single()), found.ipv6)
            assertEquals("GET", server.takeRequest().method)
            val second = server.takeRequest()
            assertEquals("MC-Mobile-Server/1.0.6 (ddns)", second.getHeader("User-Agent"))
        }
    }

    @Test fun detectRejectsIpv6EchoBodyAndSiteLocal() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("2001:db8::1"))
            server.enqueue(MockResponse().setBody("192.168.0.10"))
            val found = PublicAddresses.detect(
                OkHttpClient(), emptyList(),
                listOf(server.url("/v6").toString(), server.url("/lan").toString()),
            )
            assertNull(found.ipv4)
            assertNull(found.ipv6)
        }
    }
}
