package com.mcmobile.server.ui.frp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mcmobile.server.core.frp.FrpConfig
import com.mcmobile.server.core.frp.FrpStore
import com.mcmobile.server.service.FrpService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrpScreen(nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by FrpService.state.collectAsState()
    var config by remember { mutableStateOf(FrpConfig()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val supported = remember { java.io.File(context.applicationInfo.nativeLibraryDir, "libfrpc.so").canExecute() }
    LaunchedEffect(Unit) {
        try { config = withContext(Dispatchers.IO) { FrpStore.load(context) } }
        catch (e: Exception) { message = "读取配置失败：${e.message}" }
        loaded = true
    }
    fun save(start: Boolean) {
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { FrpStore.save(context, config) }
                if (start) context.startForegroundService(Intent(context, FrpService::class.java))
                message = if (start) null else "配置已保存"
            } catch (e: Exception) { message = e.message ?: "操作失败" }
            finally { busy = false }
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("FRP 内网穿透") }, navigationIcon = {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("连接自建或服务商提供的 frps 节点，让外网玩家加入你的 Java 版服务器。")
            Text("先启动 Minecraft 服务器，本地端口须与 server.properties 中的 server-port 一致（默认 25565）。穿透独立运行，结束联机后请单独停止。",
                style = MaterialTheme.typography.bodySmall)
            if (!supported) Text("内置 FRP 仅支持 ARM64 设备，当前设备不可启动。", color = MaterialTheme.colorScheme.error)
            val editable = loaded && !busy && !state.running
            FrpField("节点地址（域名或 IP）", config.server, editable) { config = config.copy(server = it.trim()) }
            FrpField("节点端口", config.serverPort, editable, number = true) { config = config.copy(serverPort = it) }
            FrpField("Token（节点无认证时留空）", config.token, editable, secret = true) { config = config.copy(token = it) }
            FrpField("用户前缀 user（可选）", config.user, editable) { config = config.copy(user = it.trim()) }
            FrpField("隧道名称（同节点需唯一）", config.proxyName, editable) { config = config.copy(proxyName = it.trim()) }
            FrpField("本地 MC 端口", config.localPort, editable, number = true) { config = config.copy(localPort = it) }
            FrpField("远程端口（节点允许的端口）", config.remotePort, editable, number = true) { config = config.copy(remotePort = it) }
            FrpField("玩家连接域名 / IP（可选，默认节点地址）", config.publicHost, editable) { config = config.copy(publicHost = it.trim()) }
            Text("状态：${state.message}", style = MaterialTheme.typography.titleMedium)
            message?.let { Text(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { save(false) }, enabled = editable) { Text("保存") }
                if (state.running) {
                    Button(onClick = { context.startService(Intent(context, FrpService::class.java).setAction(FrpService.STOP)) }) { Text("停止穿透") }
                } else {
                    Button(onClick = { save(true) }, enabled = editable && supported) { Text("启动穿透") }
                }
            }
            if (state.connected) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("公网联机地址：${state.address}")
                        Text("隧道已注册；实际联机还需 MC 正在监听、节点防火墙开放远程端口。", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("Minecraft 公网地址", state.address))
                            message = "联机地址已复制"
                        }) { Text("复制地址") }
                    }
                }
            }
            Text("FRP 日志（最近 300 行）", style = MaterialTheme.typography.titleSmall)
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(state.logs.joinToString("\n").ifBlank { "暂无日志" }, style = MaterialTheme.typography.bodySmall)
            }
            Text("内置 frpc 0.71.0 · fatedier/frp · Apache-2.0\n支持标准 TCP + Token，TLS 已启用；需要定制客户端的节点可能不兼容。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FrpField(label: String, value: String, enabled: Boolean, number: Boolean = false,
    secret: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = when { number -> KeyboardType.Number; secret -> KeyboardType.Password; else -> KeyboardType.Text }),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
}
