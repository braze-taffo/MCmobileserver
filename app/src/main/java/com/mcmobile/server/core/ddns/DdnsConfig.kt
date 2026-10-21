package com.mcmobile.server.core.ddns

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
enum class DdnsProviderId { CLOUDFLARE, DUCKDNS, WEBHOOK, ALIYUN, DNSPOD }

@Serializable
data class DdnsConfig(
    val enabled: Boolean = false,
    val provider: DdnsProviderId = DdnsProviderId.CLOUDFLARE,
    val hostname: String = "",
    val intervalSec: Int = 60,
    val cloudflareZoneId: String = "",
    val cloudflareRecord: String = "",
    val duckSubdomain: String = "",
    val webhookUrl: String = "",
    val token: String = "",
) {
    fun fingerprint(): String {
        return listOf(
            provider.name,
            hostname,
            cloudflareZoneId,
            cloudflareRecord,
            duckSubdomain,
            webhookFingerprintPart(),
            sha16(token),
        ).joinToString("|")
    }

    /** Host hash plus a hash of the full URL. The query string, which often holds the secret, stays out. */
    private fun webhookFingerprintPart(): String {
        if (webhookUrl.isEmpty()) return ""
        val host = webhookUrl.substringAfter("://", webhookUrl)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfter('@')
            .substringBefore(':')
            .lowercase()
        return sha16(host) + sha16(webhookUrl)
    }

    fun validate() {
        if (!enabled) return
        require(intervalSec in 30..600) { "探测间隔必须在 30–600 秒之间" }
        when (provider) {
            DdnsProviderId.CLOUDFLARE -> {
                require(dnsHostname(hostname)) { "请填写有效的主机名" }
                require(cloudflareZoneId.isNotBlank() && cloudflareZoneId.none { it.isWhitespace() }) {
                    "请填写 Cloudflare Zone ID"
                }
                val record = cloudflareRecord.ifBlank { hostname }
                require(dnsHostname(record)) { "请填写有效的 DNS 记录名" }
                require(token.isNotEmpty()) { "请填写 API Token" }
            }
            DdnsProviderId.DUCKDNS -> {
                require(duckSubdomain.matches(Regex("[a-zA-Z0-9-]{1,63}"))) {
                    "DuckDNS 子域名请使用 1–63 位字母、数字或短横线"
                }
                require(token.isNotEmpty()) { "请填写 DuckDNS token" }
            }
            DdnsProviderId.WEBHOOK -> {
                require(dnsHostname(hostname)) { "请填写有效的主机名" }
                require(webhookUrl.none { it.isWhitespace() } && httpsUrlWithHost(webhookUrl) != null) {
                    "Webhook 只支持 https 地址"
                }
                require(webhookPlaceholderInPathOrQuery(webhookUrl)) {
                    "Webhook 地址的路径或查询参数需包含 {ipv4}、{ipv6} 或 {ip}"
                }
            }
            DdnsProviderId.ALIYUN, DdnsProviderId.DNSPOD ->
                throw IllegalArgumentException("本版未实现该服务商")
        }
    }

    companion object {
        private fun sha16(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }

        fun dnsHostname(value: String): Boolean {
            if (value.isBlank() || value.length > 253 || value.startsWith(".") || value.endsWith(".")) return false
            return value.split('.').all { label ->
                label.matches(Regex("[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?"))
            }
        }

        /**
         * OkHttp folds `https:///update` into host "update". Require https and a real authority
         * immediately after the scheme, and reject whitespace that the parser would otherwise trim.
         */
        fun httpsUrlWithHost(url: String): HttpUrl? {
            if (url.isEmpty() || url.any { it.isWhitespace() }) return null
            val parsed = url.toHttpUrlOrNull() ?: return null
            if (parsed.scheme != "https" || parsed.host.isEmpty()) return null
            val authority = url.substringAfter("://", "")
            if (authority.isEmpty() || authority[0] == '/' || authority[0] == '?' || authority[0] == '#') {
                return null
            }
            return parsed
        }

        /** A fragment is not sent on the wire, so a placeholder that lives only there does not count. */
        fun webhookPlaceholderInPathOrQuery(url: String): Boolean {
            var rest = url.substringBefore('#').substringAfter("://", "")
            if (rest.isEmpty()) return false
            val pathAt = rest.indexOf('/')
            val queryAt = rest.indexOf('?')
            val authorityEnd = listOf(pathAt, queryAt).filter { it >= 0 }.minOrNull() ?: rest.length
            val at = rest.indexOf('@')
            if (at in 0 until authorityEnd) rest = rest.substring(at + 1)
            val start = listOf(rest.indexOf('/'), rest.indexOf('?')).filter { it >= 0 }.minOrNull() ?: return false
            val pathQuery = rest.substring(start)
            return listOf("{ipv4}", "{ipv6}", "{ip}").any { it in pathQuery }
        }
    }
}
