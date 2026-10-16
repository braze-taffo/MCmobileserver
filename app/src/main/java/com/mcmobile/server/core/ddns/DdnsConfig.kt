package com.mcmobile.server.core.ddns

import java.security.MessageDigest
import kotlinx.serialization.Serializable

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
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        val tokenHash = digest.joinToString("") { "%02x".format(it) }.take(16)
        return listOf(provider.name, hostname, cloudflareRecord, duckSubdomain, webhookUrl, tokenHash)
            .joinToString("|")
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
                require(webhookUrl.startsWith("https://") && webhookUrl.none { it.isWhitespace() }) {
                    "Webhook 只支持 https 地址"
                }
                require(listOf("{ipv4}", "{ipv6}", "{ip}").any { it in webhookUrl }) {
                    "Webhook 地址需包含 {ipv4}、{ipv6} 或 {ip}"
                }
            }
            DdnsProviderId.ALIYUN, DdnsProviderId.DNSPOD ->
                throw IllegalArgumentException("本版未实现该服务商")
        }
    }

    companion object {
        fun dnsHostname(value: String): Boolean {
            if (value.isBlank() || value.length > 253 || value.startsWith(".") || value.endsWith(".")) return false
            return value.split('.').all { label ->
                label.matches(Regex("[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?"))
            }
        }
    }
}
