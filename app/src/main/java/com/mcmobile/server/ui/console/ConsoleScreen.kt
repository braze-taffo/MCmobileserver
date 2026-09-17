package com.mcmobile.server.ui.console

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mcmobile.server.core.console.ConsoleSession
import com.mcmobile.server.ui.AppViewModel
import com.mcmobile.server.ui.theme.ConsoleBg
import com.mcmobile.server.ui.theme.ConsoleRed
import com.mcmobile.server.ui.theme.ConsoleTextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(vm: AppViewModel, nav: NavController) {
    val lines by vm.consoleLines.collectAsState()
    val state by vm.consoleState.collectAsState()
    val lanAddress by vm.lanAddress.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    val running = state.serverStatus in setOf("RUNNING", "STARTING", "STOPPING")

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("控制台", fontWeight = FontWeight.Bold)
                        Text(
                            text = when (state.status) {
                                "CONNECTED" -> state.serverStatus
                                    ?: "已连接"
                                "EXITED" -> "已退出${state.exitCode?.let { "（code=$it）" } ?: ""}"
                                else -> "未连接"
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(lines.takeLast(500).joinToString("\n")))
                    }) { Icon(Icons.Default.ContentCopy, "复制日志") }
                    IconButton(onClick = { vm.clearConsole() }) {
                        Icon(Icons.Default.Delete, "清屏")
                    }
                    IconButton(onClick = { vm.stopServer(force = false) }, enabled = running) {
                        Icon(Icons.Default.Stop, "停止", tint = MaterialTheme.colorScheme.error)
                    }
                    IconButton(onClick = { vm.stopServer(force = true) }, enabled = running) {
                        Icon(Icons.Default.Warning, "强制结束", tint = ConsoleRed)
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("输入服务器命令，如 list / say hi / stop") },
                        singleLine = true,
                        enabled = running,
                    )
                    IconButton(
                        onClick = {
                            val cmd = input.trim()
                            if (cmd.isNotEmpty()) {
                                ConsoleSession.sendCommand(cmd)
                                input = ""
                            }
                        },
                        enabled = running && input.isNotBlank(),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "发送") }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            if (!running && state.status == "EXITED") {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "服务器进程已结束",
                            Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        TextButton(onClick = { nav.popBackStack() }) { Text("返回") }
                    }
                }
            }
            // 同一 Wi-Fi 下电脑端 MC 客户端填这个地址即可进入（服务器没跑时不显示）
            lanAddress?.let { address ->
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "局域网地址（电脑端 MC 客户端填这个）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                address,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(address))
                        }) { Icon(Icons.Default.ContentCopy, "复制地址") }
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                items(lines) { line ->
                    Text(
                        text = line,
                        style = ConsoleTextStyle,
                        color = when {
                            line.contains("ERROR", true) || line.contains("FATAL", true) -> ConsoleRed
                            line.contains("WARN", true) -> Color(0xFFD29922)
                            line.startsWith("[MC服务器]") -> Color(0xFF58A6FF)
                            else -> Color(0xFFC9D1D9)
                        },
                    )
                }
            }
        }
    }
}
