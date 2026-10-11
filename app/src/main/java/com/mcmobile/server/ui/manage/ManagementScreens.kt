package com.mcmobile.server.ui.manage

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.mcmobile.server.core.console.ConsoleSession
import com.mcmobile.server.core.manage.*
import com.mcmobile.server.core.files.InstanceFiles
import com.mcmobile.server.core.files.ManagedFiles
import com.mcmobile.server.core.props.ServerProperties
import com.mcmobile.server.data.ServerInstance
import com.mcmobile.server.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

@Composable
fun ServerOverviewScreen(vm: AppViewModel, instance: ServerInstance, nav: NavController) {
    val instances by vm.instances.collectAsState()
    val current = instances.firstOrNull { it.id == instance.id } ?: instance
    val console by vm.consoleState.collectAsState()
    val active by vm.activeInstanceId.collectAsState()
    val lan by vm.lanAddress.collectAsState()
    val state = rememberPanelState()
    val running = vm.isRunning(current.id)
    val metrics = console.takeIf { it.instanceId == current.id && it.status == "CONNECTED" }
    var disk by remember { mutableStateOf("") }
    var confirmStop by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var moveTarget by remember { mutableStateOf<Pair<com.mcmobile.server.data.StorageKind, File>?>(null) }
    val movePicker = rememberDirectoryPicker({ moveTarget = com.mcmobile.server.data.StorageKind.EXTERNAL to it }, { state.message = it })
    LaunchedEffect(current.id) {
        ConsoleSession.activeServer(attempts = 1)
        while (true) {
            disk = withContext(Dispatchers.IO) { val dir = vm.instanceDirOf(current); "所在存储剩余 ${dir.usableSpace / 1048576} MB" }
            delay(5000)
        }
    }
    if (confirmStop) Confirm("停止服务器？", "服务器会保存世界后退出。", { confirmStop = false }) { vm.stopServer(false) }
    moveTarget?.let { (storage, parent) -> Confirm("迁移服务器目录？",
        "将完整复制到 ${File(parent, current.dirName).absolutePath}，更新实例位置后移除原目录。请先停服；备份仍保留在原备份位置。",
        { moveTarget = null }) {
        state.work { requireStopped(vm, current); val job = kotlin.coroutines.coroutineContext
            val removed = vm.relocate(current, storage, parent) { job.ensureActive(); state.progress = "${it.bytes / 1048576} MB · ${it.path}" }
            if (removed) "已迁移服务器目录" else "已切换到新目录；原目录未能完全删除，请自行清理"
        }
    } }
    Panel(current.name, nav, state) {
        Text("${current.type.label} · Minecraft ${current.mcVersion} · Java ${current.javaMajor}", style = MaterialTheme.typography.titleMedium)
        Text(if (running) when (metrics?.serverStatus) { "STARTING" -> "正在准备运行时 / 启动"; "STOPPING" -> "正在保存并停止"; else -> "运行中" } else "已停止")
        SelectionText(vm.instanceDirOf(current).absolutePath)
        Row {
            Button(enabled = !running && active == null, onClick = { vm.start(current) }) { Text("启动") }
            TextButton(enabled = running, onClick = { confirmStop = true }) { Text("停止") }
            TextButton(enabled = running, onClick = {
                state.work { require(vm.isRunning(current.id)); check(vm.controller.stopAndWait(60_000, forceOnTimeout = false)) { "停止超时，请在控制台检查" }; withContext(Dispatchers.Main) { vm.start(vm.instanceById(current.id) ?: current) }; "已请求重启，启动进度见控制台" }
            }) { Text("重启") }
            TextButton(enabled = running, onClick = { nav.navigate(Routes.CONSOLE) }) { Text("控制台") }
        }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(androidx.compose.ui.unit.Dp(16f))) {
            Text("运行监控", style = MaterialTheme.typography.titleMedium)
            Text("进程内存 PSS：${metrics?.memoryKb?.let { "${it / 1024} MB" } ?: "未获取"}")
            Text("进程 CPU（单核为 100%）：${metrics?.cpuPercent?.let { "%.1f%%".format(it) } ?: "未获取"}")
            Text("本次运行：${metrics?.uptimeMs?.let { "${it / 60000} 分 ${it / 1000 % 60} 秒" } ?: "未获取"}")
            Text("Java 堆设置：${current.minHeapMb}–${current.maxHeapMb} MB")
            Text(disk)
            if (running && lan != null) SelectionText("局域网地址：$lan")
        } }
        val links = listOf("文件管理" to Routes.files(current.id), "玩家与权限" to Routes.players(current.id),
            "模组与插件" to Routes.addons(current.id), "世界与备份" to Routes.worlds(current.id),
            "启动设置与 Java" to Routes.instance(current.id), "服务器配置" to Routes.properties(current.id),
            "日志与错误诊断" to Routes.logs(current.id), "定时任务" to Routes.tasks(current.id))
        links.forEach { (title, route) -> OutlinedButton(onClick = { nav.navigate(route) }, modifier = Modifier.fillMaxWidth()) { Text(title) } }
        Text("迁移存放位置", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(enabled = !state.busy && !running, onClick = movePicker, modifier = Modifier.fillMaxWidth()) { Text("迁移到共享目录") }
        if (current.storage != com.mcmobile.server.data.StorageKind.INTERNAL) OutlinedButton(enabled = !state.busy && !running,
            onClick = { moveTarget = com.mcmobile.server.data.StorageKind.INTERNAL to File(context.filesDir, "servers") }, modifier = Modifier.fillMaxWidth()) { Text("迁移到应用内部") }
        metrics?.error?.let { SelectionText("服务器错误：$it") }
    }
}

