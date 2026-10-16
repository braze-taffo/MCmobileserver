package com.mcmobile.server.core.ddns

import okhttp3.OkHttpClient

internal fun OkHttpClient.noRedirects(): OkHttpClient =
    if (!this.followRedirects && !this.followSslRedirects) this
    else newBuilder().followRedirects(false).followSslRedirects(false).build()

interface DdnsProvider {
    val id: DdnsProviderId
    suspend fun apply(ipv4: String?, ipv6: String?, config: DdnsConfig): DdnsResult
}

sealed interface DdnsResult {
    data class Updated(val ipv4: String?, val ipv6: String?, val detail: String) : DdnsResult
    data class Unchanged(val reason: String) : DdnsResult
    data class Failed(val message: String, val retryable: Boolean = true) : DdnsResult
    data object Unsupported : DdnsResult
}

class StubDdnsProvider(override val id: DdnsProviderId) : DdnsProvider {
    override suspend fun apply(ipv4: String?, ipv6: String?, config: DdnsConfig): DdnsResult = DdnsResult.Unsupported
}

object DdnsProviders {
    fun of(
        id: DdnsProviderId,
        http: OkHttpClient,
        apiRoot: String = CloudflareProvider.DEFAULT_API_ROOT,
    ): DdnsProvider = when (id) {
        DdnsProviderId.CLOUDFLARE -> CloudflareProvider(http, apiRoot)
        DdnsProviderId.DUCKDNS -> DuckDnsProvider(http)
        DdnsProviderId.WEBHOOK -> WebhookProvider(http)
        DdnsProviderId.ALIYUN, DdnsProviderId.DNSPOD -> StubDdnsProvider(id)
    }
}
