package com.mcmobile.server.core.ddns

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DdnsStoreSnapshotTest {
    /** Writes and snapshots are atomic, just like SharedPreferences editor commits/getAll. */
    private class AtomicKv : Kv {
        private val map = linkedMapOf<String, String>()
        var afterRead: (() -> Unit)? = null

        override fun snapshot(): Map<String, String> {
            val values = synchronized(map) { map.toMap() }
            afterRead?.invoke()
            return values
        }

        override fun get(k: String): String? {
            val value = synchronized(map) { map[k] }
            // Also reproduce the old implementation's separate config/secret reads.
            if (k == "config") afterRead?.invoke()
            return value
        }

        override fun put(k: String, v: String) = putAll(mapOf(k to v))

        override fun putAll(entries: Map<String, String>) {
            synchronized(map) { map.putAll(entries) }
        }
    }

    private val duck = DdnsConfig(
        enabled = true,
        provider = DdnsProviderId.DUCKDNS,
        duckSubdomain = "review-example",
        hostname = "review-example.duckdns.org",
        token = "synthetic-duck-credential",
    )
    private val cloudflare = DdnsConfig(
        enabled = true,
        provider = DdnsProviderId.CLOUDFLARE,
        hostname = "mc.example.com",
        cloudflareZoneId = "synthetic-zone",
        token = "synthetic-cloudflare-credential",
    )

    @Test fun concurrentCloudflareSaveCannotSendItsTokenToDuckDns() =
        verifyConcurrentProviderChange(duck, cloudflare)

    @Test fun concurrentDuckDnsSaveCannotSendItsTokenToCloudflare() =
        verifyConcurrentProviderChange(cloudflare, duck)

    private fun verifyConcurrentProviderChange(before: DdnsConfig, after: DdnsConfig) = runBlocking {
        val kv = AtomicKv()
        val box = InMemorySecretBox()
        // The screen and management service use separate store instances.
        val reader = DdnsStore(kv, box)
        val writer = DdnsStore(kv, box)
        writer.save(before)
        val read = CountDownLatch(1)
        val saved = CountDownLatch(1)
        kv.afterRead = {
            read.countDown()
            check(saved.await(5, TimeUnit.SECONDS))
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit(Callable { reader.load() })
            assertTrue(read.await(5, TimeUnit.SECONDS))
            writer.save(after)
            saved.countDown()
            val loaded = pending.get(5, TimeUnit.SECONDS).config
            loaded.validate()
            assertEquals(before.provider, loaded.provider)
            MockWebServer().use { server ->
                val sentToken = when (loaded.provider) {
                    DdnsProviderId.DUCKDNS -> {
                        server.enqueue(MockResponse().setBody("OK"))
                        DuckDnsProvider(OkHttpClient(), server.url("/update").toString())
                            .apply("203.0.113.8", null, loaded)
                        server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("token")
                    }
                    DdnsProviderId.CLOUDFLARE -> {
                        server.enqueue(MockResponse().setBody("""{"success":true,"result":[]}"""))
                        server.enqueue(MockResponse().setBody("""{"success":true}"""))
                        CloudflareProvider(OkHttpClient(), server.url("/client/v4").toString())
                            .apply("203.0.113.8", null, loaded)
                        val query = server.takeRequest(5, TimeUnit.SECONDS)!!
                        val write = server.takeRequest(5, TimeUnit.SECONDS)!!
                        assertEquals(query.getHeader("Authorization"), write.getHeader("Authorization"))
                        query.getHeader("Authorization")!!.removePrefix("Bearer ")
                    }
                    else -> error("Unexpected test provider")
                }
                assertEquals("Requests must use the credential belonging to the selected provider", before.token, sentToken)
            }
            kv.afterRead = null
            assertEquals(after, reader.load().config)
        } finally {
            saved.countDown()
            executor.shutdownNow()
        }
    }
}
