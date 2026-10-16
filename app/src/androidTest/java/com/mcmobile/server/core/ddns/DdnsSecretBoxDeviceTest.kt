package com.mcmobile.server.core.ddns

import junit.framework.TestCase

/** Real Android Keystore AES-GCM round-trip; skipped when the store is unavailable. */
class DdnsSecretBoxDeviceTest : TestCase() {
    fun testAesGcmRoundTrip() {
        val box = try {
            AndroidAesGcmSecretBox()
        } catch (e: Exception) {
            if (unavailable(e)) return else throw e
        }
        val plain = "ddns-token-secret".toByteArray()
        val first = try {
            box.encrypt(plain)
        } catch (e: Exception) {
            if (unavailable(e)) return else throw e
        }
        assertEquals("ddns-token-secret", String(box.decrypt(first)))
        val second = box.encrypt(plain)
        assertFalse(first.contentEquals(second))
        assertEquals("ddns-token-secret", String(box.decrypt(second)))
    }

    private fun unavailable(e: Throwable): Boolean {
        val text = generateSequence(e) { it.cause }.joinToString(" ") { it.javaClass.name + " " + (it.message ?: "") }
        return "AndroidKeyStore" in text || "KeyStore" in text || "keystore" in text.lowercase()
    }
}
