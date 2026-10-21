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
    private var backoffUntil = 0L
    private var backoffStep = 0
    /** Fingerprint of the attempt that scheduled [backoffUntilMs]. Not the last success. */
    private var backoffFingerprint: String? = null
    private var frozenFingerprint: String? = null
    private var frozenCredential = false

    fun tick(nowMs: Long, ipv4: String?, ipv6: String?, fingerprint: String): DdnsDecision {
        if (frozenFingerprint != null && frozenFingerprint != fingerprint) {
            frozenFingerprint = null
            frozenCredential = false
            clearBackoff()
        }
        if (frozenFingerprint != null) {
            return DdnsDecision.Skip(if (frozenCredential) SKIP_CREDENTIAL else SKIP_STOPPED)
        }
        // A new config after a success used to look like a fingerprint change on every later tick,
        // because lastFingerprint stays on the previous success until the new attempt works.
        if (backoffFingerprint != null && backoffFingerprint != fingerprint) clearBackoff()
        if (lastFingerprint != null && ipv4 == lastIpv4 && ipv6 == lastIpv6 && fingerprint == lastFingerprint) {
            return DdnsDecision.Skip(SKIP_UNCHANGED)
        }
        if (ipv4 == null && ipv6 == null) return DdnsDecision.Skip(SKIP_NO_ADDRESS)
        if (nowMs < backoffUntil) return DdnsDecision.Skip(SKIP_BACKOFF)
        return DdnsDecision.Apply(ipv4, ipv6, fingerprint)
    }

    /** Epoch millis when a retryable failure may be tried again. 0 when no backoff is pending. */
    fun backoffUntilMs(): Long = backoffUntil

    fun onSuccess(ipv4: String?, ipv6: String?, fingerprint: String) {
        lastIpv4 = ipv4
        lastIpv6 = ipv6
        lastFingerprint = fingerprint
        clearBackoff()
        frozenFingerprint = null
        frozenCredential = false
    }

    fun onFailure(nowMs: Long, retryable: Boolean, fingerprint: String, credential: Boolean = false) {
        if (!retryable) {
            frozenFingerprint = fingerprint
            frozenCredential = credential
            return
        }
        backoffFingerprint = fingerprint
        backoffUntil = nowMs + DELAYS[backoffStep.coerceAtMost(DELAYS.lastIndex)]
        backoffStep = (backoffStep + 1).coerceAtMost(DELAYS.lastIndex)
    }

    private fun clearBackoff() {
        backoffStep = 0
        backoffUntil = 0L
        backoffFingerprint = null
    }

    companion object {
        val DELAYS = longArrayOf(60_000L, 300_000L, 900_000L)
        const val SKIP_UNCHANGED = "IP 未变化"
        const val SKIP_CREDENTIAL = "凭据被拒绝"
        const val SKIP_BACKOFF = "等待重试"
        const val SKIP_STOPPED = "已停止重试"
        const val SKIP_NO_ADDRESS = "没有可用的公网地址"
    }
}
