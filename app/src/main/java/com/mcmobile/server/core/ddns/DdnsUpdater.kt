package com.mcmobile.server.core.ddns

sealed interface DdnsDecision {
    data class Skip(val reason: String) : DdnsDecision
    data class Apply(val ipv4: String?, val ipv6: String?, val fingerprint: String) : DdnsDecision
}

class DdnsUpdater(
    private var lastIpv4: String? = null,
    private var lastIpv6: String? = null,
    private var lastFingerprint: String? = null,
) {
    private var backoffUntilMs = 0L
    private var backoffStep = 0
    private var frozenFingerprint: String? = null

    fun tick(nowMs: Long, ipv4: String?, ipv6: String?, fingerprint: String): DdnsDecision {
        if (frozenFingerprint != null && frozenFingerprint != fingerprint) {
            frozenFingerprint = null
            backoffStep = 0
            backoffUntilMs = 0L
        }
        if (frozenFingerprint != null) return DdnsDecision.Skip("凭据被拒绝")
        if (lastFingerprint != null && lastFingerprint != fingerprint) {
            backoffStep = 0
            backoffUntilMs = 0L
        }
        if (lastFingerprint != null && ipv4 == lastIpv4 && ipv6 == lastIpv6 && fingerprint == lastFingerprint) {
            return DdnsDecision.Skip("IP 未变化")
        }
        if (ipv4 == null && ipv6 == null) return DdnsDecision.Skip("没有可用的公网地址")
        if (nowMs < backoffUntilMs) return DdnsDecision.Skip("等待重试")
        return DdnsDecision.Apply(ipv4, ipv6, fingerprint)
    }

    fun onSuccess(ipv4: String?, ipv6: String?, fingerprint: String) {
        lastIpv4 = ipv4
        lastIpv6 = ipv6
        lastFingerprint = fingerprint
        backoffStep = 0
        backoffUntilMs = 0L
        frozenFingerprint = null
    }

    fun onFailure(nowMs: Long, retryable: Boolean, fingerprint: String) {
        if (!retryable) {
            frozenFingerprint = fingerprint
            return
        }
        backoffUntilMs = nowMs + DELAYS[backoffStep.coerceAtMost(DELAYS.lastIndex)]
        backoffStep = (backoffStep + 1).coerceAtMost(DELAYS.lastIndex)
    }

    companion object {
        val DELAYS = longArrayOf(60_000L, 300_000L, 900_000L)
    }
}
