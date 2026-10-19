package com.mcmobile.server.core.ddns

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class WebhookProviderTest {
    private val token = "super-secret-token"

    private fun config(url: String) = DdnsConfig(
        enabled = true,
        provider = DdnsProviderId.WEBHOOK,
        hostname = "mc.example.com",
        webhookUrl = url,
        token = token,
        intervalSec = 60,
    )

    @Test fun percentEncodesIpv6InPathAndQuery() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val seen = mutableListOf<String>()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val outgoing = chain.request()
                seen += outgoing.url.toString()
                val target = server.url(outgoing.url.encodedPath).newBuilder().apply {
                    outgoing.url.encodedQuery?.let { encodedQuery(it) }
                }.build()
                chain.proceed(outgoing.newBuilder().url(target).build())
            }.build()
            val ipv6 = "2001:db8::1"
            val result = WebhookProvider(http).apply(
                "203.0.113.7",
                ipv6,
                config("https://ddns.example/hook/{ipv6}?v4={ipv4}&ip={ip}&token=$token"),
            )
            assertTrue(result is DdnsResult.Updated)
            val parsed = seen.single().toHttpUrl()
            assertEquals("https", parsed.scheme)
            assertTrue(parsed.encodedPath.contains("%3A"))
            assertFalse(parsed.encodedPath.contains("2001:db8"))
            assertTrue(parsed.encodedQuery!!.contains("%3A"))
            assertEquals(ipv6, parsed.queryParameter("ip"))
            assertEquals("203.0.113.7", parsed.queryParameter("v4"))
            val recorded = server.takeRequest()
            assertEquals("GET", recorded.method)
            assertTrue(recorded.path!!.contains("%3A"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun missingReferencedFamilyDoesNotCallOrCountAsSuccess() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val failed = WebhookProvider(OkHttpClient()).apply(
                null,
                "2001:db8::1",
                config("https://ddns.example/hook?v4={ipv4}&v6={ipv6}"),
            ) as DdnsResult.Failed
            assertTrue(failed.retryable)
            assertFalse(failed.credential)
            assertTrue(failed.message.contains("IPv4"))
            assertFalse(failed.message.contains("ddns.example"))
            assertEquals(0, server.requestCount)
            assertNull(WebhookProvider.expand("https://ddns.example/hook?v4={ipv4}", null, "2001:db8::1"))
        }
    }

    @Test fun fragmentPlaceholderDoesNotRequireThatFamily() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val target = server.url(chain.request().url.encodedPath).newBuilder().apply {
                    chain.request().url.encodedQuery?.let { encodedQuery(it) }
                }.build()
                chain.proceed(chain.request().newBuilder().url(target).build())
            }.build()
            val result = WebhookProvider(http).apply(
                null,
                "2001:db8::2",
                config("https://ddns.example/hook?ip={ip}#{ipv4}"),
            )
            assertTrue(result is DdnsResult.Updated)
            assertEquals(1, server.requestCount)
            assertFalse(server.takeRequest().path!!.contains("ipv4"))
        }
    }

    @Test fun rejectsPlainHttpWithoutCalling() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))
            val failed = WebhookProvider(OkHttpClient()).apply(
                "203.0.113.1",
                null,
                config("http://hooks.example/update?ip={ip}&token=$token"),
            ) as DdnsResult.Failed
            assertFalse(failed.retryable)
            assertFalse(failed.message.contains(token))
            assertFalse(failed.message.contains("hooks.example"))
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun failureMessageAndSimulatedLogOmitTokenAndUrl() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            val hook = "https://hooks.example/ingest?token=$token&ip={ip}"
            val seen = mutableListOf<okhttp3.HttpUrl>()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                seen += chain.request().url
                val target = server.url(chain.request().url.encodedPath).newBuilder().apply {
                    chain.request().url.encodedQuery?.let { encodedQuery(it) }
                }.build()
                chain.proceed(chain.request().newBuilder().url(target).build())
            }.build()
            val failed = WebhookProvider(http).apply("203.0.113.1", null, config(hook)) as DdnsResult.Failed
            assertTrue(failed.retryable)
            val log = "ddns webhook failed: ${failed.message}"
            assertFalse(failed.message.contains(token))
            assertFalse(failed.message.contains("hooks.example"))
            assertFalse(log.contains(token))
            assertFalse(log.contains("hooks.example"))
            assertTrue(seen.single().queryParameter("token") == token)
        }
    }

    @Test fun requestTimeoutIsRetryable() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(408))
            // OkHttp retries 408 once when retryOnConnectionFailure is left on.
            val http = OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .addInterceptor { chain ->
                    val target = server.url(chain.request().url.encodedPath).newBuilder().apply {
                        chain.request().url.encodedQuery?.let { encodedQuery(it) }
                    }.build()
                    chain.proceed(chain.request().newBuilder().url(target).build())
                }.build()
            val failed = WebhookProvider(http).apply(
                "203.0.113.9",
                null,
                config("https://ddns.example/hook?ip={ip}"),
            ) as DdnsResult.Failed
            assertTrue(failed.retryable)
            assertFalse(failed.credential)
            assertTrue(failed.message.contains("408"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun doesNotFollowRedirects() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://evil.example/collect?token=$token"))
            server.enqueue(MockResponse().setResponseCode(200))
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val target = server.url(chain.request().url.encodedPath).newBuilder().apply {
                    chain.request().url.encodedQuery?.let { encodedQuery(it) }
                }.build()
                chain.proceed(chain.request().newBuilder().url(target).build())
            }.build()
            val failed = WebhookProvider(http).apply(
                null,
                "2001:db8::2",
                config("https://ddns.example/hook?ip={ip}"),
            ) as DdnsResult.Failed
            assertTrue(failed.message.contains("302"))
            assertFalse(failed.retryable)
            assertFalse(failed.message.contains(token))
            assertEquals(1, server.requestCount)
        }
    }
}
