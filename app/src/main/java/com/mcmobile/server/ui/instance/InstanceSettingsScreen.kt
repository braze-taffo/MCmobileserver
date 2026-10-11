package com.mcmobile.server.ui.instance

import android.app.ActivityManager
import android.content.Context
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mcmobile.server.core.console.ConsoleSession
import com.mcmobile.server.core.console.StartConflict
import com.mcmobile.server.data.ServerInstance
import com.mcmobile.server.ui.AppViewModel

/**
 * 实例设置：创建之后仍可调整的启动级配置。
 * 当前只有最大内存（-Xmx）：只改实例清单，新值在下次启动时生效，
 * 正在运行的服务器不受影响、也不用停。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstanceSettingsScreen(vm: AppViewModel, instance: ServerInstance, nav: NavController) {
    val context = LocalContext.current
    val consoleState by ConsoleSession.state.collectAsState()
    // 保存后清单会更新；从流里重新取当前值，脏检查和界面才不会停留在旧对象上
    val instances by vm.instances.collectAsState()
    val current = instances.firstOrNull { it.id == instance.id } ?: instance

    val totalRamMb = remember {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        (mi.totalMem / (1024 * 1024)).toInt()
    }
    // 与创建页同一套上限：设备内存的 55%，按 256MB 取整
    val heapCap = remember { ((totalRamMb * 0.55).toInt() / 256) * 256 }
    // 滑条上界不得低于已存的值，否则 Slider 会因 value 超出 valueRange 崩溃
    val heapUpper = maxOf(heapCap, 512, current.maxHeapMb)

    var heapMb by remember(current.id) { mutableIntStateOf(current.maxHeapMb.coerceIn(256, heapUpper)) }
    var javaMajor by remember(current.id) { mutableIntStateOf(current.javaMajor) }
    var minHeap by remember(current.id) { mutableStateOf(current.minHeapMb.toString()) }
    var launchJar by remember(current.id) { mutableStateOf(current.launchJar.orEmpty()) }
    var argsFile by remember(current.id) { mutableStateOf(current.launchArgsFile.orEmpty()) }
    var mainClass by remember(current.id) { mutableStateOf(current.launchMainClass.orEmpty()) }
    var classpath by remember(current.id) { mutableStateOf(current.launchClasspath.joinToString("\n")) }
    var jvmArgs by remember(current.id) { mutableStateOf(com.mcmobile.server.core.launch.LaunchArgfiles.renderTokens(current.extraJvmArgs, "\n")) }
    var programArgs by remember(current.id) { mutableStateOf(com.mcmobile.server.core.launch.LaunchArgfiles.renderTokens(current.programArgs)) }
    var error by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var showUnsaved by remember { mutableStateOf(false) }

    val dirty = heapMb != current.maxHeapMb || javaMajor != current.javaMajor || minHeap != current.minHeapMb.toString() ||
        launchJar != current.launchJar.orEmpty() || argsFile != current.launchArgsFile.orEmpty() || mainClass != current.launchMainClass.orEmpty() ||
        classpath != current.launchClasspath.joinToString("\n") || runCatching { com.mcmobile.server.core.launch.LaunchArgfiles.tokenize(jvmArgs) }.getOrNull() != current.extraJvmArgs ||
        runCatching { com.mcmobile.server.core.launch.LaunchArgfiles.tokenize(programArgs) }.getOrNull() != current.programArgs
    val runningHere = consoleState.status == "CONNECTED" &&
            StartConflict.isActive(consoleState.serverStatus) &&
            consoleState.instanceId == current.id

    fun save(): Boolean {
        if (!dirty) return true
        try {
            val minimum = minHeap.toIntOrNull() ?: error("最小内存需为数字")
            require(minimum in 64..heapMb) { "最小内存不能大于最大内存，且至少为 64 MB" }
            val next = current.copy(maxHeapMb = heapMb, minHeapMb = minimum, javaMajor = javaMajor,
                launchJar = launchJar.trim().takeIf { it.isNotEmpty() }, launchArgsFile = argsFile.trim().takeIf { it.isNotEmpty() },
                launchMainClass = mainClass.trim().takeIf { it.isNotEmpty() }, launchClasspath = classpath.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList(),
                extraJvmArgs = com.mcmobile.server.core.launch.LaunchArgfiles.tokenize(jvmArgs), programArgs = com.mcmobile.server.core.launch.LaunchArgfiles.tokenize(programArgs))
            require(listOf(next.launchJar, next.launchArgsFile, next.launchMainClass).count { it != null } <= 1) { "JAR、参数文件和手动主类只能选一种启动方式" }
            require(next.extraJvmArgs.none { it in listOf("-jar", "-cp", "-classpath") }) { "请在启动入口中设置 JAR 或 classpath" }
            val result = com.mcmobile.server.core.launch.LaunchSpecBuilder.build(next, vm.instanceDirOf(current), com.mcmobile.server.core.jre.JreManager.javaHomeDir(context, javaMajor))
            require(result.spec != null) { result.error ?: "启动配置无效" }
            vm.updateInstance(next)
            saved = true; error = ""
            return true
        } catch (e: Exception) { error = e.message ?: "配置无效"; return false }
    }

    fun requestExit() {
        if (dirty) showUnsaved = true else nav.popBackStack()
    }

    BackHandler(enabled = dirty) { showUnsaved = true }

    if (showUnsaved) {
        AlertDialog(
            onDismissRequest = { showUnsaved = false },
            title = { Text("有未保存的修改") },
            text = { Text("退出将丢弃对实例设置的改动，或先保存再离开。") },
            confirmButton = {
                TextButton(onClick = {
                    if (save()) { showUnsaved = false; nav.popBackStack() }
                }) { Text("保存并退出") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUnsaved = false
                    nav.popBackStack()
                }) { Text("不保存") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("实例设置") },
                navigationIcon = {
                    IconButton(onClick = { requestExit() }) {
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(current.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${current.type.label} · MC ${current.mcVersion}" +
                                (current.coreVersion?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("内存", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "最大内存（-Xmx）限制服务器最多可用的 Java 堆。模组多、玩家多时需要更大内存；" +
                                "给太多会挤占系统和其他应用，可能导致整个应用被系统杀掉。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (runningHere) {
                        Text(
                            "⚠ 该服务器正在运行，新内存大小将在下次启动时生效",
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Text("最大内存：${heapMb}MB", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = heapMb.toFloat(),
                        onValueChange = { heapMb = ((it / 256).toInt() * 256).coerceAtLeast(256) },
                        valueRange = 256f..heapUpper.toFloat(),
                        // 上界来自已存值时可能不是 256 的倍数，松手时再吸附一次，避免存出非对齐值
                        onValueChangeFinished = {
                            heapMb = ((heapMb / 256f).toInt() * 256).coerceAtLeast(256)
                        },
                    )
                    Text(
                        "设备总内存 ${totalRamMb}MB · 建议不超过 ${heapCap.coerceAtLeast(512)}MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text("Java 与启动入口", style = MaterialTheme.typography.titleMedium)
            listOf(8, 17, 21, 25).chunked(2).forEach { group ->
                androidx.compose.foundation.layout.Row { group.forEach { major ->
                    androidx.compose.material3.FilterChip(javaMajor == major, { javaMajor = major }, label = { Text("Java $major") })
                } }
            }
            Text("Java 8/17 在首次启动时下载；此页设置在下次启动生效。", style = MaterialTheme.typography.bodySmall)
            androidx.compose.material3.OutlinedTextField(minHeap, { minHeap = it }, label = { Text("最小内存 MB") })
            androidx.compose.material3.OutlinedTextField(launchJar, { launchJar = it }, label = { Text("启动 JAR（相对实例目录；留空使用默认）") }, modifier = Modifier.fillMaxWidth())
            androidx.compose.material3.OutlinedTextField(argsFile, { argsFile = it }, label = { Text("启动参数文件（相对实例目录）") }, modifier = Modifier.fillMaxWidth())
            androidx.compose.material3.OutlinedTextField(mainClass, { mainClass = it }, label = { Text("高级：手动主类（与以上两项互斥）") }, modifier = Modifier.fillMaxWidth())
            if (mainClass.isNotBlank()) androidx.compose.material3.OutlinedTextField(classpath, { classpath = it }, label = { Text("Classpath，每行一个相对路径") }, modifier = Modifier.fillMaxWidth())
            androidx.compose.material3.OutlinedTextField(jvmArgs, { jvmArgs = it }, label = { Text("额外 JVM 参数（支持引号，可换行）") }, modifier = Modifier.fillMaxWidth())
            androidx.compose.material3.OutlinedTextField(programArgs, { programArgs = it }, label = { Text("服务端程序参数") }, modifier = Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(4.dp))

            Button(
                onClick = { save() },
                enabled = dirty,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (saved && !dirty) "已保存 ✓" else "保存") }
        }
    }
}
