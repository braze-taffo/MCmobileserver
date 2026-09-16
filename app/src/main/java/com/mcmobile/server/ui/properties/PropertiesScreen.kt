package com.mcmobile.server.ui.properties

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mcmobile.server.core.console.ConsoleSession
import com.mcmobile.server.core.props.ServerProperties
import com.mcmobile.server.data.ServerInstance
import com.mcmobile.server.ui.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PropertiesScreen(vm: AppViewModel, instance: ServerInstance, nav: NavController) {
    var props by remember { mutableStateOf<ServerProperties?>(null) }
    var edits by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var saved by remember { mutableStateOf(false) }

    val dir = vm.instanceDirOf(instance)

    LaunchedEffect(instance.id) {
        props = withContext(Dispatchers.IO) { ServerProperties.load(File(dir, "server.properties")) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("server.properties") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        },
    ) { padding ->
        val p = props
        if (p == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text("尚未生成 server.properties。")
                Text(
                    "服务器第一次启动时会自动生成；也可先写入常用默认值。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = {
                    val d = ServerProperties.default()
                    File(dir, "server.properties").writeText(d.toText())
                    props = d
                }, modifier = Modifier.padding(top = 12.dp)) { Text("写入默认配置") }
            }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(padding)) {
            if (ConsoleSession.state.value.serverStatus == "RUNNING") {
                Text(
                    "⚠ 服务器运行中，保存的改动将在下次重启后生效",
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(p.keys, key = { it }) { key ->
                    val value = edits[key] ?: p[key].orEmpty()
                    Card {
                        Column(Modifier.padding(12.dp)) {
                            Text(key, fontWeight = FontWeight.Medium,
                                style = MaterialTheme.typography.bodyMedium)
                            p.descriptions[key]?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedTextField(
                                value = value,
                                onValueChange = { edits = edits + (key to it) },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                singleLine = true,
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp)) {
                Button(
                    onClick = {
                        val np = ServerProperties.parse(p.toText())
                        edits.forEach { (k, v) -> np[k] = v }
                        File(dir, "server.properties").writeText(np.toText())
                        props = np
                        edits = emptyMap()
                        saved = true
                    },
                    enabled = edits.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text(if (saved) "已保存 ✓" else "保存") }
            }
        }
    }
}
