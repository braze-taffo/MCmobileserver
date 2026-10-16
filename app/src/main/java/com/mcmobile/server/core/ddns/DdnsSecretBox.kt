package com.mcmobile.server.core.ddns

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

interface Kv {
    fun get(k: String): String?
    fun put(k: String, v: String)
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

class AndroidAesGcmSecretBox : DdnsSecretBox {
    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray = gcmDecrypt(key(), blob)

    companion object {
        const val ALIAS = "mcs-ddns-aes-v1"
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
