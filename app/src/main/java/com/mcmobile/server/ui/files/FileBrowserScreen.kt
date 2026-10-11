package com.mcmobile.server.ui.files

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import android.provider.OpenableColumns
import com.mcmobile.server.core.files.ManagedFiles
import com.mcmobile.server.data.ServerInstance
import com.mcmobile.server.ui.AppViewModel
import com.mcmobile.server.ui.manage.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(vm: AppViewModel, instance: ServerInstance, nav: NavController, initial: String = "") {
    val context = LocalContext.current
    val root = vm.instanceDirOf(instance)
    val ops = remember(root) { ManagedFiles(root) }
    val state = rememberPanelState()
    var cwd by remember(instance.id) { mutableStateOf(File(root, initial).takeIf { it.isDirectory } ?: root) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var recursive by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<Set<File>>(emptySet()) }
    var clipboard by remember { mutableStateOf<List<File>>(emptyList()) }
    var moving by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf(ManagedFiles.Conflict.SKIP) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var value by remember { mutableStateOf("") }
    var editor by remember { mutableStateOf<File?>(null) }
    var text by remember { mutableStateOf("") }
    var original by remember { mutableStateOf("") }
    var modified by remember { mutableLongStateOf(0) }
    var size by remember { mutableLongStateOf(0) }
    var unsaved by remember { mutableStateOf(false) }
    var extractFile by remember { mutableStateOf<File?>(null) }
    var exportSource by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(cwd, query, recursive, sort, state.revision) {
        selected = emptySet()
        try {
            files = withContext(Dispatchers.IO) {
                ops.checked(cwd, true)
                val candidates = if (recursive && query.isNotBlank()) cwd.walkTopDown().onEnter {
                    ops.checked(it, true); !it.name.startsWith(".mcs-")
                }.filter { it != cwd }.take(50_000).toList() else cwd.listFiles().orEmpty().toList()
                candidates.filter { !it.name.startsWith(".mcs-write-") && it.name != ".mcs-instance.json" && it.name.contains(query, true) }
                    .sortedWith(compareBy<File> { !it.isDirectory }.thenComparator { a, b ->
                        when (sort) { 1 -> b.lastModified().compareTo(a.lastModified()); 2 -> b.length().compareTo(a.length()); else -> a.name.lowercase().compareTo(b.name.lowercase()) }
                    })
            }
        } catch (e: Exception) { state.message = "读取失败：${e.message}"; files = emptyList() }
    }
    suspend fun guardMutation(targets: List<File>) {
        if (vm.isRunning(instance.id)) {
            val level = com.mcmobile.server.core.manage.Backups.worldName(root)
            val worlds = com.mcmobile.server.core.manage.Backups.worlds(root).map { it.name }.toSet() +
                setOf(level, "${level}_nether", "${level}_the_end")
            targets.forEach { file ->
                val path = file.relativeTo(root).invariantSeparatorsPath
                val editable = file.isFile && file.extension.lowercase() in setOf("txt", "properties", "json", "toml", "yaml", "yml", "cfg", "conf", "ini", "xml") &&
                    path.substringBefore('/') !in worlds + setOf("libraries", "mods", "plugins")
                require(editable) { "请先停服再修改、替换或移动核心、模组和世界文件" }
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val destination = cwd
            val policy = conflict
            state.work {
                var written = 0; var skipped = 0
                uris.forEach { uri ->
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: error("无法读取文件名")
                    val target = ops.child(destination, name)
                    guardMutation(listOf(target))
                    context.contentResolver.openInputStream(uri)?.use { if (ops.write(target, it, policy)) written++ else skipped++ }
                        ?: error("无法读取 $name")
                }
                "已导入 $written 个文件，跳过 $skipped 个同名文件"
            }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val source = exportSource
        if (uri != null && source != null) state.work {
            context.contentResolver.openOutputStream(uri, "wt")?.use { output -> source.inputStream().use { it.copyTo(output) } }
                ?: error("无法写入导出文件")
            if (source.parentFile == context.cacheDir) source.delete()
            "已导出 ${source.name}"
        }
    }
    fun goBack() {
        if (editor != null) { if (text != original) unsaved = true else editor = null }
        else if (cwd != root) { cwd = cwd.parentFile ?: root; query = "" }
        else nav.popBackStack()
    }
    BackHandler { if (!state.busy) goBack() }
    if (unsaved) Confirm("放弃未保存的修改？", "当前编辑内容尚未保存。", { unsaved = false }) { editor = null }
    if (dialog != null) {
        val action = dialog!!
        AlertDialog(onDismissRequest = { dialog = null }, title = { Text(action) }, text = {
            Column {
                if (action == "删除") Text("将删除 ${selected.size} 项及其目录内容，此操作无法撤销。")
                else OutlinedTextField(value, { value = it }, label = { Text(if (action == "解压 ZIP") "目标子目录名称（留空为当前目录）" else "名称") })
                if (action == "解压 ZIP") Text("同名策略：${conflict.name}；会保留压缩包内目录结构。")
            }
        }, confirmButton = { TextButton(onClick = {
            val name = value; val picks = selected.toList(); val archive = extractFile; dialog = null
            state.work {
                val job = kotlin.coroutines.coroutineContext
                val report: (ManagedFiles.Progress) -> Unit = { p -> job.ensureActive(); state.progress = "${p.files} 项 · ${p.bytes / 1048576} MB · ${p.path}" }
                when (action) {
                    "新建文件", "新建文件夹" -> { val target = ops.child(cwd, name); guardMutation(listOf(target)); ops.create(cwd, name, action == "新建文件夹") }
                    "重命名" -> { requireStopped(vm, instance); ops.rename(picks.single(), name) }
                    "删除" -> { requireStopped(vm, instance); picks.forEach(ops::delete) }
                    "压缩 ZIP" -> { require(name.endsWith(".zip", true)) { "文件名应以 .zip 结尾" }; val target = ops.child(cwd, name); guardMutation(listOf(target)); require(!target.exists()) { "已有同名文件" }; ops.compress(picks, target, report) }
                    "解压 ZIP" -> { requireStopped(vm, instance); val target = if (name.isBlank()) cwd else ops.child(cwd, name).apply { mkdirs() }; ops.extract(archive!!, target, conflict, report) }
                }
                "操作完成"
            }
        }) { Text("确认") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } })
    }
    Scaffold(topBar = { TopAppBar(title = { Text(if (editor != null) editor!!.name else "${instance.name} · 文件") }, navigationIcon = {
        IconButton(enabled = !state.busy, onClick = ::goBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(state.progress.ifBlank { "正在处理…" }) }
            if (state.message.isNotBlank()) SelectionText(state.message)
            if (editor != null) {
                Text("UTF-8 文本；运行中修改配置后，请按对应配置要求重载或重启。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it }, Modifier.weight(1f).fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                Row {
                    Button(enabled = !state.busy && text != original, onClick = {
                        val file = editor!!; val content = text
                        state.work { guardMutation(listOf(file)); ops.saveText(file, content, modified, size); original = content; modified = file.lastModified(); size = file.length(); "保存成功；生效方式由该配置所属服务端或模组决定" }
                    }) { Text("保存") }
                    TextButton(enabled = !state.busy, onClick = { val file = editor!!; state.work { text = ops.readText(file); original = text; modified = file.lastModified(); size = file.length(); "已重新加载" } }) { Text("重新加载") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { cwd = root; query = "" }) { Text("根目录") }
                    Text(cwd.relativeTo(root).invariantSeparatorsPath.ifEmpty { "/" }, Modifier.weight(1f))
                    TextButton(onClick = { state.revision++ }) { Text("刷新") }
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索文件名") }, singleLine = true)
                Row {
                    FilterChip(recursive, { recursive = !recursive }, label = { Text("搜索子目录") })
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { sort = (sort + 1) % 3 }) { Text("排序：${listOf("名称", "修改时间", "大小")[sort]}") }
                }
                Row {
                    TextButton(enabled = !state.busy, onClick = { importer.launch(arrayOf("*/*")) }) { Text("导入") }
                    TextButton(enabled = !state.busy, onClick = { value = ""; dialog = "新建文件夹" }) { Text("新建目录") }
                    TextButton(enabled = !state.busy, onClick = { value = ""; dialog = "新建文件" }) { Text("新建文件") }
                    TextButton(onClick = { conflict = ManagedFiles.Conflict.entries[(conflict.ordinal + 1) % 3] }) { Text(listOf("同名跳过", "同名覆盖", "同名保留两份")[conflict.ordinal]) }
                }
                if (clipboard.isNotEmpty()) Row {
                    Text("${if (moving) "移动" else "复制"} ${clipboard.size} 项", Modifier.weight(1f))
                    Button(enabled = !state.busy, onClick = { val picks = clipboard; val move = moving; val destination = cwd; val policy = conflict
                        state.work { requireStopped(vm, instance); val job = kotlin.coroutines.coroutineContext; val result = ops.transfer(picks, destination, move, policy) { job.ensureActive(); state.progress = it.path }; clipboard = emptyList(); "完成 ${result.written} 项，跳过 ${result.skipped} 项" }
                    }) { Text("粘贴到此处") }
                    TextButton(onClick = { clipboard = emptyList() }) { Text("取消") }
                }
                if (selected.isNotEmpty()) {
                    Row {
                        TextButton(onClick = { selected = if (selected.size == files.size) emptySet() else files.toSet() }) { Text("全选 / 清空") }
                        TextButton(onClick = { clipboard = selected.toList(); moving = false; selected = emptySet() }) { Text("复制") }
                        TextButton(onClick = { clipboard = selected.toList(); moving = true; selected = emptySet() }) { Text("移动") }
                        TextButton(onClick = { dialog = "删除" }) { Text("删除") }
                    }
                    Row {
                        TextButton(enabled = selected.size == 1, onClick = { value = selected.single().name; dialog = "重命名" }) { Text("重命名") }
                        TextButton(onClick = { value = "files.zip"; dialog = "压缩 ZIP" }) { Text("压缩") }
                        TextButton(enabled = !state.busy, onClick = {
                            val picks = selected.toList()
                            if (picks.size == 1 && picks.single().isFile) { exportSource = picks.single(); exporter.launch(picks.single().name) }
                            else state.work { val out = File.createTempFile("export-", ".zip", context.cacheDir); ops.compress(picks, out) { state.progress = it.path }; withContext(Dispatchers.Main) { exportSource = out; exporter.launch("${instance.name}-files.zip") }; "请选择导出位置" }
                        }) { Text("导出") }
                    }
                }
                LazyColumn(Modifier.weight(1f)) {
                    if (files.isEmpty()) item { Text("这个目录没有匹配的文件", Modifier.padding(16.dp)) }
                    items(files, key = { it.absolutePath }) { file ->
                        ListItem(headlineContent = { Text(file.name) }, supportingContent = { Text(if (recursive && query.isNotEmpty()) file.relativeTo(root).path else if (file.isDirectory) "文件夹" else "${file.length() / 1024} KB") },
                            leadingContent = { Checkbox(selected.contains(file), { checked -> selected = if (checked) selected + file else selected - file }, enabled = !state.busy) },
                            trailingContent = { if (file.extension.equals("zip", true)) TextButton(enabled = !state.busy, onClick = { extractFile = file; value = file.nameWithoutExtension; dialog = "解压 ZIP" }) { Text("解压") } },
                            modifier = Modifier.clickable(enabled = !state.busy) {
                                if (file.isDirectory) { cwd = file; query = "" }
                                else state.work { text = ops.readText(file); original = text; modified = file.lastModified(); size = file.length(); editor = file; "" }
                            })
                    }
                }
            }
        }
    }
}
