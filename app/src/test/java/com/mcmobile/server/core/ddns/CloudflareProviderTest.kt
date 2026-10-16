package com.mcmobile.server.core.ddns

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class CloudflareProviderTest {
    private val token = "cf-token-1"
    private val config = DdnsConfig(
        enabled = true,
        provider = DdnsProviderId.CLOUDFLARE,
        hostname = "mc.example.com",
        cloudflareZoneId = "zone123",
        cloudflareRecord = "mc.example.com",
        token = token,
        intervalSec = 60,
    )

    private fun provider(server: MockWebServer) =
        CloudflareProvider(OkHttpClient(), server.url("/client/v4").toString())

    private fun records(vararg items: String) =
        """{"success":true,"result":[${items.joinToString(",")}]}"""

    private fun record(id: String, name: String = "mc.example.com") =
        """{"id":"$id","type":"A","name":"$name","content":"0.0.0.0"}"""

    private fun assertWrite(request: RecordedRequest, type: String, ip: String) {
        val body = request.body.readUtf8()
        val obj = Json.parseToJsonElement(body).jsonObject
        assertEquals(false, obj["proxied"]?.jsonPrimitive?.boolean)
        assertEquals(120, obj["ttl"]?.jsonPrimitive?.int)
        assertEquals(type, obj["type"]?.jsonPrimitive?.content)
        assertEquals(ip, obj["content"]?.jsonPrimitive?.content)
        assertEquals("Bearer $token", request.getHeader("Authorization"))
        assertFalse(body.contains(token))
    }

    @Test fun emptyListPostsAndExistingRecordPuts() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(records()))
            server.enqueue(MockResponse().setBody("""{"success":true,"result":{"id":"new"}}"""))
            server.enqueue(MockResponse().setBody(records(record("rec-9"))))
            server.enqueue(MockResponse().setBody("""{"success":true}"""))
            val result = provider(server).apply("203.0.113.10", "2001:db8::10", config) as DdnsResult.Updated
            assertEquals("203.0.113.10", result.ipv4)
            assertEquals("2001:db8::10", result.ipv6)
            val postGet = server.takeRequest()
            assertEquals("GET", postGet.method)
            assertEquals("A", postGet.requestUrl?.queryParameter("type"))
            assertEquals("mc.example.com", postGet.requestUrl?.queryParameter("name"))
            val post = server.takeRequest()
            assertEquals("POST", post.method)
            assertTrue(post.path!!.endsWith("/dns_records"))
            assertWrite(post, "A", "203.0.113.10")
            val putGet = server.takeRequest()
            assertEquals("AAAA", putGet.requestUrl?.queryParameter("type"))
            val put = server.takeRequest()
            assertEquals("PUT", put.method)
            assertTrue(put.path!!.endsWith("/dns_records/rec-9"))
            assertWrite(put, "AAAA", "2001:db8::10")
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun duplicateRecordsPutOnlyTheFirst() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(records(record("rec-1"), record("rec-2"))))
            server.enqueue(MockResponse().setBody("""{"success":true}"""))
            val result = provider(server).apply("203.0.113.11", null, config) as DdnsResult.Updated
            assertTrue(result.detail.contains("多余"))
            assertEquals("GET", server.takeRequest().method)
            val put = server.takeRequest()
            assertEquals("PUT", put.method)
            assertTrue(put.path!!.contains("rec-1"))
            assertFalse(put.path!!.contains("rec-2"))
            assertWrite(put, "A", "203.0.113.11")
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun authFailuresAreNotRetryableAndOmitToken() = runBlocking {
        for (code in listOf(401, 403)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(code).setBody("""{"success":false,"errors":[{"message":"no"}]}"""))
                val failed = provider(server).apply("203.0.113.1", null, config) as DdnsResult.Failed
                assertFalse(failed.retryable)
                assertTrue(failed.message.contains(code.toString()))
                assertFalse(failed.message.contains(token))
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun tooManyRequestsAndServerErrorsRetry() = runBlocking {
        for (code in listOf(429, 500)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(code))
                val failed = provider(server).apply("203.0.113.1", null, config) as DdnsResult.Failed
                assertTrue(failed.retryable)
                assertFalse(failed.message.contains(token))
            }
        }
    }

    @Test fun emptyOrNonJsonWriteIsRetryableAndSuccessFalseIsNot() = runBlocking {
        for (body in listOf("", "OK", "null", "[]", """{"result":{"id":"x"}}""")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(records()))
                server.enqueue(MockResponse().setResponseCode(200).setBody(body))
                val failed = provider(server).apply("203.0.113.1", null, config) as DdnsResult.Failed
                assertTrue(body, failed.retryable)
                assertFalse(failed.message.contains(token))
            }
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(records()))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":false}"""))
            val failed = provider(server).apply("203.0.113.1", null, config) as DdnsResult.Failed
            assertFalse(failed.retryable)
        }
    }

    @Test fun doesNotFollowRedirects() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(302).addHeader("Location", server.url("/stolen").toString()),
            )
            server.enqueue(MockResponse().setBody("""{"success":true,"result":[]}"""))
            val failed = provider(server).apply("203.0.113.1", null, config) as DdnsResult.Failed
            assertFalse(failed.retryable)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun factoryLeavesUnimplementedProvidersUnsupported() = runBlocking {
        val http = OkHttpClient()
        assertTrue(DdnsProviders.of(DdnsProviderId.CLOUDFLARE, http) is CloudflareProvider)
        assertTrue(DdnsProviders.of(DdnsProviderId.DUCKDNS, http) is DuckDnsProvider)
        assertTrue(DdnsProviders.of(DdnsProviderId.WEBHOOK, http) is WebhookProvider)
        assertTrue(DdnsProviders.of(DdnsProviderId.ALIYUN, http).apply(null, null, DdnsConfig()) is DdnsResult.Unsupported)
        assertTrue(DdnsProviders.of(DdnsProviderId.DNSPOD, http).apply(null, null, DdnsConfig()) is DdnsResult.Unsupported)
    }
}
