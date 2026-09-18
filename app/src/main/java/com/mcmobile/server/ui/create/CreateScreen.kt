package com.mcmobile.server.ui.create

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mcmobile.server.core.CoreInstaller
import com.mcmobile.server.core.CoreJarInspector
import com.mcmobile.server.core.McVersions
import com.mcmobile.server.core.storage.StorageLocation
import com.mcmobile.server.data.ServerInstance
import com.mcmobile.server.data.ServerType
import com.mcmobile.server.data.StorageKind
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

/** 合法的 MC 版本号形态（1.21.1 / 26.2 …） */
private val MC_VERSION_FORMAT = Regex("^\\d+(\\.\\d+)+$")

/** 文件名兜底用：要求至少一个点，挡掉 "server-64.jar" 这类把 build 号当版本的输入 */
private val FILENAME_VERSION = Regex("\\d+(?:\\.\\d+)+")

/** 从文件名猜 MC 版本（jar 内部读不出来时才用） */
private fun guessMcVersionFromName(fileName: String, type: ServerType?): String? {
    val raw = FILENAME_VERSION.find(fileName)?.value ?: return null
    if (type != ServerType.NEOFORGE && type != ServerType.FORGE) return raw
    // Forge/NeoForge 文件名里的数字既可能是 MC 版本（1.21.1）也可能是 loader 版本（21.1.57 / 26.2.0.88）
    return if (raw.startsWith("1.") && McVersions.isSupported(raw)) raw
    else McVersions.mcVersionOfNeoForge(raw) ?: raw
}

private fun kindLabel(kind: CoreJarInspector.Kind): String = when (kind) {
    CoreJarInspector.Kind.VANILLA -> "Vanilla 原版"
    CoreJarInspector.Kind.PAPER -> "Paper"
    CoreJarInspector.Kind.FOLIA -> "Folia"
    CoreJarInspector.Kind.FABRIC -> "Fabric"
    CoreJarInspector.Kind.FORGE -> "Forge"
    CoreJarInspector.Kind.NEOFORGE -> "NeoForge"
}

