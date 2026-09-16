package com.mcmobile.server.ui.create

import android.app.ActivityManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mcmobile.server.core.CoreInstaller
import com.mcmobile.server.core.McVersions
import com.mcmobile.server.data.ServerInstance
import com.mcmobile.server.data.ServerType
import com.mcmobile.server.data.api.CoreApi
import com.mcmobile.server.ui.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class VersionListState(
    val loading: Boolean = false,
    val items: List<VersionItem> = emptyList(),
    val error: String? = null,
)

private data class VersionItem(
    val mcVersion: String,
    /** neoforge/forge 的核心版本 */
    val coreVersion: String? = null,
    val label: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen(vm: AppViewModel, nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableIntStateOf(0) }
    var type by remember { mutableStateOf<ServerType?>(null) }
    var useImport by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<VersionItem?>(null) }
    var name by remember { mutableStateOf("") }
    val totalRamMb = remember {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        (mi.totalMem / (1024 * 1024)).toInt()
    }
    var heapMb by remember { mutableIntStateOf(((totalRamMb * 0.3).toInt() / 256) * 256) }
    val heapCap = remember { ((totalRamMb * 0.55).toInt() / 256) * 256 }

    val progress by vm.installer.progress.collectAsState()
    var busy by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val tmp = File(context.cacheDir, "import-core.jar")
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                }
                val t = type ?: ServerType.VANILLA
                // 从文件名猜版本
                val guessed = Regex("(\\d+[\\.\\d+]*)").findAll(tmp.name)
                    .map { it.value }.firstOrNull()
                val mc = guessed?.let {
                    // 归一：paper-1.21.1-133.jar → 1.21.1；neoforge-21.1.57 → 1.21.1
                    if (t == ServerType.NEOFORGE || t == ServerType.FORGE) {
                        val parts = it.split('.')
                        if (parts.size >= 2 && parts[0].toIntOrNull() ?: 0 >= 20) "1.${parts[0]}.${parts[1]}" else it
                    } else it
                } ?: "1.21.1"
                val inst = vm.createInstance(
                    name = name.ifBlank { tmp.name.removeSuffix(".jar") },
                    type = t,
                    mcVersion = mc,
                    coreVersion = null,
                    heapMb = heapMb,
                )
                val ok = if (t == ServerType.FORGE || t == ServerType.NEOFORGE) {
                    vm.installer.importInstaller(inst, tmp)
                } else {
                    vm.installer.importJar(inst, tmp)
                }
                if (t == ServerType.FORGE || t == ServerType.NEOFORGE) {
                    // 导入的安装器：直接触发安装流程
                    vm.start(inst)
                }
                nav.popBackStack()
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "导入失败: ${e.message}",
                    android.widget.Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (step == 0) "选择核心类型" else if (step == 1) "选择版本" else "配置实例") },
                navigationIcon = {
                    TextButton(onClick = { if (step > 0) step-- else nav.popBackStack() }) {
                        Text("返回")
                    }
                },
            )
        },
    ) { padding ->
        when (step) {
            0 -> TypeStep { t ->
                type = t
                useImport = false
                step = 1
            }

            1 -> {
                val versions = produceVersionList(type)
                VersionStep(
                    state = versions,
                    onPick = {
                        selected = it
                        name = "${type!!.label} ${it.mcVersion}"
                        step = 2
                    },
                    onImport = {
                        useImport = true
                        name = ""
                        step = 2
                    },
                )
            }

            2 -> ConfigStep(
                typeLabel = type!!.label,
                selected = selected,
                isImport = useImport,
                name = name,
                onName = { name = it },
                heapMb = heapMb,
                onHeap = { heapMb = it },
                heapCap = heapCap,
                busy = busy,
                progress = progress,
                onImport = { importLauncher.launch(arrayOf("application/java-archive", "application/octet-stream")) },
                onCreate = {
                    busy = true
                    scope.launch {
                        try {
                            val inst = vm.createInstance(
                                name = name,
                                type = type!!,
                                mcVersion = selected?.mcVersion ?: "1.21.1",
                                coreVersion = selected?.coreVersion,
                                heapMb = heapMb,
                            )
                            vm.installer.install(inst)
                            if (type!! == ServerType.FORGE || type!! == ServerType.NEOFORGE) {
                                vm.start(inst) // 触发安装器
                            }
                            nav.popBackStack()
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(
                                context, "创建失败: ${e.message}", android.widget.Toast.LENGTH_LONG,
                            ).show()
                        } finally {
                            busy = false
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun TypeStep(onPick: (ServerType) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(ServerType.entries.toList()) { t ->
            Card(onClick = { onPick(t) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (t) {
                                ServerType.VANILLA -> "Mojang 官方服务端"
                                ServerType.PAPER -> "高性能，支持插件"
                                ServerType.FABRIC -> "轻量模组加载器"
                                ServerType.FORGE -> "老牌模组加载器（1.18+）"
                                ServerType.NEOFORGE -> "新一代模组加载器（1.20.1+）"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionStep(
    state: VersionListState,
    onPick: (VersionItem) -> Unit,
    onImport: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        OutlinedButton(
            onClick = onImport,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text("导入已下载的 jar 文件") }

        when {
            state.loading -> Row(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.padding(4.dp))
                Text("加载版本列表…", Modifier.padding(top = 10.dp, start = 12.dp))
            }

            state.error != null -> Column(Modifier.padding(24.dp)) {
                Text("加载失败：${state.error}", color = MaterialTheme.colorScheme.error)
            }

            else -> LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp, vertical = 4.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.items, key = { it.mcVersion }) { v ->
                    Card(onClick = { onPick(v) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = false, onClick = { onPick(v) })
                            Column {
                                Text(v.label, fontWeight = FontWeight.Medium)
                                if (v.coreVersion != null) {
                                    Text(
                                        v.coreVersion!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigStep(
    typeLabel: String,
    selected: VersionItem?,
    isImport: Boolean,
    name: String,
    onName: (String) -> Unit,
    heapMb: Int,
    onHeap: (Int) -> Unit,
    heapCap: Int,
    busy: Boolean,
    progress: CoreInstaller.Progress,
    onImport: () -> Unit,
    onCreate: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("$typeLabel${selected?.let { " · MC ${it.mcVersion}" } ?: ""}",
            style = MaterialTheme.typography.titleMedium)
        if (selected != null && selected.coreVersion != null) {
            Text("核心版本：${selected.coreVersion}",
                style = MaterialTheme.typography.bodyMedium)
        }

        OutlinedTextField(
            value = name,
            onValueChange = onName,
            label = { Text("实例名称") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Text("最大内存：${heapMb}MB", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = heapMb.toFloat(),
            onValueChange = { onHeap(((it / 256).toInt() * 256).coerceAtLeast(256)) },
            valueRange = 256f..heapCap.coerceAtLeast(512).toFloat(),
        )

        when (val p = progress) {
            is CoreInstaller.Progress.Downloading -> {
                Text("下载中… ${p.done / 1048576}/${if (p.total > 0) "${p.total / 1048576}MB" else "?"}")
                if (p.total > 0) LinearProgressIndicator(
                    progress = { p.done.toFloat() / p.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is CoreInstaller.Progress.Error -> Text(
                "出错：${p.message}",
                color = MaterialTheme.colorScheme.error,
            )

            else -> Unit
        }

        Spacer(Modifier.weight(1f))

        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.height(24.dp))
                Text("  处理中…")
            }
        } else {
            Button(
                onClick = if (isImport) onImport else onCreate,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (isImport) "选择 jar 文件并导入" else "创建并下载核心") }
        }
    }
}

@Composable
private fun produceVersionList(type: ServerType?): VersionListState =
    produceState(
        initialValue = VersionListState(loading = true),
        key1 = type,
    ) {
        if (type == null) {
            value = VersionListState()
            return@produceState
        }
        value = try {
            val items = when (type) {
                ServerType.VANILLA -> CoreApi.vanillaVersions()
                    .map { it.id }
                    .filter { McVersions.isSupported(it) }
                    .map { VersionItem(it, label = "Minecraft $it") }

                ServerType.PAPER -> CoreApi.paperFamilies()
                    .filter { McVersions.isSupported(it) }
                    .map { VersionItem(it, label = "Paper $it") }

                ServerType.FABRIC -> CoreApi.fabricGameVersions()
                    .filter { McVersions.isRelease(it) && McVersions.isSupported(it) }
                    .map { VersionItem(it, label = "Fabric $it") }

                ServerType.NEOFORGE -> {
                    val all = CoreApi.neoForgeVersions()
                    all.asSequence()
                        .filter { Regex("^\\d+\\.\\d+\\.\\d+").matches(it) || Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+(-beta)?$").matches(it) }
                        .mapNotNull { v ->
                            val short = v.substringBefore(".").let { major ->
                                v.substringAfter("$major.").substringBefore(".")
                            }
                            // "21.1" ↔ MC 1.21.1；"26.2" ↔ MC 26.2（2026 日期式）
                            val major = v.substringBefore('.').toIntOrNull() ?: return@mapNotNull null
                            val minor = short.toIntOrNull() ?: return@mapNotNull null
                            val mc = if (major >= 25) "$major.$minor" else "1.$major.$minor"
                            mc to v
                        }
                        .filter { (mc, _) -> McVersions.isSupported(mc) }
                        .groupBy({ it.first }, { it.second })
                        .map { (mc, vers) ->
                            val best = vers.lastOrNull { !it.endsWith("-beta") } ?: vers.last()
                            VersionItem(mc, coreVersion = best, label = "NeoForge · MC $mc")
                        }
                }

                ServerType.FORGE -> CoreApi.forgePromos()
                    .filterKeys { McVersions.isSupported(it) && McVersions.compare(it, "1.18") >= 0 }
                    .map { (mc, pair) ->
                        val v = pair.first.ifBlank { pair.second }
                        VersionItem(mc, coreVersion = v, label = "Forge · MC $mc（$v）")
                    }
            }.sortedWith { a, b -> McVersions.compare(b.mcVersion, a.mcVersion) }
            VersionListState(items = items)
        } catch (e: Exception) {
            VersionListState(error = e.message ?: e.toString())
        }
    }.value
