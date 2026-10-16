package com.mcmobile.server.core.ddns

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.mcmobile.server.core.console.ConsoleSession
import com.mcmobile.server.core.files.ManagedFiles
import com.mcmobile.server.core.net.PublicAddresses
import com.mcmobile.server.core.net.PublicEndpoints
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

data class DdnsState(
    val active: Boolean = false,
    val lastIpv4: String? = null,
    val lastIpv6: String? = null,
    val lastSuccessAt: Long? = null,
    val lastError: String? = null,
    val skipped: String? = null,
    val secretsLost: Boolean = false,
)

object DdnsRuntime {
    private val mutableState = MutableStateFlow(DdnsState())
    val state: StateFlow<DdnsState> = mutableState.asStateFlow()

    private val gate = Any()
    private var loopJob: Job? = null
    private var ownerScope: CoroutineScope? = null
    private val tokenQuery = Regex("(?i)(token=)[^&\\s]*")

    fun shouldProbe(enabled: Boolean, status: String?): Boolean =
        enabled && (status == "STARTING" || status == "RUNNING")

    fun missingRequiredToken(config: DdnsConfig): Boolean =
        config.token.isEmpty() &&
            (config.provider == DdnsProviderId.CLOUDFLARE || config.provider == DdnsProviderId.DUCKDNS)

    /** Schedules the loop and returns immediately. */
    fun run(
        appContext: Context,
        scope: CoroutineScope,
        nics: () -> List<Inet6Address> = { nicsFromConnectivity(appContext) },
        detect: (OkHttpClient, List<Inet6Address>, List<String>) -> PublicEndpoints = { http, list, urls ->
            PublicAddresses.detect(http, list, urls, userAgent = PublicAddresses.userAgent(versionOf(appContext)))
        },
        status: () -> String? = { ConsoleSession.state.value.serverStatus },
        clock: () -> Long = System::currentTimeMillis,
    ) {
        val store = DdnsStore.android(appContext)
        val http = httpClient()
        val logFile = File(appContext.filesDir, "ddns.log")
        launch(
            scope = scope,
            load = { store.load() },
            saveLast = { v4, v6, fp, at -> store.saveLast(v4, v6, fp, at) },
            nics = nics,
            detect = { list -> detect(http, list, PublicAddresses.DEFAULT_ECHO_URLS) },
            status = status,
            clock = clock,
            providerOf = { id -> DdnsProviders.of(id, http) },
            log = { line, config -> writeLog(logFile, redact(line, config)) },
            sleep = { delay(it) },
            maxLoops = Int.MAX_VALUE,
        )
    }

    fun run(
        scope: CoroutineScope,
        load: () -> DdnsLoaded,
        saveLast: (String?, String?, String, Long) -> Unit,
        nics: () -> List<Inet6Address>,
        detect: () -> PublicEndpoints,
        status: () -> String?,
        clock: () -> Long,
        providerOf: (DdnsProviderId) -> DdnsProvider,
        log: (String, DdnsConfig) -> Unit = { _, _ -> },
        sleep: suspend (Long) -> Unit = { delay(it) },
        maxLoops: Int = Int.MAX_VALUE,
    ): Job = launch(
        scope, load, saveLast, nics, { detect() }, status, clock, providerOf, log, sleep, maxLoops,
    )

    fun redact(text: String, config: DdnsConfig): String {
        var out = text
        if (config.webhookUrl.isNotEmpty()) {
            out = out.replace(config.webhookUrl, "[webhook]")
            config.webhookUrl.toHttpUrlOrNull()?.host?.takeIf { it.isNotBlank() }?.let { host ->
                val pattern = Regex("https?://(?:[^\\s@/]+@)?${Regex.escape(host)}(?::\\d+)?(?:/[^\\s]*)?")
                out = pattern.replace(out, "[webhook]")
            }
        }
        if (config.token.isNotEmpty()) out = out.replace(config.token, "[token]")
        return tokenQuery.replace(out) { "${it.groupValues[1]}[token]" }
    }

    fun writeLog(file: File, line: String, maxBytes: Long = 2L * 1048576) {
        runCatching {
            file.parentFile?.mkdirs()
            if (file.exists() && file.length() > maxBytes) {
                ManagedFiles.replace(file, File(file.parentFile, "ddns.previous.log"))
            }
            file.appendText("${java.time.Instant.now()} $line\n")
        }
    }

