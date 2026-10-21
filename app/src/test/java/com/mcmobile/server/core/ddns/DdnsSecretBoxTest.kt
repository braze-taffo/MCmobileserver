package com.mcmobile.server.core.ddns

import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DdnsSecretBoxTest {
    private class MemoryKv : Kv {
        private val map = linkedMapOf<String, String>()
        override fun snapshot(): Map<String, String> = map.toMap()
        override fun get(k: String): String? = map[k]
        override fun put(k: String, v: String) { map[k] = v }
        fun contains(k: String) = k in map
    }

    private class BatchKv : Kv {
        val writes = mutableListOf<Map<String, String>>()
        private val map = linkedMapOf<String, String>()
        override fun snapshot(): Map<String, String> = map.toMap()
        override fun get(k: String): String? = map[k]
        override fun put(k: String, v: String) {
            map[k] = v
            writes += mapOf(k to v)
        }
        override fun putAll(entries: Map<String, String>) {
            map.putAll(entries)
            writes += LinkedHashMap(entries)
        }
    }

    private class FakeSecretKeyStore : SecretKeyStore {
        var key: SecretKey? = null
        var aliasPresent: Boolean = false
        var getError: Exception? = null
        var deletes: Int = 0
        var generates: Int = 0
        var generateSawExistingAlias: Boolean = false
        private var generation: Int = 0

        override fun getOrNull(alias: String): SecretKey? {
            getError?.let { throw it }
            return key
        }

        override fun contains(alias: String): Boolean = aliasPresent || key != null

        override fun delete(alias: String) {
            deletes++
            key = null
            aliasPresent = false
            getError = null
        }

        override fun generate(alias: String): SecretKey {
            generates++
            if (aliasPresent || key != null) {
                generateSawExistingAlias = true
                throw KeyStoreException("alias already exists")
            }
            generation++
            val material = generation
            return SecretKeySpec(ByteArray(32) { material.toByte() }, "AES").also { key = it }
        }
    }

    /** Same simple name as the Android exception, which cannot be constructed on the android.jar stub. */
    private class KeyPermanentlyInvalidatedException(message: String) : java.security.InvalidKeyException(message)

    private val storedJson = Json { encodeDefaults = true }

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

    @Test fun saveWritesConfigAndSecretsInOneBatch() {
        val kv = BatchKv()
        DdnsStore(kv, InMemorySecretBox()).save(sample)
        assertEquals(1, kv.writes.size)
        assertTrue(kv.writes.single().containsKey("config"))
        assertTrue(kv.writes.single().containsKey("secrets"))
    }

    @Test fun saveEncryptsWebhookAndLeavesItOutOfConfigAndFingerprint() {
        val kv = BatchKv()
        val store = DdnsStore(kv, InMemorySecretBox())
        val url = "https://hooks.example/update?token=super-secret&ip={ipv4}"
        val hook = DdnsConfig(
            enabled = true,
            provider = DdnsProviderId.WEBHOOK,
            hostname = "play.example.com",
            webhookUrl = url,
        )
        store.save(hook)
        assertEquals(1, kv.writes.size)
        val configJson = kv.get("config")!!
        val secrets = kv.get("secrets")!!
        assertFalse(configJson.contains("super-secret"))
        assertFalse(configJson.contains("hooks.example"))
        assertFalse(secrets.contains("super-secret"))
        assertFalse(secrets.contains("hooks.example"))
        val loaded = store.load()
        assertEquals(url, loaded.config.webhookUrl)
        assertEquals("", loaded.config.token)
        assertFalse(loaded.secretsLost)
        val fp = loaded.config.fingerprint()
        assertFalse(fp.contains("super-secret"))
        assertFalse(fp.contains("hooks.example"))
        assertFalse(fp.contains("?"))
        store.saveLast(null, null, fp, 7L)
        assertFalse(kv.get("last_fingerprint")!!.contains("super-secret"))
        assertFalse(kv.get("last_fingerprint")!!.contains("hooks.example"))
        assertEquals(2, kv.writes.size)
    }

    @Test fun loadMigratesPlaintextWebhookAndRawToken() {
        val kv = BatchKv()
        val box = InMemorySecretBox()
        val webhook = "https://hooks.example/update?token=legacy-secret&ip={ip}"
        val token = "cf-secret-token"
        kv.put("config", storedJson.encodeToString(sample.copy(token = "", webhookUrl = webhook)))
        kv.put("secrets", Base64.getEncoder().encodeToString(box.encrypt(token.toByteArray(Charsets.UTF_8))))
        kv.put("last_fingerprint", "CLOUDFLARE|mc.example.com|mc.example.com||$webhook|deadbeef")
        kv.writes.clear()
        val store = DdnsStore(kv, box)
        val loaded = store.load()
        assertEquals(token, loaded.config.token)
        assertEquals(webhook, loaded.config.webhookUrl)
        assertFalse(loaded.secretsLost)
        val configJson = kv.get("config")!!
        assertFalse(configJson.contains("legacy-secret"))
        assertFalse(configJson.contains("hooks.example"))
        assertFalse(configJson.contains(token))
        assertEquals(1, kv.writes.size)
        assertTrue(kv.writes.single().keys.containsAll(listOf("config", "secrets", "last_fingerprint")))
        assertFalse(loaded.lastFingerprint!!.contains("legacy-secret"))
        assertFalse(loaded.lastFingerprint!!.contains(webhook))
        assertEquals(loaded.config.fingerprint(), loaded.lastFingerprint)
        val again = store.load()
        assertEquals(webhook, again.config.webhookUrl)
        assertEquals(token, again.config.token)
        assertEquals(1, kv.writes.size)
    }

    @Test fun corruptSecretsAreLostAndDoNotRewritePlaintextWebhook() {
        val kv = MemoryKv()
        val webhook = "https://hooks.example/update?token=legacy-secret&ip={ip}"
        kv.put("config", storedJson.encodeToString(sample.copy(token = "", webhookUrl = webhook)))
        kv.put("secrets", Base64.getEncoder().encodeToString("not-gcm".toByteArray()))
        val loaded = DdnsStore(kv, InMemorySecretBox()).load()
        assertTrue(loaded.secretsLost)
        assertEquals("", loaded.config.token)
        assertEquals(webhook, loaded.config.webhookUrl)
        assertTrue(kv.get("config")!!.contains("legacy-secret"))
    }

    @Test fun permanentKeyFailureIsNarrow() {
        assertTrue(permanentKeyFailure(UnrecoverableKeyException("gone")))
        assertTrue(permanentKeyFailure(KeyPermanentlyInvalidatedException("gone")))
        assertTrue(permanentKeyFailure(IllegalStateException("wrap", UnrecoverableKeyException("gone"))))
        assertFalse(permanentKeyFailure(KeyStoreException("locked")))
        assertFalse(permanentKeyFailure(AEADBadTagException("tag")))
        assertFalse(permanentKeyFailure(IllegalArgumentException("short")))
    }

    @Test fun nullKeyWithExistingAliasIsDeletedBeforeGenerate() {
        val keys = FakeSecretKeyStore().apply { aliasPresent = true }
        val created = RotatingSecretKeys(keys, AndroidAesGcmSecretBox.ALIAS).get()
        assertNotNull(created)
        assertEquals(1, keys.deletes)
        assertEquals(1, keys.generates)
        assertFalse(keys.generateSawExistingAlias)
    }

    @Test fun invalidatedKeyIsDeletedAndRecreated() {
        val keys = FakeSecretKeyStore().apply {
            aliasPresent = true
            getError = KeyPermanentlyInvalidatedException("gone")
        }
        assertNotNull(RotatingSecretKeys(keys, AndroidAesGcmSecretBox.ALIAS).get())
        assertEquals(1, keys.deletes)
        assertEquals(1, keys.generates)
        assertFalse(keys.generateSawExistingAlias)
    }

    @Test fun transientGetDoesNotDeleteAlias() {
        val keys = FakeSecretKeyStore().apply {
            aliasPresent = true
            key = SecretKeySpec(ByteArray(32) { 1 }, "AES")
            getError = KeyStoreException("locked")
        }
        assertThrows(KeyStoreException::class.java) { RotatingSecretKeys(keys, AndroidAesGcmSecretBox.ALIAS).get() }
        assertEquals(0, keys.deletes)
        assertEquals(0, keys.generates)
    }

    @Test fun cipherInvalidationDeletesAliasButBadTagDoesNot() {
        val keys = FakeSecretKeyStore().apply { key = SecretKeySpec(ByteArray(32) { 2 }, "AES") }
        val rotating = RotatingSecretKeys(keys, AndroidAesGcmSecretBox.ALIAS)
        rotating.discardIfPermanent(KeyPermanentlyInvalidatedException("gone"))
        assertEquals(1, keys.deletes)
        assertNull(keys.key)
        rotating.discardIfPermanent(AEADBadTagException("tag"))
        assertEquals(1, keys.deletes)
    }

    @Test fun unrecoverableKeyLetsTheUserSaveAgainWhileOldCiphertextIsLost() {
        val keys = FakeSecretKeyStore()
        val box = AndroidAesGcmSecretBox(RotatingSecretKeys(keys, AndroidAesGcmSecretBox.ALIAS))
        val store = DdnsStore(MemoryKv(), box)
        store.save(sample)
        keys.getError = UnrecoverableKeyException("broken")
        keys.aliasPresent = true
        val loaded = store.load()
        assertTrue(loaded.secretsLost)
        assertEquals("", loaded.config.token)
        assertTrue(keys.deletes >= 1)
        store.save(sample.copy(token = "new-token"))
        val again = store.load()
        assertFalse(again.secretsLost)
        assertEquals("new-token", again.config.token)
    }

    @Test fun transientKeystoreErrorDoesNotDeleteAndStillDecryptsLater() {
        val keys = FakeSecretKeyStore()
        val box = AndroidAesGcmSecretBox(RotatingSecretKeys(keys, AndroidAesGcmSecretBox.ALIAS))
        val store = DdnsStore(MemoryKv(), box)
        store.save(sample)
        val deletesAfterSave = keys.deletes
        keys.getError = KeyStoreException("locked")
        val loaded = store.load()
        assertTrue(loaded.secretsLost)
        assertEquals(deletesAfterSave, keys.deletes)
        keys.getError = null
        val again = store.load()
        assertFalse(again.secretsLost)
        assertEquals(sample.token, again.config.token)
        assertEquals(deletesAfterSave, keys.deletes)
    }

    @Test fun tamperedCiphertextDoesNotDeleteTheKey() {
        val keys = FakeSecretKeyStore()
        val box = AndroidAesGcmSecretBox(RotatingSecretKeys(keys, AndroidAesGcmSecretBox.ALIAS))
        val blob = box.encrypt("hello".toByteArray())
        blob[blob.lastIndex] = blob[blob.lastIndex].inc()
        assertThrows(Exception::class.java) { box.decrypt(blob) }
        assertEquals(0, keys.deletes)
        assertEquals("hello", String(box.decrypt(box.encrypt("hello".toByteArray()))))
    }
}
