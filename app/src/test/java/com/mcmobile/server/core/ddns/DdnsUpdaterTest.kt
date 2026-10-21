package com.mcmobile.server.core.ddns

import org.junit.Assert.*
import org.junit.Test

class DdnsUpdaterTest {
    private val base = DdnsConfig(
        enabled = true,
        provider = DdnsProviderId.CLOUDFLARE,
        hostname = "mc.example.com",
        cloudflareZoneId = "zone",
        cloudflareRecord = "mc.example.com",
        token = "token-a",
    )
    private val v4 = "203.0.113.1"
    private val v6 = "2001:db8::1"

    private fun applyCount(updater: DdnsUpdater, now: Long, ipv4: String?, ipv6: String?, fp: String): Int {
        var n = 0
        when (val decision = updater.tick(now, ipv4, ipv6, fp)) {
            is DdnsDecision.Apply -> {
                n++
                updater.onSuccess(decision.ipv4, decision.ipv6, decision.fingerprint)
            }
            is DdnsDecision.Skip -> Unit
        }
        return n
    }

    @Test fun skipsWhenIpAndFingerprintMatch() {
        val updater = DdnsUpdater()
        val fp = base.fingerprint()
        assertEquals(1, applyCount(updater, 0, v4, v6, fp))
        assertEquals(0, applyCount(updater, 1, v4, v6, fp))
        assertTrue(updater.tick(2, v4, v6, fp) is DdnsDecision.Skip)
    }

    @Test fun appliesWhenHostnameChangesWithSameIp() {
        val updater = DdnsUpdater()
        updater.onSuccess(v4, v6, base.fingerprint())
        val renamed = base.copy(hostname = "other.example.com", cloudflareRecord = "other.example.com")
        val decision = updater.tick(0, v4, v6, renamed.fingerprint())
        assertTrue(decision is DdnsDecision.Apply)
    }

    @Test fun appliesWhenTokenIsRefilled() {
        val updater = DdnsUpdater()
        updater.onSuccess(v4, v6, base.fingerprint())
        val refilled = base.copy(token = "token-b")
        assertNotEquals(base.fingerprint(), refilled.fingerprint())
        assertTrue(updater.tick(0, v4, v6, refilled.fingerprint()) is DdnsDecision.Apply)
    }

    @Test fun retryableFailuresBackOffOneFiveFifteenMinutes() {
        val updater = DdnsUpdater()
        val fp = base.fingerprint()
        assertTrue(updater.tick(0, v4, null, fp) is DdnsDecision.Apply)
        updater.onFailure(0, retryable = true, fp)
        assertEquals(60_000L, updater.backoffUntilMs())
        assertTrue(updater.tick(59_999, v4, null, fp) is DdnsDecision.Skip)
        assertTrue(updater.tick(60_000, v4, null, fp) is DdnsDecision.Apply)
        updater.onFailure(60_000, retryable = true, fp)
        assertTrue(updater.tick(60_000 + 299_999, v4, null, fp) is DdnsDecision.Skip)
        assertTrue(updater.tick(60_000 + 300_000, v4, null, fp) is DdnsDecision.Apply)
        updater.onFailure(360_000, retryable = true, fp)
        assertTrue(updater.tick(360_000 + 899_999, v4, null, fp) is DdnsDecision.Skip)
        assertTrue(updater.tick(360_000 + 900_000, v4, null, fp) is DdnsDecision.Apply)
    }

    @Test fun nonRetryableFailureFreezesUntilFingerprintChanges() {
        val updater = DdnsUpdater()
        val fp = base.fingerprint()
        assertTrue(updater.tick(0, v4, null, fp) is DdnsDecision.Apply)
        updater.onFailure(0, retryable = false, fp, credential = true)
        assertEquals("凭据被拒绝", (updater.tick(900_000, v4, null, fp) as DdnsDecision.Skip).reason)
        assertTrue(updater.tick(1_800_000, v4, null, fp) is DdnsDecision.Skip)
        val next = base.copy(token = "rotated")
        assertTrue(updater.tick(1_800_000, v4, null, next.fingerprint()) is DdnsDecision.Apply)
    }

    @Test fun otherPermanentFailureIsNotLabeledAsCredentials() {
        val updater = DdnsUpdater()
        val fp = base.fingerprint()
        updater.onFailure(0, retryable = false, fp, credential = false)
        assertEquals(DdnsUpdater.SKIP_STOPPED, (updater.tick(900_000, v4, null, fp) as DdnsDecision.Skip).reason)
        assertTrue(updater.tick(1_800_000, v4, null, fp) is DdnsDecision.Skip)
        val next = base.copy(cloudflareZoneId = "other-zone")
        assertTrue(updater.tick(1_800_000, v4, null, next.fingerprint()) is DdnsDecision.Apply)
    }

    @Test fun retryableFailureAfterConfigChangeKeepsBackoff() {
        val updater = DdnsUpdater()
        updater.onSuccess(v4, null, base.fingerprint())
        val changed = base.copy(hostname = "other.example.com", cloudflareRecord = "other.example.com")
        val fp = changed.fingerprint()
        assertTrue(updater.tick(0, v4, null, fp) is DdnsDecision.Apply)
        updater.onFailure(0, retryable = true, fp)
        assertEquals(DdnsUpdater.SKIP_BACKOFF, (updater.tick(1_000, v4, null, fp) as DdnsDecision.Skip).reason)
        assertTrue(updater.tick(59_999, v4, null, fp) is DdnsDecision.Skip)
        assertTrue(updater.tick(60_000, v4, null, fp) is DdnsDecision.Apply)
    }

    @Test fun fingerprintChangeDuringBackoffAppliesImmediately() {
        val updater = DdnsUpdater()
        val fp = base.fingerprint()
        updater.onSuccess(v4, null, fp)
        assertTrue(updater.tick(0, "203.0.113.9", null, fp) is DdnsDecision.Apply)
        updater.onFailure(0, retryable = true, fp)
        val next = base.copy(token = "other").fingerprint()
        assertTrue(updater.tick(1_000, v4, null, next) is DdnsDecision.Apply)
    }

    @Test fun fakeProviderIsCountedOnlyOnApply() {
        val updater = DdnsUpdater()
        val fp = base.fingerprint()
        var applies = 0
        val provider = object : DdnsProvider {
            override val id = DdnsProviderId.CLOUDFLARE
            override suspend fun apply(ipv4: String?, ipv6: String?, config: DdnsConfig): DdnsResult {
                applies++
                return DdnsResult.Updated(ipv4, ipv6, "ok")
            }
        }
        repeat(3) {
            when (val decision = updater.tick(it.toLong(), v4, v6, fp)) {
                is DdnsDecision.Apply -> {
                    kotlinx.coroutines.runBlocking { provider.apply(decision.ipv4, decision.ipv6, base) }
                    updater.onSuccess(decision.ipv4, decision.ipv6, decision.fingerprint)
                }
                is DdnsDecision.Skip -> Unit
            }
        }
        assertEquals(1, applies)
    }
}
