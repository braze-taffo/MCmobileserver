package com.mcmobile.server.ui.ddns

import com.mcmobile.server.core.ddns.DdnsConfig
import com.mcmobile.server.core.ddns.DdnsProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DdnsScreenTest {
    @Test fun switchingProviderKeepsEachToken() {
        val cloudflare = DdnsConfig(
            provider = DdnsProviderId.CLOUDFLARE,
            token = "cf-token",
            hostname = "mc.example.com",
        )
        val (duck, afterDuck) = switchDdnsProvider(cloudflare, emptyMap(), DdnsProviderId.DUCKDNS)
        assertEquals(DdnsProviderId.DUCKDNS, duck.provider)
        assertEquals("", duck.token)
        assertEquals("cf-token", afterDuck[DdnsProviderId.CLOUDFLARE])

        val typed = duck.copy(token = "duck-token")
        val (back, afterBack) = switchDdnsProvider(typed, afterDuck, DdnsProviderId.CLOUDFLARE)
        assertEquals("cf-token", back.token)
        assertEquals("duck-token", afterBack[DdnsProviderId.DUCKDNS])

        val (again, _) = switchDdnsProvider(back, afterBack, DdnsProviderId.DUCKDNS)
        assertEquals("duck-token", again.token)

        val (same, sameTokens) = switchDdnsProvider(again, afterBack, DdnsProviderId.DUCKDNS)
        assertEquals(again, same)
        assertEquals(afterBack, sameTokens)
    }

    @Test fun savedSnapshotIgnoresInactiveRuntimeSecretsLost() {
        assertTrue(showSecretsLost(snapshotLost = true, runtimeActive = false, runtimeLost = true))
        assertFalse(showSecretsLost(snapshotLost = false, runtimeActive = false, runtimeLost = true))
        assertTrue(showSecretsLost(snapshotLost = false, runtimeActive = true, runtimeLost = true))
        assertFalse(showSecretsLost(snapshotLost = false, runtimeActive = true, runtimeLost = false))
        assertFalse(showSecretsLost(snapshotLost = false, runtimeActive = false, runtimeLost = false))
    }
}
