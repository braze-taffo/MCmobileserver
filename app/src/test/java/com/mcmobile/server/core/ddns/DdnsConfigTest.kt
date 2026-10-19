package com.mcmobile.server.core.ddns

import org.junit.Assert.*
import org.junit.Test

class DdnsConfigTest {
    private val cf = DdnsConfig(
        enabled = true,
        provider = DdnsProviderId.CLOUDFLARE,
        hostname = "mc.example.com",
        cloudflareZoneId = "zone123",
        cloudflareRecord = "mc.example.com",
        token = "cf-secret-token",
    )
    private val duck = DdnsConfig(
        enabled = true,
        provider = DdnsProviderId.DUCKDNS,
        duckSubdomain = "myserver",
        token = "duck-token",
    )
    private val hook = DdnsConfig(
        enabled = true,
        provider = DdnsProviderId.WEBHOOK,
        hostname = "play.example.com",
        webhookUrl = "https://example.com/update?ip={ipv4}&v6={ipv6}",
    )

    @Test fun disabledDraftAllowsEmptyFields() {
        DdnsConfig().validate()
        DdnsConfig(enabled = false, intervalSec = 1, provider = DdnsProviderId.ALIYUN).validate()
    }

    @Test fun rejectsInvalidIntervalWhenEnabled() {
        listOf(29, 601, 0, -1).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { cf.copy(intervalSec = bad).validate() }
        }
        cf.copy(intervalSec = 30).validate()
        cf.copy(intervalSec = 600).validate()
    }

    @Test fun cloudflareRequiresHostnameZoneAndToken() {
        cf.validate()
        cf.copy(cloudflareRecord = "").validate()
        listOf("", "https://mc.example.com", "host name", ".example.com", "example.com.").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { cf.copy(hostname = bad).validate() }
        }
        assertThrows(IllegalArgumentException::class.java) { cf.copy(cloudflareZoneId = "").validate() }
        assertThrows(IllegalArgumentException::class.java) { cf.copy(cloudflareZoneId = "zone id").validate() }
        assertThrows(IllegalArgumentException::class.java) { cf.copy(token = "").validate() }
    }

    @Test fun duckDnsAcceptsSubdomainWithoutHostname() {
        duck.copy(hostname = "").validate()
        assertThrows(IllegalArgumentException::class.java) { duck.copy(duckSubdomain = "").validate() }
        assertThrows(IllegalArgumentException::class.java) { duck.copy(duckSubdomain = "bad_name").validate() }
        assertThrows(IllegalArgumentException::class.java) { duck.copy(token = "").validate() }
    }

    @Test fun webhookAllowsEmptyTokenButRejectsHttp() {
        hook.validate()
        hook.copy(token = "").validate()
        assertThrows(IllegalArgumentException::class.java) {
            hook.copy(webhookUrl = "http://example.com/update?ip={ipv4}").validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            hook.copy(webhookUrl = "https://example.com/update").validate()
        }
        hook.copy(webhookUrl = "https://example.com/{ip}").validate()
        hook.copy(webhookUrl = "https://example.com?ip={ip}").validate()
        hook.copy(webhookUrl = "https://example.com/update?v={ipv6}").validate()
        hook.copy(webhookUrl = "https://example.com/update?ip={ip}").validate()
        listOf(
            "https:///update?ip={ip}",
            "https:///?ip={ip}",
            "https:////hook?ip={ip}",
            "https://?ip={ip}",
            "https://",
        ).forEach { bad ->
            assertThrows(bad, IllegalArgumentException::class.java) {
                hook.copy(webhookUrl = bad).validate()
            }
        }
        listOf(
            "https://example.com/update#{ip}",
            "https://example.com/update#ip={ipv4}",
            "https://{ipv4}.example.com/hook",
            "https://user:{ip}@example.com/hook",
        ).forEach { bad ->
            assertThrows(bad, IllegalArgumentException::class.java) {
                hook.copy(webhookUrl = bad).validate()
            }
        }
    }

    @Test fun enabledAliyunAndDnspodAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            DdnsConfig(enabled = true, provider = DdnsProviderId.ALIYUN, intervalSec = 60).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            DdnsConfig(enabled = true, provider = DdnsProviderId.DNSPOD, intervalSec = 60).validate()
        }
    }

    @Test fun fingerprintOmitsPlaintextTokenAndChangesWhenTokenDoes() {
        val fp = cf.fingerprint()
        assertFalse(fp.contains(cf.token))
        assertTrue(fp.startsWith("CLOUDFLARE|mc.example.com|"))
        assertNotEquals(fp, cf.copy(token = "other-token").fingerprint())
        assertNotEquals(fp, cf.copy(hostname = "other.example.com").fingerprint())
        assertNotEquals(fp, cf.copy(cloudflareZoneId = "zone456").fingerprint())
        assertTrue(fp.contains("zone123"))
        assertEquals(cf.copy(intervalSec = 90).fingerprint(), fp)
    }

    @Test fun fingerprintOmitsWebhookSecret() {
        val url = "https://hooks.example/update?token=super-secret&ip={ipv4}"
        val fp = hook.copy(webhookUrl = url).fingerprint()
        assertFalse(fp.contains("super-secret"))
        assertFalse(fp.contains("hooks.example"))
        assertFalse(fp.contains(url))
        assertFalse(fp.contains("?"))
        assertNotEquals(fp, hook.copy(webhookUrl = "$url&x=1").fingerprint())
    }
}
