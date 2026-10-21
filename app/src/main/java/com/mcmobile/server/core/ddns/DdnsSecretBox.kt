package com.mcmobile.server.core.ddns

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

interface Kv {
    /** A detached, atomic view of all values, including configuration and encrypted credentials. */
    fun snapshot(): Map<String, String>
    fun get(k: String): String?
    fun put(k: String, v: String)
    fun putAll(entries: Map<String, String>) {
        for ((key, value) in entries) put(key, value)
    }
}

interface DdnsSecretBox {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

class InMemorySecretBox(
    keyBytes: ByteArray = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) },
) : DdnsSecretBox {
    private val key: SecretKey = SecretKeySpec(keyBytes.copyOf(), "AES")

    override fun encrypt(plain: ByteArray): ByteArray = gcmEncrypt(key, plain)
    override fun decrypt(blob: ByteArray): ByteArray = gcmDecrypt(key, blob)
}

class AndroidAesGcmSecretBox internal constructor(
    private val keys: RotatingSecretKeys,
) : DdnsSecretBox {
    constructor() : this(RotatingSecretKeys(AndroidSecretKeyStore(), ALIAS))

    override fun encrypt(plain: ByteArray): ByteArray = useKey { key ->
        // Android Keystore rejects a caller-supplied IV when randomized encryption is required.
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray = useKey { key -> gcmDecrypt(key, blob) }

    private fun <T> useKey(block: (SecretKey) -> T): T {
        try {
            return block(keys.get())
        } catch (e: Exception) {
            keys.discardIfPermanent(e)
            throw e
        }
    }

    companion object {
        const val ALIAS = "mcs-ddns-aes-v1"
    }
}

/** Reads and creates the AES key. Permanent failures delete the alias before a new key is generated. */
internal interface SecretKeyStore {
    fun getOrNull(alias: String): SecretKey?
    fun contains(alias: String): Boolean
    fun delete(alias: String)
    fun generate(alias: String): SecretKey
}

internal class RotatingSecretKeys(
    private val store: SecretKeyStore,
    private val alias: String,
) {
    @Synchronized
    fun get(): SecretKey {
        try {
            store.getOrNull(alias)?.let { return it }
        } catch (e: Exception) {
            if (!permanentKeyFailure(e)) throw e
            store.delete(alias)
            return store.generate(alias)
        }
        if (store.contains(alias)) store.delete(alias)
        return store.generate(alias)
    }

    @Synchronized
    fun discardIfPermanent(error: Throwable) {
        if (permanentKeyFailure(error)) store.delete(alias)
    }
}

internal fun permanentKeyFailure(error: Throwable): Boolean {
    return generateSequence(error) { it.cause }.any { thrown ->
        thrown is UnrecoverableKeyException || invalidated(thrown)
    }
}

private fun invalidated(thrown: Throwable): Boolean {
    if (thrown is KeyPermanentlyInvalidatedException) return true
    val simple = thrown.javaClass.name.substringAfterLast('.').substringAfterLast('$')
    return simple == "KeyPermanentlyInvalidatedException"
}

private class AndroidSecretKeyStore : SecretKeyStore {
    override fun getOrNull(alias: String): SecretKey? = open().getKey(alias, null) as? SecretKey

    override fun contains(alias: String): Boolean = open().containsAlias(alias)

    override fun delete(alias: String) {
        val store = open()
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    override fun generate(alias: String): SecretKey {
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    private fun open(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val IV_SIZE = 12
private const val TAG_BITS = 128

internal fun gcmEncrypt(key: SecretKey, plain: ByteArray): ByteArray {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, ByteArray(IV_SIZE).also { java.security.SecureRandom().nextBytes(it) }))
    val iv = cipher.iv
    return iv + cipher.doFinal(plain)
}

internal fun gcmDecrypt(key: SecretKey, blob: ByteArray): ByteArray {
    require(blob.size > IV_SIZE) { "密文过短" }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, IV_SIZE)))
    return cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE)
}
