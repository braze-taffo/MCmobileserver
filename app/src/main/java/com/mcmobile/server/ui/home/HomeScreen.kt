package com.mcmobile.server.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mcmobile.server.data.ServerInstance
import com.mcmobile.server.ui.AppViewModel
import com.mcmobile.server.ui.Routes
import com.mcmobile.server.ui.UiEvent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel, nav: NavController) {
    val instances by vm.instances.collectAsState()
    val activeId by vm.activeInstanceId.collectAsState()
    val installingId by vm.installingId.collectAsState()
    var eulaInstance by remember { mutableStateOf<ServerInstance?>(null) }

    LaunchedEffect(Unit) {
        vm.events.collect { if (it is UiEvent.NeedEula) eulaInstance = it.instance }
    }

    eulaInstance?.let { inst ->
        AlertDialog(
            onDismissRequest = { eulaInstance = null },
            title = { Text("Mojang EULA") },
            text = {
                Text(
                    "运行 Minecraft 服务端需要同意 Mojang 的最终用户许可协议（EULA）。\n\n" +
                            "这允许在本设备上运行用于联机的服务端软件。\n\n是否同意？",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    eulaInstance = null
                    vm.acceptEula(inst)
                }) { Text("同意并启动") }
            },
            dismissButton = {
                TextButton(onClick = { eulaInstance = null }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.primary) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "MC 服务器",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Default.Settings, "设置",
                            tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate(Routes.CREATE) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("新建服务器") },
            )
        },
    ) { padding ->
        if (instances.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("还没有服务器实例", style = MaterialTheme.typography.titleMedium)
                    Text("点击\"新建服务器\"开始", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(instances, key = { it.id }) { inst ->
                    InstanceCard(
                        instance = inst,
                        isActive = activeId == inst.id,
                        installing = installingId == inst.id,
                        onStart = { vm.start(inst) },
                        onConsole = { nav.navigate(Routes.CONSOLE) },
                        onProperties = { nav.navigate(Routes.properties(inst.id)) },
                        onFiles = { nav.navigate(Routes.files(inst.id)) },
                        onDelete = { vm.delete(inst) },
                    )
                }
            }
        }
    }
}

@Composable
private fun InstanceCard(
    instance: ServerInstance,
    isActive: Boolean,
    installing: Boolean,
    onStart: () -> Unit,
    onConsole: () -> Unit,
    onProperties: () -> Unit,
    onFiles: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(instance.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${instance.type.label} · MC ${instance.mcVersion}" +
                                (instance.coreVersion?.let { " · $it" } ?: "") +
                                " · Java ${instance.javaMajor} · ${instance.maxHeapMb}MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (installing) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("安装中", style = MaterialTheme.typography.labelMedium)
                } else if (isActive) {
                    Surface(color = Color(0xFF2E7D32), shape = CircleShape) {
                        Text("运行中", Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White)
                    }
                } else if (instance.needsInstaller && !instance.installed) {
                    Text("待安装", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row {
                if (isActive) {
                    IconButton(onClick = onConsole) {
                        Icon(Icons.Default.Terminal, "控制台")
                    }
                } else {
                    IconButton(onClick = onStart) {
                        Icon(Icons.Default.PlayArrow, "启动",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
                IconButton(onClick = onFiles) {
                    Icon(Icons.Default.Folder, "文件",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onProperties) {
                    Icon(Icons.Default.Tune, "配置",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "删除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
