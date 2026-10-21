package com.mcmobile.server.core.ddns

import android.content.Context
import android.content.SharedPreferences
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

data class DdnsLoaded(
    val config: DdnsConfig,
    val secretsLost: Boolean = false,
    val lastIpv4: String? = null,
    val lastIpv6: String? = null,
    val lastFingerprint: String? = null,
    val lastSuccessAt: Long? = null,
)

class DdnsStore(private val kv: Kv, private val box: DdnsSecretBox) {
    fun load(): DdnsLoaded {
        // A concurrent save must not pair one provider's configuration with another's token.
        val values = kv.snapshot()
        val stored = runCatching {
            values[KEY_CONFIG]?.let { json.decodeFromString<DdnsConfig>(it) }
        }.getOrNull() ?: DdnsConfig()
        val read = readSecrets(values[KEY_SECRETS])
        var lastFingerprint = values[KEY_LAST_FINGERPRINT]?.ifEmpty { null }
        val webhook = when {
            !read.lost && read.webhookUrl.isNotEmpty() -> read.webhookUrl
            else -> stored.webhookUrl
        }
        val token = if (read.lost) "" else read.token
        if (!read.lost && stored.webhookUrl.isNotEmpty()) {
            val merged = stored.copy(token = token, webhookUrl = webhook)
            val oldFingerprint = lastFingerprint
            val replacement = if (!oldFingerprint.isNullOrEmpty() && oldFingerprint.contains(stored.webhookUrl)) {
                merged.fingerprint()
            } else {
                null
            }
            runCatching { write(merged, replacement) }.onSuccess {
                if (replacement != null) lastFingerprint = replacement
            }
        }
        return DdnsLoaded(
            config = stored.copy(token = token, webhookUrl = webhook),
            secretsLost = read.lost,
            lastIpv4 = values[KEY_LAST_IPV4]?.ifEmpty { null },
            lastIpv6 = values[KEY_LAST_IPV6]?.ifEmpty { null },
            lastFingerprint = lastFingerprint,
            lastSuccessAt = values[KEY_LAST_SUCCESS_AT]?.toLongOrNull(),
        )
    }

    fun save(config: DdnsConfig) {
        config.validate()
        write(config, fingerprintOverride = null)
    }

    fun saveLast(ipv4: String?, ipv6: String?, fingerprint: String, successAt: Long) {
        kv.putAll(
            mapOf(
                KEY_LAST_IPV4 to ipv4.orEmpty(),
                KEY_LAST_IPV6 to ipv6.orEmpty(),
                KEY_LAST_FINGERPRINT to fingerprint,
                KEY_LAST_SUCCESS_AT to successAt.toString(),
            ),
        )
    }

    private fun write(config: DdnsConfig, fingerprintOverride: String?) {
        val entries = linkedMapOf(
            KEY_CONFIG to json.encodeToString(config.copy(token = "", webhookUrl = "")),
            KEY_SECRETS to seal(config.token, config.webhookUrl),
        )
        if (fingerprintOverride != null) entries[KEY_LAST_FINGERPRINT] = fingerprintOverride
        kv.putAll(entries)
    }

    private fun seal(token: String, webhookUrl: String): String {
        if (token.isEmpty() && webhookUrl.isEmpty()) return ""
        val payload = json.encodeToString(DdnsSecretPayload(token = token, webhookUrl = webhookUrl))
        return Base64.getEncoder().encodeToString(box.encrypt(payload.toByteArray(Charsets.UTF_8)))
    }

    private fun readSecrets(encoded: String?): SecretRead {
        if (encoded.isNullOrEmpty()) return SecretRead(token = "", webhookUrl = "", lost = false)
        val plain = try {
            String(box.decrypt(Base64.getDecoder().decode(encoded)), Charsets.UTF_8)
        } catch (_: Exception) {
            return SecretRead(token = "", webhookUrl = "", lost = true)
        }
        val payload = parsePayload(plain)
        if (payload != null) return SecretRead(payload.token, payload.webhookUrl, lost = false)
        return SecretRead(token = plain, webhookUrl = "", lost = false)
    }

    private fun parsePayload(plain: String): DdnsSecretPayload? {
        val obj = runCatching { json.parseToJsonElement(plain) as? JsonObject }.getOrNull() ?: return null
        if (obj["kind"]?.jsonPrimitive?.contentOrNull != SECRET_KIND) return null
        return runCatching { json.decodeFromJsonElement(DdnsSecretPayload.serializer(), obj) }.getOrNull()
    }

    private data class SecretRead(val token: String, val webhookUrl: String, val lost: Boolean)

    companion object {
        private const val PREFS = "ddns"
        private const val KEY_CONFIG = "config"
        private const val KEY_SECRETS = "secrets"
        private const val KEY_LAST_IPV4 = "last_ipv4"
        private const val KEY_LAST_IPV6 = "last_ipv6"
        private const val KEY_LAST_FINGERPRINT = "last_fingerprint"
        private const val KEY_LAST_SUCCESS_AT = "last_success_at"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun android(context: Context): DdnsStore = DdnsStore(
            PrefsKv(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)),
            AndroidAesGcmSecretBox(),
        )
    }
}

private const val SECRET_KIND = "ddns-secrets-v1"

@Serializable
private data class DdnsSecretPayload(
    val kind: String = SECRET_KIND,
    val token: String = "",
    val webhookUrl: String = "",
)

private class PrefsKv(private val prefs: SharedPreferences) : Kv {
    override fun snapshot(): Map<String, String> = prefs.all.mapNotNull { (key, value) ->
        (value as? String)?.let { key to it }
    }.toMap()

    override fun get(k: String): String? = prefs.getString(k, null)

    override fun put(k: String, v: String) = putAll(mapOf(k to v))

    override fun putAll(entries: Map<String, String>) {
        if (entries.isEmpty()) return
        val editor = prefs.edit()
        for ((key, value) in entries) editor.putString(key, value)
        check(editor.commit()) { "保存 DDNS 配置失败" }
    }
}
