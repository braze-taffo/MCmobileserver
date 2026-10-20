package com.mcmobile.server.ui.ddns

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mcmobile.server.core.console.ConsoleSession
import com.mcmobile.server.core.ddns.DdnsConfig
import com.mcmobile.server.core.ddns.DdnsLoaded
import com.mcmobile.server.core.ddns.DdnsProviderId
import com.mcmobile.server.core.ddns.DdnsRuntime
import com.mcmobile.server.core.ddns.DdnsState
import com.mcmobile.server.core.ddns.DdnsStore
import com.mcmobile.server.core.net.LanAddress
import com.mcmobile.server.data.InstanceRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DdnsScreen(nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runtime by DdnsRuntime.state.collectAsState()
    val console by ConsoleSession.state.collectAsState()
    var config by remember { mutableStateOf(DdnsConfig()) }
    var snapshot by remember { mutableStateOf(DdnsLoaded(DdnsConfig())) }
    var intervalText by remember { mutableStateOf("60") }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var tokens by remember { mutableStateOf(mapOf<DdnsProviderId, String>()) }
    LaunchedEffect(Unit) {
        try {
            val loaded = withContext(Dispatchers.IO) { DdnsStore.android(context).load() }
            snapshot = loaded
            config = loaded.config
            tokens = mapOf(loaded.config.provider to loaded.config.token)
            intervalText = loaded.config.intervalSec.toString()
        } catch (e: Exception) {
            message = "读取配置失败：${e.message}"
        }
        ready = true
    }
    val live = runtime != DdnsState()
    val ipv4 = if (live) runtime.lastIpv4 else snapshot.lastIpv4
    val ipv6 = if (live) runtime.lastIpv6 else snapshot.lastIpv6
    val successAt = if (live) runtime.lastSuccessAt else snapshot.lastSuccessAt
    val error = if (live) runtime.lastError else null
    val skipped = if (live) runtime.skipped else null
    val secretsLost = showSecretsLost(snapshot.secretsLost, runtime.active, runtime.secretsLost)
    Scaffold(topBar = {
        TopAppBar(title = { Text("动态域名") }, navigationIcon = {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("只在 Minecraft 启动或运行期间，把域名指到当前的公网地址。不单独占一条常驻通知。")
            Text(
                "有全局 IPv6 或探测到公网 IPv4，并不代表玩家能连进来。server-ip 请保持留空，不要设置 -Djava.net.preferIPv4Stack=true。连不上时请改用内网穿透。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("开服期间更新", modifier = Modifier.weight(1f))
                Switch(checked = config.enabled, onCheckedChange = { config = config.copy(enabled = it) }, enabled = ready && !busy)
            }
            Box {
                OutlinedButton(onClick = { menu = true }, enabled = ready && !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(config.provider.label())
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DdnsProviderId.entries.forEach { id ->
                        DropdownMenuItem(text = { Text(id.label()) }, onClick = {
                            val (next, kept) = switchDdnsProvider(config, tokens, id)
                            config = next
                            tokens = kept
                            menu = false
                        })
                    }
                }
            }
            DdnsField("探测间隔（秒，30–600）", intervalText, ready && !busy, number = true) {
                intervalText = it.filter(Char::isDigit).take(3)
            }
            when (config.provider) {
                DdnsProviderId.CLOUDFLARE -> {
                    DdnsField("主机名", config.hostname, ready && !busy) { config = config.copy(hostname = it.trim()) }
                    DdnsField("Zone ID", config.cloudflareZoneId, ready && !busy) { config = config.copy(cloudflareZoneId = it.trim()) }
                    DdnsField("记录名（留空则用主机名）", config.cloudflareRecord, ready && !busy) {
                        config = config.copy(cloudflareRecord = it.trim())
                    }
                    DdnsField("API Token", config.token, ready && !busy, secret = true) { config = config.copy(token = it) }
                    Text("Token 建议只授 Zone.DNS Edit。已有的橙云代理会在更新时关掉。", style = MaterialTheme.typography.bodySmall)
                }
                DdnsProviderId.DUCKDNS -> {
                    DdnsField("子域名", config.duckSubdomain, ready && !busy) { config = config.copy(duckSubdomain = it.trim()) }
                    DdnsField("Token", config.token, ready && !busy, secret = true) { config = config.copy(token = it) }
                    Text("保存后域名为 ${config.duckSubdomain.ifBlank { "子域名" }}.duckdns.org。DuckDNS 大约每 5 分钟限流一次。",
                        style = MaterialTheme.typography.bodySmall)
                }
                DdnsProviderId.WEBHOOK -> {
                    DdnsField("主机名（用于展示和复制）", config.hostname, ready && !busy) { config = config.copy(hostname = it.trim()) }
                    DdnsField("Webhook URL", config.webhookUrl, ready && !busy) { config = config.copy(webhookUrl = it.trim()) }
                    Text("地址必须是 https，并在路径或查询参数里包含 {ipv4}、{ipv6} 或 {ip}。只写在 # 后面不会发出去。URL 里的 token 会进对方的访问日志。",
                        style = MaterialTheme.typography.bodySmall)
                }
                DdnsProviderId.ALIYUN, DdnsProviderId.DNSPOD ->
                    Text("本版未实现该服务商。", color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = {
                val seconds = intervalText.toIntOrNull()
                if (seconds == null) {
                    message = "探测间隔必须在 30–600 秒之间"
                    return@Button
                }
                val next = config.copy(
                    intervalSec = seconds,
                    hostname = if (config.provider == DdnsProviderId.DUCKDNS && config.duckSubdomain.isNotBlank()) {
                        "${config.duckSubdomain}.duckdns.org"
                    } else config.hostname,
                )
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { DdnsStore.android(context).save(next) }
                        config = next
                        snapshot = snapshot.copy(config = next.copy(token = ""), secretsLost = false)
                        message = "配置已保存"
                    } catch (e: Exception) {
                        message = e.message ?: "保存失败"
                    } finally {
                        busy = false
                    }
                }
            }, enabled = ready && !busy) { Text("保存") }
            message?.let { Text(it) }
            Text(if (runtime.active) "正在按间隔探测" else "未在探测（仅在服务器启动或运行时更新）",
                style = MaterialTheme.typography.titleMedium)
            Text("IPv4：${ipv4 ?: "—"}")
            Text("IPv6：${ipv6 ?: "—"}")
            if (ula(ipv6)) Text("这个 IPv6 是局域网唯一地址（ULA），外网玩家多半连不上。", color = MaterialTheme.colorScheme.error)
            Text("上次成功：${successAt?.let(::formatTime) ?: "—"}")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            skipped?.let { Text(it) }
            if (secretsLost) Text("保存的凭据无法解密，请重新填写。", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = {
                val host = displayHost(config)
                if (host.isBlank()) {
                    message = "请先填写主机名"
                    return@TextButton
                }
                scope.launch {
                    val running = console.instanceId != null && console.serverStatus in setOf("STARTING", "RUNNING")
                    val address = if (!running) host else withContext(Dispatchers.IO) {
                        val repo = InstanceRepository.get(context)
                        val instance = console.instanceId?.let(repo::byId)
                        val port = instance?.let { LanAddress.portOf(repo.instanceDir(it)) } ?: LanAddress.DEFAULT_PORT
                        if (instance == null) host else "$host:$port"
                    }
                    val attached = address.contains(':') && address.substringAfterLast(':').toIntOrNull() != null
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Minecraft 地址", address))
                    message = if (running && attached) "联机地址已复制" else "未开服，未附端口（默认 25565）"
                }
            }) { Text("复制地址") }
        }
    }
}

@Composable
private fun DdnsField(
    label: String,
    value: String,
    enabled: Boolean,
    number: Boolean = false,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                number -> KeyboardType.Number
                secret -> KeyboardType.Password
                else -> KeyboardType.Text
            },
        ),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