private fun kindMatchesType(kind: CoreJarInspector.Kind?, type: ServerType?): Boolean = when {
    kind == null || type == null -> true
    kind == CoreJarInspector.Kind.VANILLA -> type == ServerType.VANILLA
    kind == CoreJarInspector.Kind.PAPER -> type == ServerType.PAPER
    kind == CoreJarInspector.Kind.FOLIA -> type == ServerType.FOLIA
    kind == CoreJarInspector.Kind.FABRIC -> type == ServerType.FABRIC
    kind == CoreJarInspector.Kind.FORGE -> type == ServerType.FORGE
    else -> type == ServerType.NEOFORGE
}

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

    // 导入：已选文件与其识别结果
    var importFile by remember { mutableStateOf<File?>(null) }
    var importName by remember { mutableStateOf("") }
    var mcVersionInput by remember { mutableStateOf("") }
    var detectedJava by remember { mutableStateOf<Int?>(null) }
    var detectedCore by remember { mutableStateOf<String?>(null) }
    var typeHint by remember { mutableStateOf<String?>(null) }

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

    // 实例存放位置：默认应用私有存储，可选用户能直接访问的目录
    var storage by remember { mutableStateOf(StorageKind.INTERNAL) }
    var externalRoot by remember { mutableStateOf<File?>(null) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 记住这次授权，否则应用重启后系统会收回（有全盘权限时用不上，但没有它授权只在当次有效）
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        // 选择器给的是 content URI，JVM 需要真实路径；映射不出来（云盘等）就直接拒绝
        when (val picked = StorageLocation.resolvePicked(uri)) {
            is StorageLocation.Resolution.Ok -> {
                val problem = StorageLocation.checkWritable(picked.dir)
                if (problem == null) {
                    externalRoot = picked.dir
                } else {
                    externalRoot = null
                    toast(problem)
                }
            }

            is StorageLocation.Resolution.Rejected -> {
                externalRoot = null
                toast(picked.reason)
            }
        }
    }

    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) treeLauncher.launch(null)
        else toast("没有存储写权限，无法在共享存储里创建实例")
    }

    // 授权页不返回结果，回到应用后自己再查一次权限
    val allFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (StorageLocation.hasWriteAccess(context)) {
            treeLauncher.launch(null)
        } else {
            toast("没有「所有文件访问权限」，无法在共享存储里创建实例")
        }
    }

    fun pickDirectory() {
        when {
            StorageLocation.hasWriteAccess(context) -> treeLauncher.launch(null)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                allFilesLauncher.launch(StorageLocation.permissionSettingsIntent(context))

            else -> legacyPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    /** SAF 只给 content URI，真正的文件名要查 OpenableColumns；临时文件用唯一名，避免并发互相覆盖 */
    fun displayNameOf(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment?.substringAfterLast('/')

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            var tmp: File? = null
            try {
                val picked = displayNameOf(uri) ?: "imported.jar"
                tmp = File.createTempFile("import-core-", ".jar", context.cacheDir)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                }
                val detected = CoreJarInspector.detect(tmp)
                val t = type ?: ServerType.VANILLA
                val mc = detected?.mcVersion ?: guessMcVersionFromName(picked, t)

                importFile = tmp
                importName = picked
                mcVersionInput = mc ?: ""
                detectedJava = detected?.requiredJava
                detectedCore = detected?.coreVersion
                typeHint = detected?.kind?.takeIf { !kindMatchesType(it, t) }
                    ?.let { "注意：这个 jar 是 ${kindLabel(it)}，与所选类型 ${t.label} 不一致，请确认" }
                if (name.isBlank()) name = picked.removeSuffix(".jar")
                if (mc == null) toast("无法从 jar 或文件名识别 MC 版本，请手动填写")
            } catch (e: Exception) {
                tmp?.delete()
                toast("读取 jar 失败: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    val mcText = if (useImport) mcVersionInput.trim() else selected?.mcVersion.orEmpty()
    val formatOk = MC_VERSION_FORMAT.matches(mcText)
    val versionError = when {
        !useImport -> null
        mcText.isBlank() -> "未能识别 MC 版本，请手动填写（如 1.21.1 / 26.2）"
        !formatOk -> "版本号格式不对，应形如 1.21.1 / 26.2"
        !McVersions.isSupported(mcText) -> "内置运行时跑不了 MC $mcText（需要 Java ${McVersions.requiredJava(mcText)}）"
        else -> null
    }
    val importReady = !useImport || importFile != null
    val storageReady = storage == StorageKind.INTERNAL || externalRoot != null
    val canCreate =
        (if (useImport) importFile != null && versionError == null else selected != null) && storageReady

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
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (step) {
                0 -> TypeStep { t ->
                    type = t
                    useImport = false
                    selected = null
                    importFile = null
                    mcVersionInput = ""
                    detectedJava = null
                    detectedCore = null
                    typeHint = null
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
                            selected = null
                            importFile = null
                            mcVersionInput = ""
                            detectedJava = null
                            detectedCore = null
                            typeHint = null
                            name = ""
                            step = 2
                        },
                    )
                }

                2 -> ConfigStep(
                    typeLabel = type?.label ?: "",
                    selected = selected,
                    isImport = useImport,
                    importReady = importReady,
                    importedName = importName,
                    mcVersion = mcText,
                    onMcVersion = { mcVersionInput = it },
                    mcPreview = if (formatOk) {
                        McVersions.bundledJavaMajorFor(detectedJava ?: McVersions.requiredJava(mcText))
                    } else null,
                    versionError = versionError,
                    typeHint = typeHint,
                    name = name,
                    onName = { name = it },
                    heapMb = heapMb,
                    onHeap = { heapMb = it },
                    heapCap = heapCap,
                    storage = storage,
                    onStorage = { storage = it },
                    externalRoot = externalRoot,
                    onPickDirectory = { pickDirectory() },
                    busy = busy,
                    canCreate = canCreate,
                    // 只在本次操作进行中显示进度：installer.progress 是常驻状态，
                    // 直接展示会让新建的实例顶着上一个实例（甚至已删除实例）的"下载中"体积
                    progress = progress.takeIf { busy },
                    onPickFile = {
                        importLauncher.launch(
                            arrayOf("application/java-archive", "application/octet-stream"),
                        )
                    },
                    onCreate = {
                        busy = true
                        scope.launch {
                            var created: ServerInstance? = null
                            try {
                                val t = type ?: error("未选择核心类型")
                                val mc = if (useImport) mcText else (selected?.mcVersion ?: error("未选择 MC 版本"))
                                if (useImport && importFile == null) error("请先选择 jar 文件")
                                val inst = vm.createInstance(
                                    name = name.ifBlank {
                                        if (useImport) importName.removeSuffix(".jar") else "${t.label} $mc"
                                    },
                                    type = t,
                                    mcVersion = mc,
                                    coreVersion = if (useImport) detectedCore else selected?.coreVersion,
                                    heapMb = heapMb,
                                    requiredJava = if (useImport) detectedJava else null,
                                    storage = storage,
                                    externalRoot = externalRoot?.absolutePath,
                                )
                                created = inst
                                val needsInstaller = t == ServerType.FORGE || t == ServerType.NEOFORGE
                                if (useImport) {
                                    val source = importFile ?: error("请先选择 jar 文件")
                                    val copied = if (needsInstaller) {
                                        vm.installer.importInstaller(inst, source)
                                    } else {
                                        vm.installer.importJar(inst, source)
                                    }
                                    if (!copied) {
                                        val why = (vm.installer.progress.value as? CoreInstaller.Progress.Error)?.message
                                        error("导入失败：${why ?: "复制文件出错"}")
                                    }
                                } else {
                                    val ready = vm.installer.install(inst)
                                    // Forge/NeoForge 下载完安装器还要跑安装，返回值本就不代表"可用"
                                    if (!needsInstaller && !ready) {
                                        val why = (vm.installer.progress.value as? CoreInstaller.Progress.Error)?.message
                                        error("核心下载失败：${why ?: "未知错误"}")
                                    }
                                }
                                if (needsInstaller) vm.start(inst)
                                nav.popBackStack()
                            } catch (e: Exception) {
                                // 已经落盘的实例要回滚，否则首页会留下一个"看着建好了"的空壳
                                created?.let { vm.delete(it) }
                                toast("创建失败: ${e.message}")
                            } finally {
                                importFile?.delete()
                                importFile = null
                                busy = false
                            }
                        }
                    },
                )
            }
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
                                ServerType.FOLIA -> "区域化多线程，高并发（Paper 分支）"
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
    importReady: Boolean,
    importedName: String,
    mcVersion: String,
    onMcVersion: (String) -> Unit,
    mcPreview: Int?,
    versionError: String?,
    typeHint: String?,
    name: String,
    onName: (String) -> Unit,
    heapMb: Int,
    onHeap: (Int) -> Unit,
    heapCap: Int,
    storage: StorageKind,
    onStorage: (StorageKind) -> Unit,
    externalRoot: File?,
    onPickDirectory: () -> Unit,
    busy: Boolean,
    canCreate: Boolean,
    progress: CoreInstaller.Progress?,
    onPickFile: () -> Unit,
    onCreate: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "$typeLabel${if (isImport) "" else selected?.let { " · MC ${it.mcVersion}" } ?: ""}",
            style = MaterialTheme.typography.titleMedium,
        )
        if (!isImport && selected?.coreVersion != null) {
            Text("核心版本：${selected.coreVersion}", style = MaterialTheme.typography.bodyMedium)
        }

        if (isImport && importReady) {
            Text("来源：$importedName", style = MaterialTheme.typography.bodySmall)
        }

        if (isImport) {
            OutlinedTextField(
                value = mcVersion,
                onValueChange = onMcVersion,
                label = { Text("MC 版本") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = versionError != null,
                supportingText = {
                    Text(
                        versionError ?: mcPreview?.let { "将使用内置 Java $it" } ?: "",
                        color = if (versionError != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }

        if (typeHint != null) {
            Text(typeHint, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary)
        }

        OutlinedTextField(
            value = name,
            onValueChange = onName,
            label = { Text("实例名称") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Text("存储位置", style = MaterialTheme.typography.titleSmall)
        StorageOption(
            selected = storage == StorageKind.INTERNAL,
            title = StorageKind.INTERNAL.label,
            detail = "无需任何权限，读写最快；卸载应用时实例会一并删除",
            onClick = { onStorage(StorageKind.INTERNAL) },
        )
        StorageOption(
            selected = storage == StorageKind.EXTERNAL,
            title = StorageKind.EXTERNAL.label,
            detail = "文件管理器和电脑都能直接读写实例文件；需要「所有文件访问权限」，读写较慢",
            onClick = { onStorage(StorageKind.EXTERNAL) },
        )
        if (storage == StorageKind.EXTERNAL) {
            if (externalRoot == null) {
                OutlinedButton(onClick = onPickDirectory, modifier = Modifier.fillMaxWidth()) {
                    Text("选择存放文件夹")
                }
                Text(
                    "还没有选择文件夹，无法创建",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    "实例目录：${externalRoot.absolutePath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onPickDirectory) { Text("重新选择文件夹") }
            }
        }

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

        Spacer(Modifier.height(4.dp))

        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.height(24.dp))
                Text("  处理中…")
            }
        } else {
            if (isImport && !importReady) {
                OutlinedButton(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
                    Text("选择 jar 文件")
                }
            } else {
                Button(
                    onClick = onCreate,
                    enabled = canCreate,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (isImport) "创建并导入" else "创建并下载核心") }
            }
        }
    }
}

@Composable
private fun StorageOption(
    selected: Boolean,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

                ServerType.PAPER -> CoreApi.paperVersions()
                    .map { VersionItem(it, label = "Paper $it") }

                ServerType.FOLIA -> CoreApi.paperVersions("folia")
                    .map { VersionItem(it, label = "Folia $it") }

                ServerType.FABRIC -> CoreApi.fabricGameVersions()
                    .filter { McVersions.isRelease(it) && McVersions.isSupported(it) }
                    .map { VersionItem(it, label = "Fabric $it") }

                ServerType.NEOFORGE -> CoreApi.neoForgeVersions()
                    .asSequence()
                    .filter {
                        Regex("^\\d+\\.\\d+\\.\\d+").matches(it) ||
                            Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+(-beta)?$").matches(it)
                    }
                    // "21.1.219" ↔ MC 1.21.1；"26.2.0.88" ↔ MC 26.2（2026 日期式）
                    .mapNotNull { v -> McVersions.mcVersionOfNeoForge(v)?.let { it to v } }
                    .filter { (mc, _) -> McVersions.isSupported(mc) }
                    .groupBy({ it.first }, { it.second })
                    .map { (mc, vers) ->
                        val best = vers.lastOrNull { !it.endsWith("-beta") } ?: vers.last()
                        VersionItem(mc, coreVersion = best, label = "NeoForge · MC $mc")
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