@Composable
fun PlayersScreen(vm: AppViewModel, instance: ServerInstance, nav: NavController) {
    val root = vm.instanceDirOf(instance)
    val state = rememberPanelState()
    val console by vm.consoleState.collectAsState()
    val lines by vm.consoleLines.collectAsState()
    var players by remember { mutableStateOf<List<Players.Player>>(emptyList()) }
    var filter by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var uuid by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var selectedAction by remember { mutableStateOf<String?>(null) }
    var onlineResult by remember { mutableStateOf("尚未查询在线玩家") }
    LaunchedEffect(state.revision) { try { players = withContext(Dispatchers.IO) { Players.read(root) } } catch (e: Exception) { state.message = "玩家列表读取失败：${e.message}" } }
    LaunchedEffect(instance.id, console.instanceId) {
        if (vm.isRunning(instance.id)) ConsoleSession.lines.collect { line ->
            if (Regex("There are \\d+ of a max of \\d+ players online|players online:", RegexOption.IGNORE_CASE).containsMatchIn(line)) onlineResult = line
        }
    }
    selectedAction?.let { action -> Confirm("确认玩家操作", "${action} ${name}\n${if (vm.isRunning(instance.id)) "通过服务端命令执行，结果见日志。" else "修改玩家列表文件，下次开服生效。"}", { selectedAction = null }) {
        state.work {
            if (vm.isRunning(instance.id)) {
                require(ConsoleSession.state.value.instanceId == instance.id)
                check(ConsoleSession.sendCommand(Players.command(action, name, reason))) { "命令发送失败" }
                delay(500)
                "命令已发送，请核对服务端日志中的执行结果"
            } else { requireStopped(vm, instance); Players.offline(root, action, name, uuid, reason); "玩家列表已保存，下次启动生效" }
        }
    } }
    Panel("玩家与权限", nav, state) {
        Row {
            Button(enabled = !state.busy && vm.isRunning(instance.id), onClick = { state.work { require(ConsoleSession.state.value.instanceId == instance.id); check(ConsoleSession.sendCommand("list")) { "无法发送查询" }; "在线列表查询已发送" } }) { Text("查询在线玩家") }
            TextButton(onClick = { state.revision++ }) { Text("刷新权限列表") }
        }
        SelectionText(onlineResult)
        OutlinedTextField(filter, { filter = it }, label = { Text("搜索玩家") }, modifier = Modifier.fillMaxWidth())
        players.filter { it.name.contains(filter, true) }.forEach { player ->
            OutlinedButton(onClick = { name = player.name; uuid = player.uuid ?: "" }, modifier = Modifier.fillMaxWidth()) { Column {
                Text(player.name + " · " + player.lists.joinToString(" / "))
                player.uuid?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            } }
        }
        OutlinedTextField(name, { name = it }, label = { Text("玩家名称") }, singleLine = true)
        OutlinedTextField(uuid, { uuid = it }, label = { Text("UUID（停服时添加正版玩家需要）") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(reason, { reason = it }, label = { Text("封禁 / 踢出原因（可选）") }, modifier = Modifier.fillMaxWidth())
        listOf(listOf("op" to "设为 OP", "deop" to "取消 OP"), listOf("whitelist add" to "加入白名单", "whitelist remove" to "移出白名单"), listOf("ban" to "封禁", "pardon" to "解封", "kick" to "踢出")).forEach { group -> Row { group.forEach { (action, title) ->
            TextButton(enabled = !state.busy && name.isNotBlank() && (action != "kick" || vm.isRunning(instance.id)), onClick = { selectedAction = action }) { Text(title) }
        } } }
        Row {
            TextButton(enabled = vm.isRunning(instance.id), onClick = { state.work { check(ConsoleSession.sendCommand("whitelist on")); "已请求启用白名单，结果见日志" } }) { Text("启用白名单") }
            TextButton(enabled = vm.isRunning(instance.id), onClick = { state.work { check(ConsoleSession.sendCommand("whitelist off")); "已请求关闭白名单，结果见日志" } }) { Text("关闭白名单") }
        }
        SelectionText(lines.takeIf { console.instanceId == instance.id }?.takeLast(8)?.joinToString("\n") ?: "")
    }
}

@Composable
fun WorldsScreen(vm: AppViewModel, instance: ServerInstance, nav: NavController) {
    val context = LocalContext.current
    val root = vm.instanceDirOf(instance)
    val directory = vm.backupsDir(instance)
    val state = rememberPanelState()
    var worlds by remember { mutableStateOf<List<File>>(emptyList()) }
    var backups by remember { mutableStateOf<List<File>>(emptyList()) }
    var level by remember { mutableStateOf("") }
    var pendingWorld by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Backups.Preview?>(null) }
    var exportFile by remember { mutableStateOf<File?>(null) }
    DisposableEffect(Unit) { onDispose { if (!state.busy) preview?.staging?.deleteRecursively() } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val source = exportFile
        if (uri != null && source != null) state.work { context.contentResolver.openOutputStream(uri, "wt")?.use { output -> source.inputStream().use { it.copyTo(output) } } ?: error("导出失败"); "已导出备份" }
    }
    val importWorld = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) state.work {
            requireStopped(vm, instance)
            val archive = File.createTempFile(".world-", ".zip", root)
            try { context.contentResolver.openInputStream(uri)?.use { input -> archive.outputStream().use { input.copyTo(it) } } ?: error("无法读取存档")
                val name = InstanceFiles(root).importWorldZip(archive, "world-import-${System.currentTimeMillis()}")
                pendingWorld = name; "已导入 $name，可选择切换到这个世界"
            } finally { archive.delete() }
        }
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) state.work {
            requireStopped(vm, instance)
            directory.mkdirs()
            val archive = File(directory, "import-${System.currentTimeMillis()}.zip")
            try { context.contentResolver.openInputStream(uri)?.use { input -> archive.outputStream().use { input.copyTo(it) } } ?: error("无法读取备份")
                preview?.staging?.deleteRecursively(); preview = Backups.preview(root, archive) { state.progress = it.path }; "备份读取完成，请核对恢复范围"
            } catch (t: Throwable) { archive.delete(); throw t }
        }
    }
    LaunchedEffect(state.revision) { try { withContext(Dispatchers.IO) { worlds = Backups.worlds(root); level = Backups.worldName(root); backups = directory.listFiles().orEmpty().filter { it.extension == "zip" }.sortedByDescending { it.lastModified() } } } catch (e: Exception) { state.message = "读取失败：${e.message}" } }
    DisposableEffect(Unit) { onDispose { if (!state.busy) preview?.staging?.deleteRecursively() } }
    pendingWorld?.let { name -> Confirm("切换世界？", "将 level-name 改为 $name。原世界目录会保留，下次启动使用新世界。", { pendingWorld = null }) {
        state.work { requireStopped(vm, instance); val file = File(root, "server.properties"); val props = ServerProperties.load(file) ?: ServerProperties.default(); props["level-name"] = name; props.toText().byteInputStream().use { ManagedFiles(root).write(file, it) }; "已选择世界 $name" }
    } }
    if (preview != null) {
        val selected = preview!!
        AlertDialog(onDismissRequest = { selected.staging.deleteRecursively(); preview = null }, title = { Text("确认恢复范围") },
            text = { SelectionText("${if (selected.info.kind == "server") "整体替换服务端目录，备份中未包含的当前文件会移除" else "替换选中的世界并切换 level-name"}\n${selected.replacements.joinToString("\n")}") },
            confirmButton = { TextButton(onClick = { preview = null; state.work { try { requireStopped(vm, instance); Backups.restore(root, selected); "恢复完成，请启动并检查服务端" } finally { selected.staging.deleteRecursively() } } }) { Text("恢复") } },
            dismissButton = { TextButton(onClick = { selected.staging.deleteRecursively(); preview = null }) { Text("取消") } })
    }
    Panel("世界与备份", nav, state) {
        Text("当前世界：$level")
        worlds.forEach { world -> OutlinedButton(enabled = !state.busy && world.name != level, onClick = { pendingWorld = world.name }) { Text("切换到 ${world.name}") } }
        OutlinedButton(enabled = !state.busy, onClick = { importWorld.launch(arrayOf("*/*")) }) { Text("导入世界 ZIP") }
        Row { listOf(false to "备份世界", true to "备份整服").forEach { (full, label) ->
            Button(enabled = !state.busy, onClick = { state.work { val job = kotlin.coroutines.coroutineContext; val archive = LiveBackup.create(instance, root, directory, full) { job.ensureActive(); state.progress = "${it.files} 项 · ${it.bytes / 1048576} MB · ${it.path}" }; "备份完成：${archive.absolutePath}" } }) { Text(label) }
        } }
        Text("运行中备份会先请求保存并暂停自动保存，完成后恢复自动保存；恢复和切换世界需要先停服。", style = MaterialTheme.typography.bodySmall)
        SelectionText("备份位置：${directory.absolutePath}")
        OutlinedButton(enabled = !state.busy, onClick = { importBackup.launch(arrayOf("*/*")) }) { Text("导入 ZIP 备份并预览恢复") }
        backups.forEach { archive -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(androidx.compose.ui.unit.Dp(12f))) {
            Text(archive.name); Text("${archive.length() / 1048576} MB")
            Row {
                TextButton(enabled = !state.busy, onClick = { exportFile = archive; export.launch(archive.name) }) { Text("导出") }
                TextButton(enabled = !state.busy, onClick = { state.work { requireStopped(vm, instance); preview?.staging?.deleteRecursively(); preview = Backups.preview(root, archive) { state.progress = it.path }; "请核对恢复范围" } }) { Text("恢复预览") }
            }
        } } }
    }
}