/**
 * A stopped loop keeps [com.mcmobile.server.core.ddns.DdnsState.secretsLost] after the user saves a new token.
 * Hide that stale flag once the snapshot is clear, but keep the warning while the loop is still active and lost.
 */
internal fun showSecretsLost(snapshotLost: Boolean, runtimeActive: Boolean, runtimeLost: Boolean): Boolean =
    snapshotLost || (runtimeActive && runtimeLost)

/** Keep each provider's token. Switching must not submit the previous provider's credential. */
internal fun switchDdnsProvider(
    config: DdnsConfig,
    tokens: Map<DdnsProviderId, String>,
    next: DdnsProviderId,
): Pair<DdnsConfig, Map<DdnsProviderId, String>> {
    if (next == config.provider) return config to tokens
    val kept = tokens + (config.provider to config.token)
    return config.copy(provider = next, token = kept[next].orEmpty()) to kept
}

private fun DdnsProviderId.label(): String = when (this) {
    DdnsProviderId.CLOUDFLARE -> "Cloudflare"
    DdnsProviderId.DUCKDNS -> "DuckDNS"
    DdnsProviderId.WEBHOOK -> "Webhook"
    DdnsProviderId.ALIYUN -> "阿里云 DNS（未实现）"
    DdnsProviderId.DNSPOD -> "DNSPod（未实现）"
}

private fun displayHost(config: DdnsConfig): String = when (config.provider) {
    DdnsProviderId.DUCKDNS -> config.hostname.ifBlank {
        config.duckSubdomain.takeIf { it.isNotBlank() }?.let { "$it.duckdns.org" }.orEmpty()
    }
    else -> config.hostname
}

private fun ula(host: String?): Boolean {
    val text = host?.lowercase().orEmpty()
    return text.startsWith("fc") || text.startsWith("fd")
}

private fun formatTime(epochMs: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))
