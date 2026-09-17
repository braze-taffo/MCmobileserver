package com.mcmobile.server.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mcmobile.server.R
import com.mcmobile.server.core.update.UpdateChecker
import com.mcmobile.server.ui.AppLinks
import com.mcmobile.server.ui.AppViewModel
import com.mcmobile.server.ui.Routes
import com.mcmobile.server.ui.UpdateViewModel

@Composable
fun DashboardScreen(vm: AppViewModel, updates: UpdateViewModel, nav: NavController) {
    val instances by vm.instances.collectAsState()
    val console by vm.consoleState.collectAsState()
    val update by updates.state.collectAsState()
    val context = LocalContext.current
    val running = console.status == "CONNECTED" && console.serverStatus in setOf("STARTING", "RUNNING", "STOPPING", "INSTALLING")
    val colors = MaterialTheme.colorScheme

    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(
            Brush.verticalGradient(listOf(colors.surfaceVariant.copy(alpha = 0.5f), colors.background)),
        ), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("MC MOBILE SERVER", style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Surface(shape = RoundedCornerShape(50), color = colors.surface) {
                        Text("v${updates.currentVersion}", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Image(painterResource(R.drawable.app_brand), "MC 服务器图标",
                        Modifier.size(92.dp).clip(RoundedCornerShape(24.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("方块世界\n随时开启", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("在手机上，搭建你的 Java 版服务器", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }

                Card(onClick = { nav.navigate(Routes.SERVERS) }, shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.primary, contentColor = colors.onPrimary)) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Dns, null, Modifier.size(30.dp))
                            Spacer(Modifier.weight(1f))
                            Surface(color = colors.onPrimary.copy(alpha = 0.15f), shape = RoundedCornerShape(50)) {
                                Text(if (running) "服务器已连接" else "准备就绪", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    color = colors.onPrimary, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Text("服务器管理", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(if (instances.isEmpty()) "创建第一个世界，邀请朋友一起加入" else "${instances.size} 个实例 · 启动、配置与文件管理",
                            style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("进入管理", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                        }
                    }
                }

                Text("开服工具", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FeatureTile("内网穿透", "让朋友从外网加入", Icons.Default.Public, Modifier.weight(1f)) { nav.navigate(Routes.FRP) }
                    FeatureTile("运行控制台", if (running) "查看日志与发送指令" else "尚未连接 · 查看控制台", Icons.Default.Terminal, Modifier.weight(1f)) { nav.navigate(Routes.CONSOLE) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FeatureTile("应用设置", "运行环境与更新偏好", Icons.Default.Settings, Modifier.weight(1f)) { nav.navigate(Routes.SETTINGS) }
                    FeatureTile("开源项目", "查看源码与发行版本", Icons.Default.Code, Modifier.weight(1f)) { AppLinks.open(context, UpdateChecker.REPOSITORY) }
                }

                update.release?.let { release ->
                    Card(onClick = updates::showRelease, colors = CardDefaults.cardColors(containerColor = colors.tertiaryContainer)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.SystemUpdate, null)
                            Column(Modifier.weight(1f)) {
                                Text("新版本 ${release.version} 已发布", fontWeight = FontWeight.SemiBold)
                                Text("查看更新内容", style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                        }
                    }
                }

                Text("作者与交流", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Card(shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Surface(color = colors.secondaryContainer, shape = RoundedCornerShape(14.dp)) {
                                Icon(Icons.Default.Person, null, Modifier.padding(12.dp), tint = colors.onSecondaryContainer)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(AppLinks.AUTHOR, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("开发与维护", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                        }
                        OutlinedButton(onClick = { AppLinks.open(context, AppLinks.BILIBILI) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.SmartDisplay, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("访问作者 Bilibili")
                        }
                        HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("QQ 交流群", fontWeight = FontWeight.SemiBold)
                                Text(AppLinks.GROUP, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            }
                            IconButton(onClick = { AppLinks.copy(context, AppLinks.GROUP) }) { Icon(Icons.Default.ContentCopy, "复制群号") }
                        }
                        Button(onClick = { AppLinks.joinGroup(context) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Groups, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("打开 QQ · 加入交流群")
                        }
                    }
                }
                Text("MC 服务器 · 让联机更简单", modifier = Modifier.align(Alignment.CenterHorizontally),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FeatureTile(title: String, subtitle: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
