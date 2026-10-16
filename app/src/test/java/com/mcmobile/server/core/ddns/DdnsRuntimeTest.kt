package com.mcmobile.server.core.ddns

import com.mcmobile.server.core.net.PublicEndpoints
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class DdnsRuntimeTest {
    private val scopes = mutableListOf<CoroutineScope>()

    @After fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun scope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    private fun webhook(token: String = "") = DdnsConfig(
        enabled = true,
        provider = DdnsProviderId.WEBHOOK,
        hostname = "mc.example.com",
        webhookUrl = "https://ddns.example/hook?ip={ip}",
        token = token,
        intervalSec = 60,
    )

    private fun cloudflare(token: String) = DdnsConfig(
        enabled = true,
        provider = DdnsProviderId.CLOUDFLARE,
        hostname = "mc.example.com",
        cloudflareZoneId = "zone",
        cloudflareRecord = "mc.example.com",
        token = token,
        intervalSec = 60,
    )

    private fun counting(applies: AtomicInteger, block: suspend (String?, String?) -> DdnsResult = { v4, v6 ->
        DdnsResult.Updated(v4, v6, "ok")
    }): DdnsProvider = object : DdnsProvider {
        override val id = DdnsProviderId.WEBHOOK
        override suspend fun apply(ipv4: String?, ipv6: String?, config: DdnsConfig): DdnsResult {
            applies.incrementAndGet()
            return block(ipv4, ipv6)
        }
    }

    private fun runLoop(
        scope: CoroutineScope,
        config: DdnsConfig,
        applies: AtomicInteger = AtomicInteger(),
        detect: () -> PublicEndpoints = { PublicEndpoints("203.0.113.8", "2001:db8::8") },
        status: () -> String? = { "RUNNING" },
        clock: () -> Long = { 0L },
        sleep: suspend (Long) -> Unit = {},
        maxLoops: Int = 1,
        secretsLost: Boolean = false,
        provider: DdnsProvider = counting(applies),
        saveLast: (String?, String?, String, Long) -> Unit = { _, _, _, _ -> },
        load: () -> DdnsLoaded = { DdnsLoaded(config, secretsLost = secretsLost) },
    ): Job = DdnsRuntime.run(
        scope = scope,
        load = load,
        saveLast = saveLast,
        nics = { emptyList() },
        detect = detect,
        status = status,
        clock = clock,
        providerOf = { provider },
        sleep = sleep,
        maxLoops = maxLoops,
    )

    @Test fun shouldProbeOnlyWhileStartingOrRunning() {
        assertTrue(DdnsRuntime.shouldProbe(true, "STARTING"))
        assertTrue(DdnsRuntime.shouldProbe(true, "RUNNING"))
        assertFalse(DdnsRuntime.shouldProbe(true, "STOPPING"))
        assertFalse(DdnsRuntime.shouldProbe(true, null))
        assertFalse(DdnsRuntime.shouldProbe(false, "RUNNING"))
    }

    @Test fun missingTokenDependsOnProvider() {
        assertTrue(DdnsRuntime.missingRequiredToken(cloudflare("")))
        assertTrue(DdnsRuntime.missingRequiredToken(DdnsConfig(provider = DdnsProviderId.DUCKDNS, duckSubdomain = "mc")))
        assertFalse(DdnsRuntime.missingRequiredToken(webhook("")))
        assertFalse(DdnsRuntime.missingRequiredToken(cloudflare("token")))
    }

    @Test fun runReturnsBeforeProbeFinishes() = runBlocking {
        val release = CountDownLatch(1)
        val started = System.nanoTime()
        val job = runLoop(
            scope(),
            webhook(),
            detect = {
                assertTrue(release.await(5, TimeUnit.SECONDS))
                PublicEndpoints("203.0.113.9", null)
            },
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("run() blocked for ${elapsedMs}ms", elapsedMs < 1_000)
        assertTrue(job.isActive)
        release.countDown()
        withTimeout(5_000) { job.join() }
    }

    @Test fun secondRunStartsAfterPreviousScopeCancels() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val applies = AtomicInteger()
        val provider = counting(applies) { v4, v6 ->
            if (applies.get() == 1) gate.await()
            DdnsResult.Updated(v4, v6, "ok")
        }
        val first = scope()
        runLoop(first, webhook(), provider = provider, maxLoops = 4)
        withTimeout(3_000) { while (applies.get() == 0) delay(10) }
        first.cancel()
        val job = runLoop(scope(), webhook(), provider = provider, maxLoops = 1)
        withTimeout(5_000) { job.join() }
        assertTrue(applies.get() >= 2)
    }

    @Test fun sameLiveScopeKeepsASingleLoop() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val applies = AtomicInteger()
        val provider = counting(applies) { v4, v6 ->
            gate.await()
            DdnsResult.Updated(v4, v6, "ok")
        }
        val scope = scope()
        val first = runLoop(scope, webhook(), provider = provider, maxLoops = 2)
        val second = runLoop(scope, webhook(), provider = provider, maxLoops = 2)
        assertSame(first, second)
        withTimeout(3_000) { while (applies.get() == 0) delay(10) }
        delay(150)
        assertEquals(1, applies.get())
        gate.complete(Unit)
        withTimeout(5_000) { first.join() }
    }

    @Test fun startingAppliesAndStoppingDoesNot() = runBlocking {
        var status = "STARTING"
        var detects = 0
        val applies = AtomicInteger()
        val job = runLoop(
            scope(),
            webhook(),
            applies = applies,
            status = { status },
            detect = {
                detects++
                PublicEndpoints("203.0.113.4", null)
            },
            provider = counting(applies) { v4, v6 ->
                status = "STOPPING"
                DdnsResult.Updated(v4, v6, "ok")
            },
            maxLoops = 2,
        )
        withTimeout(5_000) { job.join() }
        assertEquals(1, applies.get())
        assertEquals(1, detects)
    }

    @Test fun disabledConfigStaysIdle() = runBlocking {
        val applies = AtomicInteger()
        var detects = 0
        var slept = 0L
        val job = runLoop(
            scope(),
            webhook().copy(enabled = false),
            applies = applies,
            detect = { detects++; PublicEndpoints("203.0.113.8", null) },
            sleep = { slept = it },
        )
        withTimeout(5_000) { job.join() }
        assertEquals(0, applies.get())
        assertEquals(0, detects)
        assertEquals(3_000L, slept)
    }

    @Test fun webhookWithEmptyTokenStillApplies() = runBlocking {
        val applies = AtomicInteger()
        var saved: String? = "unset"
        val job = runLoop(
            scope(),
            webhook(""),
            applies = applies,
            saveLast = { _, _, _, _ -> saved = "saved" },
        )
        withTimeout(5_000) { job.join() }
        assertEquals(1, applies.get())
        assertEquals("saved", saved)
    }

    @Test fun cloudflareWithoutTokenDoesNotApply() = runBlocking {
        val applies = AtomicInteger()
        var detects = 0
        var during: DdnsState? = null
        val job = runLoop(
            scope(),
            cloudflare(""),
            applies = applies,
            secretsLost = true,
            detect = { detects++; PublicEndpoints(null, null) },
            sleep = { during = DdnsRuntime.state.value },
        )
        withTimeout(5_000) { job.join() }
        assertEquals(0, applies.get())
        assertEquals(0, detects)
        assertEquals(true, during?.secretsLost)
        assertTrue(during?.lastError?.isNotBlank() == true)
    }

    @Test fun ipv4MissStillUpdatesAaaa() = runBlocking {
        val applies = AtomicInteger()
        var during: DdnsState? = null
        var saved4: String? = "unset"
        var saved6: String? = null
        val job = runLoop(
            scope(),
            webhook(),
            applies = applies,
            detect = { PublicEndpoints(null, "2001:db8::9") },
            saveLast = { v4, v6, _, _ -> saved4 = v4; saved6 = v6 },
            sleep = { during = DdnsRuntime.state.value },
        )
        withTimeout(5_000) { job.join() }
        assertEquals(1, applies.get())
        assertNull(saved4)
        assertEquals("2001:db8::9", saved6)
        assertEquals("IPv4 探测失败，仅更新 AAAA", during?.skipped)
    }

    @Test fun rejectedCredentialIsNotRetriedUntilFingerprintChanges() = runBlocking {
        var now = 0L
        var token = "token-a"
        var rotate = false
        val applies = AtomicInteger()
        val provider = counting(applies) { v4, v6 ->
            if (applies.get() == 1) DdnsResult.Failed("no", retryable = false) else DdnsResult.Updated(v4, v6, "ok")
        }
        var sawRejection = false
        val job = runLoop(
            scope(),
            cloudflare(token),
            provider = provider,
            clock = { now },
            load = {
                if (rotate) token = "token-b"
                DdnsLoaded(cloudflare(token))
            },
            sleep = { ms ->
                if (DdnsRuntime.state.value.lastError == "凭据被拒绝") sawRejection = true
                if (applies.get() >= 1) rotate = true
                now += ms
            },
            maxLoops = 3,
        )
        withTimeout(5_000) { job.join() }
        assertEquals(2, applies.get())
        assertTrue(sawRejection)
    }

    @Test fun redactDropsTokenQueryAndWebhook() {
        val config = DdnsConfig(
            token = "s3cret",
            webhookUrl = "https://hooks.example/a?token=s3cret&ip={ip}",
        )
        val raw = "fail s3cret ${config.webhookUrl} also https://hooks.example/a?token=s3cret&ip=203.0.113.1"
        val out = DdnsRuntime.redact(raw, config)
        assertFalse(out.contains("s3cret"))
        assertFalse(out.contains("hooks.example"))
    }

    @Test fun ulaDoesNotDisableEmptyAddressBackoff() = runBlocking {
        val applies = AtomicInteger()
        val sleeps = mutableListOf<Long>()
        var now = 0L
        val job = runLoop(
            scope(),
            webhook(),
            applies = applies,
            detect = { PublicEndpoints(null, "fd12:3456::1") },
            clock = { now },
            sleep = { ms ->
                sleeps += ms
                now += ms
            },
            maxLoops = 3,
        )
        withTimeout(5_000) { job.join() }
        assertEquals(0, applies.get())
        assertEquals(DdnsUpdater.DELAYS.toList(), sleeps)
        assertEquals("没有探测到公网地址", DdnsRuntime.state.value.lastError)
    }

    @Test fun publicIpv4StillUpdatesWhenIpv6IsUla() = runBlocking {
        val applies = AtomicInteger()
        var seen: Pair<String?, String?>? = null
        val provider = counting(applies) { v4, v6 ->
            seen = v4 to v6
            DdnsResult.Updated(v4, v6, "ok")
        }
        val job = runLoop(
            scope(),
            webhook(),
            provider = provider,
            detect = { PublicEndpoints("203.0.113.8", "fc00::5") },
        )
        withTimeout(5_000) { job.join() }
        assertEquals(1, applies.get())
        assertEquals("203.0.113.8" to null, seen)
    }

    @Test fun writeLogRotatesPastLimit() {
        val dir = java.nio.file.Files.createTempDirectory("ddns-log").toFile()
        try {
            val file = File(dir, "ddns.log")
            DdnsRuntime.writeLog(file, "a".repeat(40), maxBytes = 16)
            DdnsRuntime.writeLog(file, "next", maxBytes = 16)
            assertTrue(File(dir, "ddns.previous.log").exists())
            assertTrue(file.readText().contains("next"))
            assertFalse(file.readText().contains("aaaa"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
