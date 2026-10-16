package com.mcmobile.server.core.ddns

import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class WebhookProvider(private val http: OkHttpClient) : DdnsProvider {
    override val id = DdnsProviderId.WEBHOOK

    override suspend fun apply(ipv4: String?, ipv6: String?, config: DdnsConfig): DdnsResult = withContext(Dispatchers.IO) {
        val url = expand(config.webhookUrl, ipv4, ipv6)
            ?: return@withContext DdnsResult.Failed("Webhook 地址无效", retryable = false)
        val client = http.noRedirects()
        try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                when (response.code) {
                    in 200..299 -> DdnsResult.Updated(ipv4, ipv6, "Webhook 已调用")
                    401, 403 -> DdnsResult.Failed("Webhook 凭据被拒绝（${response.code}）", retryable = false)
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
        fun expand(template: String, ipv4: String?, ipv6: String?): okhttp3.HttpUrl? {
            val replaced = template
                .replace("{ipv4}", encode(ipv4.orEmpty()))
                .replace("{ipv6}", encode(ipv6.orEmpty()))
                .replace("{ip}", encode(ipv6 ?: ipv4 ?: ""))
            val parsed = replaced.toHttpUrlOrNull() ?: return null
            if (parsed.scheme != "https") return null
            return parsed
        }

        private fun encode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    }
}
