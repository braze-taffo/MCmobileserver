package com.mcmobile.server.core.ddns

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class DuckDnsProvider(
    private val http: OkHttpClient,
    private val endpoint: String = DEFAULT_ENDPOINT,
) : DdnsProvider {
    override val id = DdnsProviderId.DUCKDNS

    override suspend fun apply(ipv4: String?, ipv6: String?, config: DdnsConfig): DdnsResult = withContext(Dispatchers.IO) {
        if (ipv4.isNullOrBlank() && ipv6.isNullOrBlank()) return@withContext DdnsResult.Failed("没有可用的公网地址")
        val url = try {
            endpoint.toHttpUrl().newBuilder()
                .addQueryParameter("domains", config.duckSubdomain.trim())
                .addQueryParameter("token", config.token)
                .addQueryParameter("verbose", "true")
                .apply {
                    if (!ipv4.isNullOrBlank()) addQueryParameter("ip", ipv4)
                    if (!ipv6.isNullOrBlank()) addQueryParameter("ipv6", ipv6)
                }
                .build()
        } catch (_: Exception) {
            return@withContext DdnsResult.Failed("DuckDNS 地址无效", retryable = false)
        }
        try {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                val body = response.body?.string().orEmpty().trim()
                val safe = scrub(body, config.token)
                if (response.code == 401 || response.code == 403) {
                    return@withContext DdnsResult.Failed("DuckDNS 凭据被拒绝（${response.code}）", retryable = false)
                }
                if (!response.isSuccessful) {
                    return@withContext DdnsResult.Failed(
                        if (safe.isBlank()) "DuckDNS 请求失败（${response.code}）" else "DuckDNS 更新失败：$safe",
                        retryable = response.code == 429 || response.code >= 500,
                    )
                }
                when {
                    safe.startsWith("OK") -> DdnsResult.Updated(ipv4, ipv6, "DuckDNS 已更新")
                    safe.startsWith("KO") -> DdnsResult.Failed("DuckDNS 更新失败：$safe")
                    else -> DdnsResult.Failed("DuckDNS 返回异常")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            DdnsResult.Failed("DuckDNS 网络失败")
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://www.duckdns.org/update"

        private fun scrub(body: String, token: String): String {
            val cleaned = if (token.isEmpty()) body else body.replace(token, "[redacted]")
            return cleaned.take(300)
        }
    }
}
