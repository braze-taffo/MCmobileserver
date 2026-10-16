package com.mcmobile.server.core.ddns

import android.content.Context
import android.content.SharedPreferences
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
        val config = runCatching {
            kv.get(KEY_CONFIG)?.let { json.decodeFromString<DdnsConfig>(it) }
        }.getOrNull() ?: DdnsConfig()
        val secrets = kv.get(KEY_SECRETS)
        val (token, lost) = when {
            secrets.isNullOrEmpty() -> "" to false
            else -> runCatching {
                String(box.decrypt(Base64.getDecoder().decode(secrets)), Charsets.UTF_8) to false
            }.getOrElse { "" to true }
        }
        return DdnsLoaded(
            config = config.copy(token = token),
            secretsLost = lost,
            lastIpv4 = kv.get(KEY_LAST_IPV4)?.ifEmpty { null },
            lastIpv6 = kv.get(KEY_LAST_IPV6)?.ifEmpty { null },
            lastFingerprint = kv.get(KEY_LAST_FINGERPRINT)?.ifEmpty { null },
            lastSuccessAt = kv.get(KEY_LAST_SUCCESS_AT)?.toLongOrNull(),
        )
    }

    fun save(config: DdnsConfig) {
        config.validate()
        kv.put(KEY_CONFIG, json.encodeToString(config.copy(token = "")))
        kv.put(
            KEY_SECRETS,
            if (config.token.isEmpty()) ""
            else Base64.getEncoder().encodeToString(box.encrypt(config.token.toByteArray(Charsets.UTF_8))),
        )
    }

    fun saveLast(ipv4: String?, ipv6: String?, fingerprint: String, successAt: Long) {
        kv.put(KEY_LAST_IPV4, ipv4.orEmpty())
        kv.put(KEY_LAST_IPV6, ipv6.orEmpty())
        kv.put(KEY_LAST_FINGERPRINT, fingerprint)
        kv.put(KEY_LAST_SUCCESS_AT, successAt.toString())
    }

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

private class PrefsKv(private val prefs: SharedPreferences) : Kv {
    override fun get(k: String): String? = prefs.getString(k, null)
    override fun put(k: String, v: String) {
        check(prefs.edit().putString(k, v).commit()) { "保存 DDNS 配置失败" }
    }
}