    private fun launch(
        scope: CoroutineScope,
        load: () -> DdnsLoaded,
        saveLast: (String?, String?, String, Long) -> Unit,
        nics: () -> List<Inet6Address>,
        detect: (List<Inet6Address>) -> PublicEndpoints,
        status: () -> String?,
        clock: () -> Long,
        providerOf: (DdnsProviderId) -> DdnsProvider,
        log: (String, DdnsConfig) -> Unit,
        sleep: suspend (Long) -> Unit,
        maxLoops: Int,
    ): Job = synchronized(gate) {
        val current = loopJob
        if (current != null && current.isActive && ownerScope === scope && scope.isActive) return current
        current?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext.job
            try {
                loop(load, saveLast, nics, detect, status, clock, providerOf, log, sleep, maxLoops)
            } finally {
                synchronized(gate) {
                    if (loopJob === self) {
                        loopJob = null
                        ownerScope = null
                        mutableState.value = mutableState.value.copy(active = false)
                    }
                }
            }
        }
        loopJob = job
        ownerScope = scope
        job.start()
        job
    }

    private suspend fun loop(
        load: () -> DdnsLoaded,
        saveLast: (String?, String?, String, Long) -> Unit,
        nics: () -> List<Inet6Address>,
        detect: (List<Inet6Address>) -> PublicEndpoints,
        status: () -> String?,
        clock: () -> Long,
        providerOf: (DdnsProviderId) -> DdnsProvider,
        log: (String, DdnsConfig) -> Unit,
        sleep: suspend (Long) -> Unit,
        maxLoops: Int,
    ) {
        val initial = runCatching { load() }.getOrElse { DdnsLoaded(DdnsConfig()) }
        mutableState.value = DdnsState(
            lastIpv4 = initial.lastIpv4,
            lastIpv6 = initial.lastIpv6,
            lastSuccessAt = initial.lastSuccessAt,
            secretsLost = initial.secretsLost,
        )
        val updater = DdnsUpdater(initial.lastIpv4, initial.lastIpv6, initial.lastFingerprint)
        var missStep = 0
        var missUntil = 0L
        var loops = 0
        while (coroutineContext.isActive && loops < maxLoops) {
            loops++
            val loaded = try {
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(redact("读取配置失败：${e.message}", DdnsConfig()), DdnsConfig())
                sleep(idleMs)
                continue
            }
            val config = loaded.config
            try {
                if (!shouldProbe(config.enabled, status())) {
                    mutableState.value = mutableState.value.copy(active = false, secretsLost = loaded.secretsLost)
                    sleep(idleMs)
                    continue
                }
                val invalid = runCatching { config.validate() }.exceptionOrNull()
                if (invalid != null || missingRequiredToken(config)) {
                    mutableState.value = mutableState.value.copy(
                        active = true,
                        secretsLost = loaded.secretsLost,
                        lastError = invalid?.message ?: "请填写凭据",
                        skipped = null,
                    )
                    sleep(idleMs)
                    continue
                }
                mutableState.value = mutableState.value.copy(active = true, secretsLost = loaded.secretsLost)
                val now = clock()
                val endpoints = probe(nics, detect)
                if (endpoints.ipv4 == null && endpoints.ipv6 == null) {
                    if (now >= missUntil) {
                        val wait = DdnsUpdater.DELAYS[missStep.coerceAtMost(DdnsUpdater.DELAYS.lastIndex)]
                        missStep = (missStep + 1).coerceAtMost(DdnsUpdater.DELAYS.lastIndex)
                        missUntil = now + wait
                        mutableState.value = mutableState.value.copy(
                            active = true,
                            lastError = "没有探测到公网地址",
                            skipped = null,
                            secretsLost = loaded.secretsLost,
                        )
                        log("没有探测到公网地址", config)
                    }
                    sleep((missUntil - clock()).coerceAtLeast(1L))
                    continue
                }
                missStep = 0
                missUntil = 0L
                when (val decision = updater.tick(now, endpoints.ipv4, endpoints.ipv6, config.fingerprint())) {
                    is DdnsDecision.Skip -> {
                        mutableState.value = mutableState.value.copy(
                            active = true,
                            skipped = decision.reason,
                            lastError = if (decision.reason == "凭据被拒绝") "凭据被拒绝" else mutableState.value.lastError,
                            secretsLost = loaded.secretsLost,
                        )
                        sleep(intervalMs(config))
                    }
                    is DdnsDecision.Apply -> {
                        val result = providerOf(config.provider).apply(decision.ipv4, decision.ipv6, config)
                        when (result) {
                            is DdnsResult.Updated, is DdnsResult.Unchanged -> {
                                val at = clock()
                                updater.onSuccess(decision.ipv4, decision.ipv6, decision.fingerprint)
                                saveLast(decision.ipv4, decision.ipv6, decision.fingerprint, at)
                                val detail = when (result) {
                                    is DdnsResult.Updated -> result.detail
                                    is DdnsResult.Unchanged -> result.reason
                                    else -> ""
                                }
                                mutableState.value = DdnsState(
                                    active = true,
                                    lastIpv4 = decision.ipv4,
                                    lastIpv6 = decision.ipv6,
                                    lastSuccessAt = at,
                                    skipped = if (decision.ipv4 == null && decision.ipv6 != null) "IPv4 探测失败，仅更新 AAAA" else null,
                                    secretsLost = loaded.secretsLost,
                                )
                                log("更新成功 $detail", config)
                            }
                            is DdnsResult.Failed -> {
                                updater.onFailure(clock(), result.retryable, decision.fingerprint)
                                val err = redact(if (result.retryable) result.message else "凭据被拒绝", config)
                                mutableState.value = mutableState.value.copy(
                                    active = true,
                                    lastError = err,
                                    skipped = if (result.retryable) null else "凭据被拒绝",
                                    secretsLost = loaded.secretsLost,
                                )
                                log("更新失败 $err", config)
                            }
                            DdnsResult.Unsupported -> {
                                updater.onFailure(clock(), false, decision.fingerprint)
                                mutableState.value = mutableState.value.copy(
                                    active = true,
                                    lastError = "本版未实现该服务商",
                                    skipped = null,
                                    secretsLost = loaded.secretsLost,
                                )
                            }
                        }
                        sleep(intervalMs(config))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(redact("动态域名循环失败：${e.message}", config), config)
                sleep(idleMs)
            }
        }
    }

    private fun probe(
        nics: () -> List<Inet6Address>,
        detect: (List<Inet6Address>) -> PublicEndpoints,
    ): PublicEndpoints = try {
        detect(nics())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        PublicEndpoints(null, null)
    }

    private fun intervalMs(config: DdnsConfig) = config.intervalSec.coerceIn(30, 600) * 1000L

    private fun versionOf(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName?.takeIf { it.isNotBlank() }
    }.getOrNull() ?: "dev"

    private fun httpClient() = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()
}

private const val idleMs = 3_000L

internal fun nicsFromConnectivity(context: Context): List<Inet6Address> {
    val cm = runCatching { context.getSystemService(ConnectivityManager::class.java) }.getOrNull()
        ?: return everyInet6()
    val network = runCatching { cm.activeNetwork }.getOrNull() ?: return everyInet6()
    val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
    if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return everyInet6()
    val name = runCatching { cm.getLinkProperties(network)?.interfaceName }.getOrNull()
    if (name.isNullOrBlank()) return everyInet6()
    val iface = runCatching { NetworkInterface.getByName(name) }.getOrNull() ?: return everyInet6()
    val addrs = iface.inetAddresses?.toList().orEmpty().filterIsInstance<Inet6Address>()
    val picked = PublicAddresses.pickIpv6(addrs)
    if (picked == null || !isGlobalUnicast(picked)) return everyInet6()
    return addrs
}

private fun isGlobalUnicast(host: String): Boolean {
    val addr = runCatching { InetAddress.getByName(host) }.getOrNull() as? Inet6Address ?: return false
    val top = addr.address[0].toInt() and 0xff
    return top in 0x20..0x3f
}

private fun everyInet6(): List<Inet6Address> = runCatching {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .flatMap { it.inetAddresses?.toList().orEmpty() }
        .filterIsInstance<Inet6Address>()
}.getOrDefault(emptyList())
