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

    private fun cand(host: String, flags: Int = 0) = Ipv6Candidate(v6(host), flags)

    @Test fun pickIpv6KeepsStableGlobalAndDropsFlagsAndUla() {
        val stable = cand("2001:db8::1")
        val picked = PublicAddresses.pickIpv6(
            listOf(
                cand("fe80::1"),
                cand("fd12:3456::1"),
                cand("fc00::1"),
                cand("2001:db8::aa", Ipv6AddressFlags.TEMPORARY),
                cand("2001:db8::dead", Ipv6AddressFlags.DEPRECATED),
                cand("2001:db8::777", Ipv6AddressFlags.TENTATIVE),
                cand("2001:db8::dad", Ipv6AddressFlags.DADFAILED),
                cand("2001:db8::bad", Ipv6AddressFlags.TEMPORARY or Ipv6AddressFlags.DEPRECATED),
                stable,
            ),
        )
        assertEquals(PublicAddresses.hostOf(stable.address), picked)
        assertNull(
            PublicAddresses.pickIpv6(
                listOf(
                    cand("2001:db8::aa", Ipv6AddressFlags.TEMPORARY),
                    cand("2001:db8::dead", Ipv6AddressFlags.DEPRECATED),
                    cand("2001:db8::777", Ipv6AddressFlags.TENTATIVE),
                    cand("2001:db8::dad", Ipv6AddressFlags.DADFAILED),
                ),
            ),
        )
        assertNull(PublicAddresses.pickIpv6(listOf(cand("fe80::1"), cand("fd12:3456::1"))))
        assertNull(PublicAddresses.pickIpv6(listOf(cand("fe80::1"))))
        assertNotEquals(0, Ipv6AddressFlags.TEMPORARY)
        assertNotEquals(0, Ipv6AddressFlags.DADFAILED)
        assertNotEquals(0, Ipv6AddressFlags.DEPRECATED)
        assertNotEquals(0, Ipv6AddressFlags.TENTATIVE)
        assertEquals(0x08, Ipv6AddressFlags.DADFAILED)
        // android.jar leaves these at 0. A non-zero stub value must still be the kernel bit we filter on.
        val os = listOf(
            Ipv6AddressFlags.TEMPORARY to android.system.OsConstants.IFA_F_TEMPORARY,
            Ipv6AddressFlags.DADFAILED to android.system.OsConstants.IFA_F_DADFAILED,
            Ipv6AddressFlags.DEPRECATED to android.system.OsConstants.IFA_F_DEPRECATED,
            Ipv6AddressFlags.TENTATIVE to android.system.OsConstants.IFA_F_TENTATIVE,
        )
        for ((ours, platform) in os) {
            if (platform != 0) assertEquals(ours, platform)
        }
    }

    @Test fun pickIpv6StripsZoneSuffix() {
        val scoped = v6("2001:db8::1", iface = "wlan0")
        assertTrue(scoped.hostAddress!!.contains("%"))
        val picked = PublicAddresses.pickIpv6(listOf(Ipv6Candidate(scoped)))
        assertEquals(PublicAddresses.hostOf(scoped), picked)
        assertFalse(picked!!.contains("%"))
    }

    @Test fun sanitizeDropsUlaAndKeepsGlobalText() {
        val kept = PublicAddresses.sanitize(PublicEndpoints("203.0.113.10", "2001:db8::9"))
        assertEquals("203.0.113.10", kept.ipv4)
        assertEquals("2001:db8::9", kept.ipv6)
        val ula = PublicAddresses.sanitize(PublicEndpoints(null, "fd12:3456::1"))
        assertNull(ula.ipv4)
        assertNull(ula.ipv6)
        val local = PublicAddresses.sanitize(PublicEndpoints("10.1.1.1", "fe80::1"))
        assertNull(local.ipv4)
        assertNull(local.ipv6)
        assertNull(PublicAddresses.publishableIpv6("fc00::5"))
        assertEquals("2001:db8::9", PublicAddresses.publishableIpv6("2001:db8::9%2"))
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
