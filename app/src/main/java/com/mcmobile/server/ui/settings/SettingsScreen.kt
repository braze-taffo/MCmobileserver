package com.mcmobile.server.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import com.mcmobile.server.ui.UpdateViewModel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mcmobile.server.core.jre.JreManager
import com.mcmobile.server.core.jre.JreStatus
import com.mcmobile.server.core.storage.StorageLocation
import com.mcmobile.server.data.StorageKind
import com.mcmobile.server.ui.AppViewModel
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, nav: NavController, updates: UpdateViewModel) {
    val context = LocalContext.current
    val jreState by JreManager.state.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val update by updates.state.collectAsState()
    val instances by vm.instances.collectAsState()
    val serverState by com.mcmobile.server.core.console.ConsoleSession.state.collectAsState()
    val serverActive = serverState.status == "CONNECTED" && com.mcmobile.server.core.console.StartConflict.isActive(serverState.serverStatus)

    // 授权页不返回结果，回到应用后重新查一次
    var hasWriteAccess by remember { mutableStateOf(StorageLocation.hasWriteAccess(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { hasWriteAccess = StorageLocation.hasWriteAccess(context) }

    LaunchedEffect(Unit) {
        com.mcmobile.server.core.console.ConsoleSession.activeServer(attempts = 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            OutlinedButton(onClick = { nav.navigate(com.mcmobile.server.ui.Routes.CUSTOM_APK) }, modifier = Modifier.fillMaxWidth()) {
                Text("自签名与自定义包名")
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("应用更新", style = MaterialTheme.typography.titleMedium)
                    Text("当前版本：${updates.currentVersion}")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("打开应用时自动检查")
                            Text("从 GitHub 获取正式发行版本", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = update.autoCheck, onCheckedChange = updates::setAutoCheck)
                    }
                    if (update.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                    update.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Button(enabled = !update.checking, onClick = { updates.check(manual = true) }) {
                        Text(if (update.checking) "正在检查…" else "检查更新")
                    }
                    if (update.release != null) TextButton(onClick = updates::showRelease) { Text("查看新版本") }
                }
            }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Java 运行时", style = MaterialTheme.typography.titleMedium)
                    Text("Java 8/17 按需下载，下载后可离线使用；21/25 随应用提供。修复运行时前请停服。", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "设备架构：${JreManager.supportedAbi() ?: "不支持"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    for (major in JreManager.AVAILABLE_MAJORS) {
                        val ready = JreManager.isReady(context, major)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Java $major",
                                Modifier.weight(1f),
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                if (ready) "已就绪" else if (major in listOf(8, 17)) "未下载" else "未解压",
                                color = if (ready) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Spacer(Modifier.height(0.dp))
                            Button(
                                // 解压中禁点：ensureExtracted 虽有互斥，连点仍会排队
                                // 触发一串无谓的重复解压
                                enabled = jreState.status != JreStatus.EXTRACTING && !serverActive,
                                onClick = {
                                    scope.launch {
                                        if (com.mcmobile.server.core.console.ConsoleSession.activeServer(attempts = 1) == null) {
                                            if (ready) JreManager.repair(context, major)
                                            else JreManager.ensureExtracted(context, major)
                                        } else android.widget.Toast.makeText(context, "请先停服再准备或修复运行时", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                },
                            ) { Text(if (ready) "修复" else if (major in listOf(8, 17)) "下载" else "解压") }
                        }
                    }
                    if (jreState.status == JreStatus.EXTRACTING) {
                        LinearProgressIndicator(
                            progress = { jreState.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "准备 Java ${jreState.javaMajor}：${(jreState.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    jreState.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    jreState.javaVersion?.let {
                        Text(if (jreState.status == JreStatus.EXTRACTING) it else "当前版本：OpenJDK $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("存储位置", style = MaterialTheme.typography.titleMedium)
                    Text("应用内部存储", fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodyMedium)
                    Text(
                        File(context.filesDir, "servers").absolutePath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val externalRoots = instances
                        .filter { it.storage == StorageKind.EXTERNAL }
                        .mapNotNull { it.externalRoot }
                        .distinct()
                    if (externalRoots.isEmpty()) {
                        Text(
                            "还没有实例使用外部目录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text("用户可访问目录", fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.bodyMedium)
                        for (root in externalRoots) {
                            Text(
                                root,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("所有文件访问权限", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (hasWriteAccess) "已授予"
                                else "未授予；只有放在外部目录的实例需要它",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (hasWriteAccess) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(onClick = {
                            permissionLauncher.launch(StorageLocation.permissionSettingsIntent(context))
                        }) { Text(if (hasWriteAccess) "查看" else "去授权") }
                    }
                }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("说明", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "· 应用内嵌 Android 原生编译的 OpenJDK（JIT），服务器以 ARM64 机器码直接运行\n" +
                                "· MC 1.17–1.21.x 使用 Java 21；MC 26.x 使用 Java 25\n" +
                                "· 建议设备内存 ≥4GB，运行时保持充电与稳定网络\n" +
                                "· 部分ROM的省电策略可能杀后台，请为本应用关闭电池优化",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("关于", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "MC Mobile Server ${updates.currentVersion}\n作者：${com.mcmobile.server.ui.AppLinks.AUTHOR}\n内嵌 OpenJDK 21/25（AngelAuraMC mobile 构建）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
