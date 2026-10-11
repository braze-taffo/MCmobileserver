package com.mcmobile.server.ui.manage

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mcmobile.server.ui.AppViewModel
import com.mcmobile.server.data.ServerInstance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Stable
class PanelState(private val scope: kotlinx.coroutines.CoroutineScope) {
    var busy by mutableStateOf(false)
    var message by mutableStateOf("")
    var progress by mutableStateOf("")
    var revision by mutableIntStateOf(0)
    fun work(block: suspend () -> String) {
        if (busy) return
        busy = true; message = ""; progress = ""
        scope.launch {
            try { message = withContext(Dispatchers.IO) { block() }; revision++ }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = "操作失败：${e.message ?: e.javaClass.simpleName}" }
            finally { busy = false }
        }
    }
}

@Composable
fun rememberPanelState(): PanelState {
    val scope = rememberCoroutineScope()
    return remember(scope) { PanelState(scope) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Panel(title: String, nav: NavController, state: PanelState,
          content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = {
        IconButton(enabled = !state.busy, onClick = { nav.popBackStack() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
        }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.progress.ifBlank { "正在处理…" }, style = MaterialTheme.typography.bodySmall)
            }
            if (state.message.isNotBlank()) {
                Card(Modifier.fillMaxWidth()) { SelectionText(state.message, Modifier.padding(12.dp)) }
                TextButton(onClick = { state.message = "" }) { Text("关闭提示") }
            }
            content()
        }
    }
}

@Composable
fun SelectionText(text: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.text.selection.SelectionContainer { Text(text, modifier) }
}

@Composable
fun Confirm(title: String, text: String, close: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(title) },
        text = { SelectionText(text) }, confirmButton = { TextButton(onClick = { close(); confirm() }) { Text("确认") } },
        dismissButton = { TextButton(onClick = close) { Text("取消") } })
}

suspend fun requireStopped(vm: AppViewModel, instance: ServerInstance) {
    val running = com.mcmobile.server.core.console.ConsoleSession.activeServer(attempts = 1)
    require(!vm.isRunning(instance.id) && running?.instanceId != instance.id) { "请先停止该服务器，再执行此操作" }
}
