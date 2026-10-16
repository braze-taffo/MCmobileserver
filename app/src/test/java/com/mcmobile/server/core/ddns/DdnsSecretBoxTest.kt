package com.mcmobile.server.core.ddns

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class DdnsSecretBoxTest {
    private class MemoryKv : Kv {
        private val map = linkedMapOf<String, String>()
        override fun get(k: String): String? = map[k]
        override fun put(k: String, v: String) { map[k] = v }
        fun contains(k: String) = k in map
    }

    private val sample = DdnsConfig(
        enabled = true,
        provider = DdnsProviderId.CLOUDFLARE,
        hostname = "mc.example.com",
        cloudflareZoneId = "zone123",
        token = "cf-secret-token",
    )

    @Test fun inMemoryBoxRoundTripsAndUsesFreshIv() {
        val box = InMemorySecretBox()
        val first = box.encrypt("hello".toByteArray())
        val second = box.encrypt("hello".toByteArray())
        assertEquals("hello", String(box.decrypt(first)))
        assertEquals("hello", String(box.decrypt(second)))
        assertFalse(first.contentEquals(second))
    }

    @Test fun missingSecretsKeyIsNotLost() {
        val kv = MemoryKv()
        val loaded = DdnsStore(kv, InMemorySecretBox()).load()
        assertEquals("", loaded.config.token)
        assertFalse(loaded.secretsLost)
        assertEquals(DdnsConfig(), loaded.config)
    }

    @Test fun saveRoundTripsTokenAndStripsItFromJson() {
        val kv = MemoryKv()
        val store = DdnsStore(kv, InMemorySecretBox())
        store.save(sample)
        val loaded = store.load()
        assertEquals(sample.token, loaded.config.token)
        assertFalse(loaded.secretsLost)
        assertFalse(kv.get("config")!!.contains("cf-secret-token"))
        assertTrue(kv.contains("secrets"))
        assertTrue(kv.get("secrets")!!.isNotEmpty())
    }

    @Test fun badCiphertextMarksSecretsLost() {
        val kv = MemoryKv()
        val store = DdnsStore(kv, InMemorySecretBox())
        store.save(sample)
        kv.put("secrets", Base64.getEncoder().encodeToString("not-gcm-ciphertext".toByteArray()))
        val loaded = store.load()
        assertEquals("", loaded.config.token)
        assertTrue(loaded.secretsLost)
        assertEquals("mc.example.com", loaded.config.hostname)
    }

    @Test fun emptySecretsAfterClearIsDraftNotLost() {
        val kv = MemoryKv()
        val store = DdnsStore(kv, InMemorySecretBox())
        store.save(sample)
        store.save(sample.copy(enabled = false, token = ""))
        val loaded = store.load()
        assertEquals("", loaded.config.token)
        assertFalse(loaded.secretsLost)
    }

    @Test fun loadNeverThrowsOnCorruptJson() {
        val kv = MemoryKv()
        kv.put("config", "{not-json")
        val loaded = DdnsStore(kv, InMemorySecretBox()).load()
        assertEquals(DdnsConfig(), loaded.config)
        assertFalse(loaded.secretsLost)
    }

    @Test fun saveLastPersistsPlaintextEndpoints() {
        val kv = MemoryKv()
        val store = DdnsStore(kv, InMemorySecretBox())
        store.saveLast("203.0.113.1", "2001:db8::1", "fp", 42L)
        val loaded = store.load()
        assertEquals("203.0.113.1", loaded.lastIpv4)
        assertEquals("2001:db8::1", loaded.lastIpv6)
        assertEquals("fp", loaded.lastFingerprint)
        assertEquals(42L, loaded.lastSuccessAt)
    }
}
