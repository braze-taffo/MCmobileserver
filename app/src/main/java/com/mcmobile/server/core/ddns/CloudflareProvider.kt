package com.mcmobile.server.core.ddns

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl

class CloudflareProvider(
    http: OkHttpClient,
    private val apiRoot: String = DEFAULT_API_ROOT,
) : DdnsProvider {
    private val http = http.noRedirects()
    override val id = DdnsProviderId.CLOUDFLARE

    override suspend fun apply(ipv4: String?, ipv6: String?, config: DdnsConfig): DdnsResult = withContext(Dispatchers.IO) {
        val families = buildList {
            if (!ipv4.isNullOrBlank()) add("A" to ipv4)
            if (!ipv6.isNullOrBlank()) add("AAAA" to ipv6)
        }
        if (families.isEmpty()) return@withContext DdnsResult.Failed("没有可用的公网地址")
        val zone = config.cloudflareZoneId.trim()
        val name = config.cloudflareRecord.ifBlank { config.hostname }.trim()
        val details = mutableListOf<String>()
        var wrote4: String? = null
        var wrote6: String? = null
        for ((type, ip) in families) {
            when (val one = upsert(type, ip, zone, name, config.token)) {
                is DdnsResult.Failed -> return@withContext one
                is DdnsResult.Updated -> {
                    details += one.detail
                    if (type == "A") wrote4 = ip else wrote6 = ip
                }
                else -> return@withContext DdnsResult.Failed("Cloudflare 更新失败")
            }
        }
        DdnsResult.Updated(wrote4, wrote6, details.joinToString("；"))
    }

    private fun upsert(type: String, ip: String, zone: String, name: String, token: String): DdnsResult {
        val listed = try {
            http.newCall(request(recordsUrl(zone, type, name), token).get().build()).execute().use { response ->
                httpFailure(response.code)?.let { return it }
                parseRecords(response.body?.string().orEmpty())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: CfRejected) {
            return DdnsResult.Failed("Cloudflare 拒绝了查询", retryable = false)
        } catch (_: IOException) {
            return DdnsResult.Failed("Cloudflare 网络失败")
        } catch (_: Exception) {
            return DdnsResult.Failed("Cloudflare 响应无法解析")
        }
        val exact = listed.filter { it.name.equals(name, ignoreCase = true) }
        val extra = if (exact.size > 1) "，另有 ${exact.size - 1} 条多余记录，仅更新第一条，未删除" else ""
        val call = if (exact.isEmpty()) {
            request(recordsUrl(zone), token).post(payload(type, name, ip))
        } else {
            request(recordsUrl(zone, id = exact[0].id), token).put(payload(type, name, ip))
        }
        return try {
            http.newCall(call.build()).execute().use { response ->
                httpFailure(response.code)?.let { return it }
                rejectedWrite(response.body?.string().orEmpty(), type)?.let { return it }
            }
            DdnsResult.Updated(if (type == "A") ip else null, if (type == "AAAA") ip else null, "$type 已更新$extra")
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            DdnsResult.Failed("Cloudflare 网络失败")
        }
    }

    private fun request(url: okhttp3.HttpUrl, token: String) =
        Request.Builder().url(url).header("Authorization", "Bearer $token")

    private fun recordsUrl(zone: String, type: String? = null, name: String? = null, id: String? = null): okhttp3.HttpUrl {
        val builder = apiRoot.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegment("zones")
            .addPathSegment(zone)
            .addPathSegment("dns_records")
        if (id != null) builder.addPathSegment(id)
        if (type != null) builder.addQueryParameter("type", type)
        if (name != null) builder.addQueryParameter("name", name)
        return builder.build()
    }

    private fun payload(type: String, name: String, ip: String) =
        json.encodeToString(CfBody(type, name, ip)).toRequestBody(JSON)

    private fun rejectedWrite(body: String, type: String): DdnsResult.Failed? {
        if (body.isBlank()) return DdnsResult.Failed("Cloudflare 响应为空", retryable = true)
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return DdnsResult.Failed("Cloudflare 响应无法解析", retryable = true)
        return when (obj["success"]?.jsonPrimitive?.booleanOrNull) {
            true -> null
            false -> DdnsResult.Failed("Cloudflare 拒绝了$type 更新", retryable = false)
            null -> DdnsResult.Failed("Cloudflare 响应无法解析", retryable = true)
        }
    }

    private fun parseRecords(body: String): List<CfRecord> {
        val obj = json.parseToJsonElement(body).jsonObject
        val ok = obj["success"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!ok) throw CfRejected()
        val result = obj["result"]
        if (result == null || result is JsonNull) return emptyList()
        return result.jsonArray.map { el ->
            val record = el.jsonObject
            CfRecord(
                id = record["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                name = record["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
    }

    companion object {
        const val DEFAULT_API_ROOT = "https://api.cloudflare.com/client/v4"
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        private fun httpFailure(code: Int): DdnsResult.Failed? {
            if (code in 200..299) return null
            val retryable = code == 429 || code >= 500
            val message = when (code) {
                401, 403 -> "Cloudflare 凭据被拒绝（$code）"
                429 -> "Cloudflare 请求受限（429）"
                else -> "Cloudflare 请求失败（$code）"
            }
            return DdnsResult.Failed(message, retryable = retryable)
        }
    }
}

@Serializable
private data class CfBody(
    val type: String,
    val name: String,
    val content: String,
    val ttl: Int = 120,
    val proxied: Boolean = false,
)

private data class CfRecord(val id: String, val name: String)

private class CfRejected : Exception()