@Composable
fun TasksScreen(vm: AppViewModel, instance: ServerInstance, nav: NavController) {
    val store = remember { vm.taskStore() }
    val state = rememberPanelState()
    var tasks by remember { mutableStateOf<List<ServerTask>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var action by remember { mutableStateOf("BACKUP_WORLD") }
    var interval by remember { mutableStateOf("30") }
    var command by remember { mutableStateOf("") }
    var editedId by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<ServerTask?>(null) }
    val labels = mapOf("BACKUP_WORLD" to "备份世界", "BACKUP_FULL" to "备份整服", "RESTART" to "重启", "COMMAND" to "执行命令")
    LaunchedEffect(state.revision) { try { tasks = withContext(Dispatchers.IO) { store.load().filter { it.instanceId == instance.id } } } catch (e: Exception) { state.message = "任务读取失败：${e.message}" } }
    deleting?.let { task -> Confirm("删除任务？", task.name, { deleting = null }) { state.work { store.save(store.load().filterNot { it.id == task.id }); "任务已删除" } } }
    Panel("定时任务", nav, state) {
        Text("只在本服务器运行期间执行。每次开始运行后重新计时；停止期间不执行、不补跑。")
        tasks.forEach { task -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(androidx.compose.ui.unit.Dp(12f))) {
            Text("${task.name} · ${labels[task.action]} · 每 ${task.intervalMinutes} 分钟")
            Row {
                Switch(task.enabled, { enabled -> state.work { store.save(store.load().map { if (it.id == task.id) it.copy(enabled = enabled) else it }); "任务状态已保存" } }, enabled = !state.busy)
                TextButton(onClick = { editedId = task.id; name = task.name; action = task.action; interval = task.intervalMinutes.toString(); command = task.command }) { Text("编辑") }
                TextButton(onClick = { deleting = task }) { Text("删除") }
            }
        } } }
        Text(if (editedId == null) "新建任务" else "编辑任务", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(name, { name = it }, label = { Text("任务名称") })
        TaskStore.ACTIONS.chunked(2).forEach { group -> Row { group.forEach { kind -> FilterChip(action == kind, { action = kind }, label = { Text(labels[kind]!!) }) } } }
        OutlinedTextField(interval, { interval = it }, label = { Text("间隔（分钟）") }, singleLine = true)
        if (action == "COMMAND") OutlinedTextField(command, { command = it }, label = { Text("单行控制台命令（不带 /）") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = !state.busy, onClick = {
            val task = ServerTask(editedId ?: UUID.randomUUID().toString(), instance.id, name.trim(), action, interval.toIntOrNull() ?: 0, command.trim().removePrefix("/"))
            state.work { TaskStore.validate(task); store.save(store.load().filterNot { it.id == task.id } + task); editedId = null; name = ""; "任务已保存，执行记录位于 logs/management.log" }
        }) { Text("保存任务") }
        if (editedId != null) TextButton(onClick = { editedId = null; name = "" }) { Text("取消编辑") }
        TextButton(onClick = { nav.navigate(Routes.logs(instance.id)) }) { Text("查看任务执行记录") }
    }
}

