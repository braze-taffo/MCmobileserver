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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mcmobile.server.core.jre.JreManager
import com.mcmobile.server.core.jre.JreStatus
import com.mcmobile.server.ui.AppViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, nav: NavController) {
    val context = LocalContext.current
    val jreState by JreManager.state.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // 展示两个运行时的就绪状态（不主动解压 25，按需在使用时解压）
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
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("内嵌 Java 运行时", style = MaterialTheme.typography.titleMedium)
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
                                if (ready) "已就绪" else "未解压",
                                color = if (ready) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Spacer(Modifier.height(0.dp))
                            Button(onClick = {
                                scope.launch { JreManager.ensureExtracted(context, major) }
                            }) { Text(if (ready) "重新解压" else "解压") }
                        }
                    }
                    if (jreState.status == JreStatus.EXTRACTING) {
                        LinearProgressIndicator(
                            progress = { jreState.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "解压中 ${(jreState.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    jreState.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    jreState.javaVersion?.let {
                        Text("当前版本：OpenJDK $it", style = MaterialTheme.typography.bodySmall)
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
                        "MC Mobile Server 1.0.0\n内嵌 OpenJDK 21/25（AngelAuraMC mobile 构建）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
