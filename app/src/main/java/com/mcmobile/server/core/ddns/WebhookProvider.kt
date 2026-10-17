package com.mcmobile.server.core.ddns

import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class WebhookProvider(private val http: OkHttpClient) : DdnsProvider {
    override val id = DdnsProviderId.WEBHOOK

    override suspend fun apply(ipv4: String?, ipv6: String?, config: DdnsConfig): DdnsResult = withContext(Dispatchers.IO) {
        missingFamilies(config.webhookUrl, ipv4, ipv6)?.let { return@withContext it }
        val url = expand(config.webhookUrl, ipv4, ipv6)
            ?: return@withContext DdnsResult.Failed("Webhook 地址无效", retryable = false)
        val client = http.noRedirects()
        try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                when (response.code) {
                    in 200..299 -> DdnsResult.Updated(ipv4, ipv6, "Webhook 已调用")
                    401, 403 -> DdnsResult.Failed(
                        "Webhook 凭据被拒绝（${response.code}）",
                        retryable = false,
                        credential = true,
                    )
                    else -> DdnsResult.Failed(
                        "Webhook 请求失败（${response.code}）",
                        retryable = response.code == 429 || response.code >= 500,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            DdnsResult.Failed("Webhook 网络失败")
        }
    }

    companion object {
        /**
         * `{ipv4}` / `{ipv6}` name a family. An empty expansion would still be a 2xx and then
         * suppress later calls, so a missing referenced family fails before the request.
         * A fragment is not transmitted and does not count as a reference.
         */
        fun missingFamilies(template: String, ipv4: String?, ipv6: String?): DdnsResult.Failed? {
            val transmitted = template.substringBefore('#')
            val missing = buildList {
                if ("{ipv4}" in transmitted && ipv4.isNullOrBlank()) add("IPv4")
                if ("{ipv6}" in transmitted && ipv6.isNullOrBlank()) add("IPv6")
            }
            if (missing.isEmpty()) return null
            return DdnsResult.Failed(
                "Webhook 引用了未探测到的 ${missing.joinToString("、")}，已跳过",
                retryable = true,
            )
        }

        fun expand(template: String, ipv4: String?, ipv6: String?): okhttp3.HttpUrl? {
            if (missingFamilies(template, ipv4, ipv6) != null) return null
            if ("{ip}" in template.substringBefore('#') && ipv4.isNullOrBlank() && ipv6.isNullOrBlank()) return null
            val replaced = template
                .replace("{ipv4}", encode(ipv4.orEmpty()))
                .replace("{ipv6}", encode(ipv6.orEmpty()))
                .replace("{ip}", encode(ipv6 ?: ipv4 ?: ""))
            val parsed = replaced.toHttpUrlOrNull() ?: return null
            if (parsed.scheme != "https") return null
            return parsed
        }

        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