@Composable
fun LogsScreen(vm: AppViewModel, instance: ServerInstance, nav: NavController) {
    val context = LocalContext.current
    val root = vm.instanceDirOf(instance)
    val state = rememberPanelState()
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var selected by remember { mutableStateOf<File?>(null) }
    var content by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var errorsOnly by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(true) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val file = selected
        if (uri != null && file != null) state.work { context.contentResolver.openOutputStream(uri, "wt")?.use { output -> file.inputStream().use { it.copyTo(output) } } ?: error("导出失败"); "已导出完整日志" }
    }
    LaunchedEffect(instance.id, state.revision) {
        try { files = withContext(Dispatchers.IO) {
            listOf(File(root, "logs"), File(root, "crash-reports")).flatMap { dir -> dir.listFiles().orEmpty().filter { it.isFile && it.extension in setOf("log", "txt") } }.sortedByDescending { it.lastModified() }
        }; if (selected == null) selected = files.firstOrNull() } catch (e: Exception) { state.message = e.message ?: "读取日志失败" }
    }
    LaunchedEffect(selected, auto, state.revision) {
        do {
            try { content = withContext(Dispatchers.IO) {
                selected?.let { file ->
                    ManagedFiles(root).checked(file)
                    RandomAccessFile(file, "r").use { input ->
                        val truncated = input.length() > 2L * 1048576
                        input.seek((input.length() - 2L * 1048576).coerceAtLeast(0))
                        val bytes = ByteArray((input.length() - input.filePointer).toInt()); input.readFully(bytes)
                        (if (truncated) "[仅显示最近 2 MB，导出可获取完整日志]\n" else "") + bytes.toString(Charsets.UTF_8)
                    }
                } ?: "没有日志文件"
            } } catch (e: Exception) { content = "读取失败：${e.message}" }
            if (auto) delay(3000)
        } while (auto)
    }
    Panel("日志与错误诊断", nav, state) {
        Row {
            FilterChip(auto, { auto = !auto }, label = { Text("自动刷新") })
            FilterChip(errorsOnly, { errorsOnly = !errorsOnly }, label = { Text("仅错误 / 警告") })
            TextButton(onClick = { state.revision++ }) { Text("刷新") }
        }
        OutlinedTextField(query, { query = it }, label = { Text("搜索日志内容") }, modifier = Modifier.fillMaxWidth())
        files.forEach { file -> TextButton(onClick = { selected = file }) { Text((if (file == selected) "● " else "") + file.parentFile!!.name + "/" + file.name) } }
        OutlinedButton(enabled = selected != null && !state.busy, onClick = { export.launch(selected!!.name) }) { Text("导出完整日志") }
        val hints = buildList {
            if (content.contains("UnsupportedClassVersionError")) add("Java 版本与核心或模组要求不匹配，请在启动设置中检查运行时。")
            if (content.contains("OutOfMemoryError")) add("内存不足，请核对最大堆设置、模组数量和设备剩余内存。")
            if (content.contains("Address already in use", true)) add("端口已被占用，请检查另一实例或其他程序，以及 server-port。")
            if (content.contains("ClassNotFoundException") || content.contains("NoClassDefFoundError")) add("类或依赖缺失，请检查完整包的 libraries、模组依赖与启动入口。")
        }
        hints.forEach { Text(it, color = MaterialTheme.colorScheme.tertiary) }
        SelectionText(content.lineSequence().filter { it.contains(query, true) && (!errorsOnly || Regex("ERROR|WARN|Exception|Caused by|\\s+at\\s", RegexOption.IGNORE_CASE).containsMatchIn(it)) }.joinToString("\n"))
    }
}
