package com.mcmobile.server.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mcmobile.server.R
import com.mcmobile.server.core.console.ConsoleSession
import com.mcmobile.server.core.console.StartConflict
import com.mcmobile.server.core.update.UpdateChecker
import com.mcmobile.server.data.ServerInstance
import com.mcmobile.server.ui.AppLinks
import com.mcmobile.server.ui.AppViewModel
import com.mcmobile.server.ui.Routes
import com.mcmobile.server.ui.UpdateViewModel
import com.mcmobile.server.ui.theme.BurntOrange
import com.mcmobile.server.ui.theme.OnBurntOrange
import com.mcmobile.server.ui.theme.RunningIndicator

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(vm: AppViewModel, updates: UpdateViewModel, nav: NavController) {
    val instances by vm.instances.collectAsState()
    val activeId by vm.activeInstanceId.collectAsState()
    val console by vm.consoleState.collectAsState()
    val deleting by vm.deletingId.collectAsState()
    val installing by vm.installingId.collectAsState()
    val error by vm.loadError.collectAsState()
    val update by updates.state.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(Unit) { ConsoleSession.activeServer(attempts = 1) }
    val running = console.status == "CONNECTED" && StartConflict.isActive(console.serverStatus)
    val currentId = if (running) console.instanceId ?: activeId else activeId
    val featured = remember(instances, currentId) {
        instances.find { it.id == currentId } ?: instances.maxByOrNull { it.lastStartedMs ?: it.createdMs }
    }
    val others = remember(instances, featured) { instances.filter { it.id != featured?.id }.take(2) }
    var editingCoverId by rememberSaveable { mutableStateOf<String?>(null) }
    var coverRevision by rememberSaveable { mutableIntStateOf(0) }
    fun open(route: String) { nav.navigate(route) { launchSingleTop = true } }

    val colors = MaterialTheme.colorScheme
    Scaffold(containerColor = colors.background, contentColor = colors.onBackground) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 700.dp).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.app_brand), null,
                            Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
                        Spacer(Modifier.width(10.dp))
                        Text("MC Mobile Server", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        IconButton(onClick = { open(Routes.SETTINGS) }) { Icon(Icons.Default.Settings, "应用设置") }
                    }
                }
                item {
                    Column {
                        Text("作者 · ${AppLinks.AUTHOR}", style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { AppLinks.open(context, AppLinks.BILIBILI) }) { Text("B 站主页 ↗") }
                            TextButton(onClick = { AppLinks.joinGroup(context) }) { Text("QQ 群 ${AppLinks.GROUP} ↗") }
                            IconButton(onClick = { AppLinks.copy(context, AppLinks.GROUP) }) {
                                Icon(Icons.Default.ContentCopy, "复制群号", tint = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("我的服务器", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold)
                        FilledTonalButton(onClick = { open(Routes.CREATE) }, colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer)) {
                            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                            Text("新建")
                        }
                    }
                }
                error?.let { message -> item { Text(message, color = colors.error) } }
                item {
                    val instance = featured
                    val active = instance != null && instance.id == currentId
                    val status = when {
                        instance == null -> ""
                        instance.id == deleting -> "删除中"
                        instance.needsInstaller && instance.id == installing -> "安装中"
                        active && running -> when (console.serverStatus) {
                            "STOPPING" -> "停止中"
                            "RUNNING" -> "运行中"
                            else -> "启动中"
                        }
                        active -> "启动中"
                        instance.needsInstaller && !instance.installed -> "待安装"
                        else -> "已停止"
                    }
                    Surface(shape = RoundedCornerShape(22.dp), color = BurntOrange, contentColor = OnBurntOrange) {
                        Column {
                            if (instance != null) {
                                Box(Modifier.fillMaxWidth().heightIn(max = 210.dp).aspectRatio(2.2f)) {
                                    InstanceCover(vm.instanceDirOf(instance), instance,
                                        Modifier.matchParentSize()
                                            .clickable(onClickLabel = "管理实例") { open(Routes.manage(instance.id)) },
                                        featured = true, revision = coverRevision)
                                    FilledTonalButton(onClick = { editingCoverId = instance.id },
                                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                                        Text("换封面")
                                    }
                                }
                            }
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (instance != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(instance.name, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.size(8.dp).background(if (active) RunningIndicator else OnBurntOrange.copy(alpha = 0.72f), CircleShape))
                                            Spacer(Modifier.width(6.dp))
                                            Text(status, style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                    Text("${instance.type.label} · ${instance.mcVersion} · Java ${instance.javaMajor}",
                                        color = OnBurntOrange.copy(alpha = 0.92f), style = MaterialTheme.typography.bodySmall)
                                    Spacer(Modifier.height(3.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Button(onClick = { if (active) open(Routes.CONSOLE) else vm.start(instance) },
                                            enabled = deleting != instance.id && (active || (currentId == null && !running)),
                                            colors = ButtonDefaults.buttonColors(containerColor = OnBurntOrange, contentColor = BurntOrange)) {
                                            Icon(if (active) Icons.Default.Terminal else Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(if (active) "打开控制台" else "启动服务器")
                                        }
                                        TextButton(onClick = { open(Routes.manage(instance.id)) }) { Text("管理", color = OnBurntOrange) }
                                    }
                                } else {
                                    Text(if (error == null) "还没有服务器" else "实例列表暂不可用",
                                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                    if (error == null) Text("新建服务器，或从整包导入已有的世界。",
                                        style = MaterialTheme.typography.bodyMedium, color = OnBurntOrange.copy(alpha = 0.92f))
                                }
                            }
                        }
                    }
                }
                if (instances.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (others.isEmpty()) "实例管理" else "其他实例", Modifier.weight(1f),
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            TextButton(onClick = { open(Routes.SERVERS) }) { Text("全部实例 · ${instances.size}", color = colors.primary) }
                        }
                        others.forEach { instance ->
                            Surface(onClick = { open(Routes.manage(instance.id)) }, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                shape = RoundedCornerShape(14.dp), color = colors.surfaceContainer, contentColor = colors.onSurface) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    InstanceCover(vm.instanceDirOf(instance), instance,
                                        Modifier.size(44.dp).clip(RoundedCornerShape(9.dp)), revision = coverRevision)
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                        Text(instance.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${instance.type.label} · ${instance.mcVersion}", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(when {
                                        instance.id == deleting -> "删除中"
                                        instance.needsInstaller && !instance.installed -> "待安装"
                                        else -> "已停止"
                                    }, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                                }
                            }
                        }
                    }
                }
                item {
                    Text("开服工具", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        Tool("内网穿透", Icons.Default.Hub) { open(Routes.FRP) }
                        Tool("动态域名", Icons.Default.Language) { open(Routes.DDNS) }
                        Tool("导入整包", Icons.Default.MoveToInbox) { open(Routes.PACK_IMPORT) }
                    }
                }
                item {
                    HorizontalDivider(color = colors.outlineVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { open(Routes.CONSOLE) }) { Text("运行控制台", color = colors.onSurfaceVariant) }
                        TextButton(onClick = { AppLinks.open(context, UpdateChecker.REPOSITORY) }) {
                            Text("开源项目 · v${updates.currentVersion}", color = colors.onSurfaceVariant)
                        }
                    }
                    update.release?.let { release ->
                        TextButton(onClick = updates::showRelease) { Text("查看新版本 ${release.version}", color = colors.primary) }
                    }
                }
            }
        }
    }
    instances.find { it.id == editingCoverId }?.let { instance ->
        CoverPicker(vm.instanceDirOf(instance), onClose = { editingCoverId = null }, onChanged = {
            coverRevision++
            editingCoverId = null
        })
    }
}

@Composable
private fun RowScope.Tool(label: String, icon: ImageVector, action: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(onClick = action).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(48.dp).background(colors.primaryContainer, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(25.dp), tint = colors.onPrimaryContainer)
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
