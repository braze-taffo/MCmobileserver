package com.mcmobile.server.core.ddns

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DuckDnsProviderTest {
    private val token = "duck-token-secret"
    private val config = DdnsConfig(
        enabled = true,
        provider = DdnsProviderId.DUCKDNS,
        duckSubdomain = "mcbox",
        hostname = "mcbox.duckdns.org",
        token = token,
        intervalSec = 60,
    )

    @Test fun okUpdatesAndOmitsEmptyFamilies() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("OK\n2001:db8::5\n"))
            val provider = DuckDnsProvider(OkHttpClient(), server.url("/update").toString())
            val result = provider.apply(null, "2001:db8::5", config) as DdnsResult.Updated
            assertEquals("2001:db8::5", result.ipv6)
            assertNull(result.ipv4)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            val url = request.requestUrl!!
            assertEquals("mcbox", url.queryParameter("domains"))
            assertEquals(token, url.queryParameter("token"))
            assertEquals("true", url.queryParameter("verbose"))
            assertEquals("2001:db8::5", url.queryParameter("ipv6"))
            assertNull(url.queryParameter("ip"))
        }
    }

    @Test fun koIncludesBodyButNotToken() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("KO\ndenied\n"))
            val provider = DuckDnsProvider(OkHttpClient(), server.url("/update").toString())
            val failed = provider.apply("203.0.113.5", "2001:db8::5", config) as DdnsResult.Failed
            assertFalse(failed.retryable)
            assertTrue(failed.message.contains("KO"))
            assertTrue(failed.message.contains("denied"))
            assertFalse(failed.message.contains(token))
            assertFalse(failed.message.contains("duckdns.org"))
            assertFalse("模拟日志不应含 token", "ddns ${failed.message}".contains(token))
            val url = server.takeRequest().requestUrl!!
            assertEquals("203.0.113.5", url.queryParameter("ip"))
            assertEquals("2001:db8::5", url.queryParameter("ipv6"))
        }
    }

    @Test fun doesNotFollowRedirects() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(302).addHeader("Location", server.url("/stolen").toString()),
            )
            server.enqueue(MockResponse().setBody("OK"))
            val failed = DuckDnsProvider(OkHttpClient(), server.url("/update").toString())
                .apply("203.0.113.5", null, config) as DdnsResult.Failed
            assertTrue(failed.message.contains("302"))
            assertFalse(failed.retryable)
            assertEquals(1, server.requestCount)
        }
    }
}
