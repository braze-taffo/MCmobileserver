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

    @Test fun emptyListPostsAndExistingRecordPatches() = runBlocking {
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
            val patch = server.takeRequest()
            assertEquals("PATCH", patch.method)
            assertTrue(patch.path!!.endsWith("/dns_records/rec-9"))
            assertWrite(patch, "AAAA", "2001:db8::10")
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun duplicateRecordsPatchOnlyTheFirst() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(records(record("rec-1"), record("rec-2"))))
            server.enqueue(MockResponse().setBody("""{"success":true}"""))
            server.enqueue(MockResponse().setBody(records()))
            val result = provider(server).apply("203.0.113.11", null, config) as DdnsResult.Updated
            assertEquals("203.0.113.11", result.ipv4)
            assertNull(result.ipv6)
            assertTrue(result.detail.contains("多余"))
            assertFalse(result.detail.contains("已删除"))
            assertEquals("GET", server.takeRequest().method)
            val patch = server.takeRequest()
            assertEquals("PATCH", patch.method)
            assertTrue(patch.path!!.contains("rec-1"))
            assertFalse(patch.path!!.contains("rec-2"))
            assertWrite(patch, "A", "203.0.113.11")
            val aaaa = server.takeRequest()
            assertEquals("GET", aaaa.method)
            assertEquals("AAAA", aaaa.requestUrl?.queryParameter("type"))
            assertEquals("mc.example.com", aaaa.requestUrl?.queryParameter("name"))
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun ipv4OnlyDeletesStaleAaaa() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(records()))
            server.enqueue(MockResponse().setBody("""{"success":true,"result":{"id":"new"}}"""))
            server.enqueue(MockResponse().setBody(records(
                record("aaaa-1"),
                record("aaaa-skip", "other.example.com"),
                record("aaaa-2", "MC.example.com"),
            )))
            server.enqueue(MockResponse().setBody("""{"success":true}"""))
            server.enqueue(MockResponse().setBody("""{"success":true}"""))
            val result = provider(server).apply("203.0.113.20", "  ", config) as DdnsResult.Updated
            assertEquals("203.0.113.20", result.ipv4)
            assertNull(result.ipv6)
            assertTrue(result.detail.contains("已删除过期 AAAA"))
            val getA = server.takeRequest()
            assertEquals("GET", getA.method)
            assertEquals("A", getA.requestUrl?.queryParameter("type"))
            val post = server.takeRequest()
            assertEquals("POST", post.method)
            assertWrite(post, "A", "203.0.113.20")
            val getAAAA = server.takeRequest()
            assertEquals("GET", getAAAA.method)
            assertEquals("AAAA", getAAAA.requestUrl?.queryParameter("type"))
            assertEquals("mc.example.com", getAAAA.requestUrl?.queryParameter("name"))
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals("DELETE", first.method)
            assertEquals("DELETE", second.method)
            assertTrue(first.path!!.endsWith("/dns_records/aaaa-1"))
            assertTrue(second.path!!.endsWith("/dns_records/aaaa-2"))
            assertEquals("Bearer $token", first.getHeader("Authorization"))
            assertEquals("Bearer $token", second.getHeader("Authorization"))
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun ipv6OnlyDeletesStaleA() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(records(record("aaaa-9"))))
            server.enqueue(MockResponse().setBody("""{"success":true}"""))
            server.enqueue(MockResponse().setBody(records(record("a-old"))))
            server.enqueue(MockResponse().setBody("""{"success":true}"""))
            val result = provider(server).apply(null, "2001:db8::20", config) as DdnsResult.Updated
            assertNull(result.ipv4)
            assertEquals("2001:db8::20", result.ipv6)
            assertTrue(result.detail.contains("已删除过期 A"))
            assertFalse(result.detail.contains("已删除过期 AAAA"))
            assertEquals("AAAA", server.takeRequest().requestUrl?.queryParameter("type"))
            val patch = server.takeRequest()
            assertEquals("PATCH", patch.method)
            assertTrue(patch.path!!.endsWith("/dns_records/aaaa-9"))
            assertWrite(patch, "AAAA", "2001:db8::20")
            val getA = server.takeRequest()
            assertEquals("GET", getA.method)
            assertEquals("A", getA.requestUrl?.queryParameter("type"))
            assertEquals("mc.example.com", getA.requestUrl?.queryParameter("name"))
            val delete = server.takeRequest()
            assertEquals("DELETE", delete.method)
            assertTrue(delete.path!!.endsWith("/dns_records/a-old"))
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun absentFamilyWithNoRecordsOnlyLists() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(records(record("rec-4"))))
            server.enqueue(MockResponse().setBody("""{"success":true}"""))
            server.enqueue(MockResponse().setBody(records()))
            val result = provider(server).apply("203.0.113.21", null, config) as DdnsResult.Updated
            assertEquals("203.0.113.21", result.ipv4)
            assertNull(result.ipv6)
            assertFalse(result.detail.contains("删除"))
            val getA = server.takeRequest()
            assertEquals("GET", getA.method)
            assertEquals("A", getA.requestUrl?.queryParameter("type"))
            val patch = server.takeRequest()
            assertEquals("PATCH", patch.method)
            assertTrue(patch.path!!.endsWith("/dns_records/rec-4"))
            val listed = server.takeRequest()
            assertEquals("GET", listed.method)
            assertEquals("AAAA", listed.requestUrl?.queryParameter("type"))
            assertEquals("mc.example.com", listed.requestUrl?.queryParameter("name"))
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun noPublicAddressDoesNotDelete() = runBlocking {
        MockWebServer().use { server ->
            val failed = provider(server).apply(null, null, config) as DdnsResult.Failed
            assertEquals("没有可用的公网地址", failed.message)
            val blank = provider(server).apply("  ", "", config) as DdnsResult.Failed
            assertEquals("没有可用的公网地址", blank.message)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun staleDeleteUsesHttpAndNetworkFailureSemantics() = runBlocking {
        for (code in listOf(401, 403)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(records()))
                server.enqueue(MockResponse().setBody("""{"success":true}"""))
                server.enqueue(MockResponse().setBody(records(record("aaaa-1"))))
                server.enqueue(MockResponse().setResponseCode(code))
                val failed = provider(server).apply("203.0.113.1", null, config) as DdnsResult.Failed
                assertFalse(failed.retryable)
                assertTrue(failed.credential)
                assertTrue(failed.message.contains(code.toString()))
                assertFalse(failed.message.contains(token))
            }
        }
        for (code in listOf(429, 500)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(records()))
                server.enqueue(MockResponse().setBody("""{"success":true}"""))
                server.enqueue(MockResponse().setBody(records(record("aaaa-1"))))
                server.enqueue(MockResponse().setResponseCode(code))
                val failed = provider(server).apply("203.0.113.1", null, config) as DdnsResult.Failed
                assertTrue(code.toString(), failed.retryable)
                assertFalse(failed.credential)
                assertFalse(failed.message.contains(token))
            }
        }
        MockWebServer().use { server ->
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                if (chain.request().method == "DELETE") throw java.io.IOException("boom")
                chain.proceed(chain.request())
            }.build()
            server.enqueue(MockResponse().setBody(records()))
            server.enqueue(MockResponse().setBody("""{"success":true}"""))
            server.enqueue(MockResponse().setBody(records(record("aaaa-1"))))
            val failed = CloudflareProvider(http, server.url("/client/v4").toString())
                .apply("203.0.113.1", null, config) as DdnsResult.Failed
            assertTrue(failed.retryable)
            assertFalse(failed.credential)
            assertEquals("Cloudflare 网络失败", failed.message)
            assertFalse(failed.message.contains(token))
        }
    }

    @Test fun authFailuresAreNotRetryableAndOmitToken() = runBlocking {
        for (code in listOf(401, 403)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(code).setBody("""{"success":false,"errors":[{"message":"no"}]}"""))
                val failed = provider(server).apply("203.0.113.1", null, config) as DdnsResult.Failed
                assertFalse(failed.retryable)
                assertTrue(failed.credential)
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
            assertFalse(failed.credential)
        }
    }

    @Test fun notFoundIsPermanentButNotACredentialRejection() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"success":false}"""))
            val failed = provider(server).apply("203.0.113.1", null, config) as DdnsResult.Failed
            assertFalse(failed.retryable)
            assertFalse(failed.credential)
            assertEquals("Cloudflare 请求失败（404）", failed.message)
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
