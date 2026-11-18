package com.mcmobile.server.ui.console

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mcmobile.server.core.console.ConsoleSession
import com.mcmobile.server.core.console.StartConflict
import com.mcmobile.server.ui.AppLinks
import com.mcmobile.server.ui.AppViewModel
import com.mcmobile.server.ui.theme.LogWarning
import com.mcmobile.server.ui.theme.MCServerTheme
import com.mcmobile.server.ui.theme.RunningIndicator
import com.mcmobile.server.ui.theme.ConsoleTextStyle

@Composable
fun ConsoleScreen(vm: AppViewModel, nav: NavController) {
    val lines by vm.consoleLines.collectAsState()
    val state by vm.consoleState.collectAsState()
    val lanAddress by vm.lanAddress.collectAsState()
    val context = LocalContext.current
    val running = state.status == "CONNECTED" && StartConflict.isActive(state.serverStatus)
    val status = when {
        state.status == "EXITED" -> "已退出" + (state.exitCode?.let { " · $it" } ?: "")
        state.status != "CONNECTED" -> "未连接"
        else -> when (state.serverStatus) {
            "RUNNING" -> "运行中"
            "STARTING" -> "启动中"
            "STOPPING" -> "停止中"
            "STOPPED" -> "已停止"
            "CRASHED", "FAILED" -> "运行失败"
            else -> "已连接"
        }
    }
    var menu by remember { mutableStateOf(false) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var input by rememberSaveable(state.instanceId) { mutableStateOf("") }
    var sendError by remember(state.instanceId) { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(lines, follow) {
        if (follow && lines.isNotEmpty()) list.scrollToItem(lines.lastIndex)
    }

    fun send() {
        val cmd = input.trim()
        if (!running || cmd.isEmpty()) return
        sendError = !ConsoleSession.sendCommand(cmd)
        if (!sendError) input = ""
    }

    MCServerTheme(darkTheme = true) {
        val colors = MaterialTheme.colorScheme
        Scaffold(containerColor = colors.background, contentColor = colors.onBackground) { insets ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding(),
                contentAlignment = Alignment.TopCenter) {
                val compact = maxWidth >= 520.dp && maxHeight < 420.dp
                Column(Modifier.widthIn(max = 840.dp).fillMaxSize().padding(horizontal = 14.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                        Column(if (compact) Modifier else Modifier.weight(1f)) {
                            Text("运行控制台",
                                style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold)
                            if (!compact && state.name.isNotBlank()) Text(state.name, color = colors.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (compact) {
                            Text(state.name, Modifier.weight(1f).padding(horizontal = 12.dp),
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(Modifier.size(7.dp).background(if (running) RunningIndicator else colors.onSurfaceVariant, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(status, style = MaterialTheme.typography.labelMedium)
                        if (compact) {
                            FollowLogsButton(follow) { follow = !follow }
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "控制台操作") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                val address = lanAddress
                                if (compact && address != null) {
                                    DropdownMenuItem(
                                        text = { Text("复制局域网地址\n$address") },
                                        onClick = {
                                            menu = false
                                            AppLinks.copy(context, address)
                                        },
                                    )
                                }
                                DropdownMenuItem(text = { Text("复制最近日志") }, enabled = lines.isNotEmpty(), onClick = {
                                    menu = false
                                    AppLinks.copy(context, lines.takeLast(500).joinToString("\n"))
                                })
                                DropdownMenuItem(text = { Text("清屏") }, enabled = lines.isNotEmpty(), onClick = {
                                    menu = false
                                    vm.clearConsole()
                                })
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("停止服务器") }, enabled = running, onClick = {
                                    menu = false
                                    vm.stopServer(force = false)
                                })
                                DropdownMenuItem(text = { Text("强制结束") }, enabled = running,
                                    colors = MenuDefaults.itemColors(textColor = colors.error), onClick = {
                                    menu = false
                                    vm.stopServer(force = true)
                                })
                            }
                        }
                    }
                    val failed = state.error != null || state.serverStatus in setOf("CRASHED", "FAILED") ||
                        (state.exitCode ?: 0) != 0
                    val notice = state.error ?: when {
                        failed -> "服务器进程异常结束"
                        state.status == "EXITED" -> "服务器进程已结束"
                        else -> null
                    }
                    if (notice != null) Text(notice, Modifier.padding(vertical = 6.dp),
                        color = if (failed) colors.error else colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    if (!compact) lanAddress?.let { address ->
                        Surface(color = colors.surfaceContainer, shape = RoundedCornerShape(12.dp)) {
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Lan, null, tint = colors.primary)
                                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                    Text("局域网地址", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                    Text(address, style = ConsoleTextStyle)
                                }
                                IconButton(onClick = { AppLinks.copy(context, address) }) { Icon(Icons.Default.ContentCopy, "复制地址") }
                            }
                        }
                    }
                    Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
                    Surface(modifier = Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(16.dp),
                        color = colors.surfaceContainerLowest, border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant)) {
                        Column {
                            if (!compact) {
                                Row(Modifier.fillMaxWidth().background(colors.surface).padding(start = 14.dp, end = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Terminal, null, Modifier.size(19.dp), tint = colors.primary)
                                    Spacer(Modifier.width(8.dp))
                                    Text("实时日志", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                    FollowLogsButton(follow) { follow = !follow }
                                }
                            }
                            if (lines.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(if (running) "等待日志输出…" else "暂无日志",
                                        style = ConsoleTextStyle, color = colors.onSurfaceVariant)
                                }
                            } else LazyColumn(Modifier.fillMaxSize(), state = list,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = if (compact) 6.dp else 12.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                items(lines.size) { index ->
                                    val line = lines[index]
                                    Text(line, style = ConsoleTextStyle, color = when {
                                        line.contains("ERROR", true) || line.contains("FATAL", true) -> colors.error
                                        line.contains("WARN", true) -> LogWarning
                                        line.startsWith("[MC服务器]") -> colors.primary
                                        else -> colors.onSurface
                                    })
                                }
                            }
                        }
                    }
                    if (sendError) Text("命令发送失败，请检查连接后重试", color = colors.error, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = input, onValueChange = {
                            input = it.filterNot { c -> c == '\r' || c == '\n' || c == '\u0000' }
                            sendError = false
                        }, modifier = Modifier.weight(1f), enabled = running, singleLine = true,
                            shape = RoundedCornerShape(12.dp), textStyle = ConsoleTextStyle,
                            placeholder = { Text(if (running) "输入服务器命令" else "连接后可输入命令") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { send() }))
                        FilledIconButton(onClick = ::send, enabled = running && input.isNotBlank(), modifier = Modifier.size(48.dp)) {
                            Icon(Icons.AutoMirrored.Filled.Send, "发送命令")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FollowLogsButton(follow: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(if (follow) "暂停跟随" else "跟随最新") }
}
