package com.mcmobile.server.ui.create

import android.os.Environment
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.mcmobile.server.core.files.*
import com.mcmobile.server.data.*
import com.mcmobile.server.ui.*
import com.mcmobile.server.ui.manage.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.UUID

@Composable
fun PackImportScreen(vm: AppViewModel, nav: NavController) {
    val context = LocalContext.current
    val state = rememberPanelState()
    var detection by remember { mutableStateOf<ServerPack.Detection?>(null) }
    var roots by remember { mutableStateOf<List<File>>(emptyList()) }
    var staging by remember { mutableStateOf<File?>(null) }
    var direct by remember { mutableStateOf(true) }
    var fromZip by remember { mutableStateOf(false) }
    var storage by remember { mutableStateOf(StorageKind.EXTERNAL) }
    var externalRoot by remember { mutableStateOf(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "MCServers")) }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ServerType.VANILLA) }
    var mc by remember { mutableStateOf("") }
    var javaMajor by remember { mutableIntStateOf(21) }
    var jar by remember { mutableStateOf("") }
    var argsFile by remember { mutableStateOf("") }
    var heap by remember { mutableStateOf("1024") }
    var jvmArgs by remember { mutableStateOf("") }
    var programArgs by remember { mutableStateOf("nogui") }

    fun showRoot(directory: File) {
        val found = ServerPack.inspect(directory)
        detection = found; mc = found.mcVersion ?: ""; type = found.type ?: ServerType.VANILLA
        javaMajor = found.javaMajor ?: 21; jar = found.launchJar ?: ""; argsFile = found.argsFile ?: ""
        jvmArgs = com.mcmobile.server.core.launch.LaunchArgfiles.renderTokens(found.jvmArgs, "\n")
        programArgs = com.mcmobile.server.core.launch.LaunchArgfiles.renderTokens(found.programArgs)
        if (name.isBlank()) name = directory.name.removePrefix(".pack-")
    }
    fun inspectSource(directory: File) {
        roots = ServerPack.findRoots(directory)
        when (roots.size) { 0 -> { roots = listOf(directory); showRoot(directory) }; 1 -> showRoot(roots.single()); else -> detection = null }
    }
    val sourcePicker = rememberDirectoryPicker({ dir ->
        if (state.busy) return@rememberDirectoryPicker
        state.work {
            staging?.deleteRecursively(); staging = null; fromZip = false; direct = true; name = dir.name
            inspectSource(dir)
            "已读取目录；确认启动方式后再加入服务器列表"
        }
    }, { state.message = it })
    val destinationPicker = rememberDirectoryPicker({ externalRoot = it }, { state.message = it })
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) state.work {
            staging?.deleteRecursively(); staging = null; detection = null
            val filename = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "server.zip"
            require(filename.endsWith(".zip", true)) { "请选择 ZIP 服务端压缩包" }
            val parent = if (storage == StorageKind.EXTERNAL) externalRoot else File(context.filesDir, "servers")
            if (storage == StorageKind.EXTERNAL) require(com.mcmobile.server.core.storage.StorageLocation.hasWriteAccess(context)) { "请先选择共享存放目录并授予权限，或改为内部存储" }
            check(parent.isDirectory || parent.mkdirs()) { "无法创建目标目录" }
            val archive = File.createTempFile(".pack-upload-", ".zip", parent)
            val unpacked = Files.createTempDirectory(parent.toPath(), ".pack-").toFile()
            try {
                val job = kotlin.coroutines.coroutineContext
                context.contentResolver.openInputStream(uri)?.use { input -> archive.outputStream().use { input.copyTo(it) } } ?: error("无法读取 ZIP")
                ManagedFiles.unpack(archive, unpacked) { job.ensureActive(); state.progress = "${it.files} 项 · ${it.bytes / 1048576} MB · ${it.path}" }
                staging = unpacked; fromZip = true; direct = false; name = filename.substringBeforeLast('.')
                inspectSource(unpacked)
                "解压完成；原有目录结构已保留，请确认启动配置"
            } catch (t: Throwable) { unpacked.deleteRecursively(); throw t }
            finally { archive.delete() }
        }
    }
    DisposableEffect(Unit) { onDispose { if (!state.busy) staging?.deleteRecursively() } }
    Panel("导入完整服务端", nav, state) {
        Text("导入已经安装完整的服务端包。核心、libraries、mods、config、插件和世界会一起保留。")
        Row {
            Button(enabled = !state.busy, onClick = { zipPicker.launch(arrayOf("*/*")) }) { Text("选择 ZIP") }
            TextButton(enabled = !state.busy, onClick = sourcePicker) { Text("选择已解压目录") }
        }
        if (!fromZip && detection != null) Row {
            FilterChip(direct, { direct = true }, label = { Text("直接使用原目录") })
            FilterChip(!direct, { direct = false }, label = { Text("复制为独立副本") })
        }
        if (!direct || detection == null) {
            Row {
                FilterChip(storage == StorageKind.EXTERNAL, { storage = StorageKind.EXTERNAL }, label = { Text("共享目录（默认）") })
                FilterChip(storage == StorageKind.INTERNAL, { storage = StorageKind.INTERNAL }, label = { Text("应用内部") })
            }
            if (storage == StorageKind.EXTERNAL) {
                SelectionText(externalRoot.absolutePath)
                OutlinedButton(enabled = !state.busy, onClick = destinationPicker) { Text("选择存放目录 / 授予权限") }
            }
        }
        if (roots.size > 1) {
            Text("包内有多个候选服务端，请选择实际根目录")
            roots.forEach { candidate -> OutlinedButton(onClick = { state.work { showRoot(candidate); "已选择 ${candidate.name}" } }) { Text(candidate.relativeTo(staging ?: candidate.parentFile!!).path) } }
        }
        detection?.let { detected ->
            SelectionText("实际服务端根目录：${detected.directory.absolutePath}")
            detected.notes.forEach { Text(it, color = MaterialTheme.colorScheme.tertiary) }
            OutlinedTextField(name, { name = it }, label = { Text("服务器名称") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(mc, { mc = it }, label = { Text("Minecraft 版本") }, singleLine = true)
            ServerType.entries.chunked(3).forEach { group -> Row { group.forEach { choice ->
                FilterChip(type == choice, { type = choice }, label = { Text(choice.name) })
            } } }
            listOf(8, 17, 21, 25).chunked(2).forEach { group -> Row { group.forEach { major ->
                FilterChip(javaMajor == major, { javaMajor = major }, label = { Text("Java $major") })
            } } }
            Text("Java 8/17 首次使用按需下载；所有启动设置以后都可修改。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(jar, { jar = it; if (it.isNotBlank()) argsFile = "" }, label = { Text("启动 JAR（相对根目录）") }, modifier = Modifier.fillMaxWidth())
            if (detected.jars.size > 1) detected.jars.forEach { path -> TextButton(onClick = { jar = path; argsFile = "" }) { Text("选择 $path") } }
            OutlinedTextField(argsFile, { argsFile = it; if (it.isNotBlank()) jar = "" }, label = { Text("启动参数文件（与 JAR 二选一）") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(heap, { heap = it }, label = { Text("最大内存 MB") }, singleLine = true)
            OutlinedTextField(jvmArgs, { jvmArgs = it }, label = { Text("额外 JVM 参数（可换行，支持引号）") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(programArgs, { programArgs = it }, label = { Text("服务端程序参数") }, modifier = Modifier.fillMaxWidth())
            Button(enabled = !state.busy && mc.isNotBlank() && name.isNotBlank() && (jar.isNotBlank() || argsFile.isNotBlank()), onClick = {
                val source = detected.directory; val useOriginal = direct && !fromZip
                val chosenStorage = if (useOriginal) StorageKind.EXTERNAL else storage
                val chosenParent = if (useOriginal) source.parentFile!! else if (storage == StorageKind.EXTERNAL) externalRoot else File(context.filesDir, "servers")
                val uid = UUID.randomUUID().toString()
                val dirname = if (useOriginal) source.name else "pack-${uid.take(8)}"
                val planned = ServerInstance(uid, name.trim(), type, mc.trim(), detected.coreVersion, dirname,
                    chosenStorage, chosenParent.absolutePath.takeIf { chosenStorage == StorageKind.EXTERNAL },
                    maxHeapMb = heap.toIntOrNull() ?: 0, javaMajor = javaMajor, createdMs = System.currentTimeMillis(), installed = true,
                    launchJar = jar.trim().takeIf { it.isNotBlank() }, launchArgsFile = argsFile.trim().takeIf { it.isNotBlank() })
                state.work {
                    require(planned.maxHeapMb >= 128) { "内存至少为 128 MB" }
                    val configured = planned.copy(minHeapMb = minOf(256, planned.maxHeapMb),
                        extraJvmArgs = com.mcmobile.server.core.launch.LaunchArgfiles.tokenize(jvmArgs),
                        programArgs = com.mcmobile.server.core.launch.LaunchArgfiles.tokenize(programArgs))
                    ServerPack.verify(configured, source)
                    val spec = com.mcmobile.server.core.launch.LaunchSpecBuilder.build(configured, source, com.mcmobile.server.core.jre.JreManager.javaHomeDir(context, javaMajor))
                    require(spec.spec != null) { spec.error ?: "启动设置无效" }
                    if (chosenStorage == StorageKind.EXTERNAL) require(com.mcmobile.server.core.storage.StorageLocation.hasWriteAccess(context)) { "请授予所有文件访问权限" }
                    val target = File(chosenParent, dirname)
                    if (!useOriginal) {
                        chosenParent.mkdirs()
                        require(!target.exists()) { "目标目录已经存在" }
                        val job = kotlin.coroutines.coroutineContext
                        var moved = false
                        try {
                            // A ZIP staging tree is already complete: avoid copying gigabytes again on the same volume.
                            moved = fromZip && source.renameTo(target)
                            if (!moved) ServerPack.copy(source, target) { job.ensureActive(); state.progress = "${it.bytes / 1048576} MB · ${it.path}" }
                            vm.registerPrepared(configured)
                        } catch (t: Throwable) {
                            if (moved) {
                                if (!target.renameTo(source)) throw IllegalStateException("导入登记失败；完整暂存文件保留在 ${target.absolutePath}", t)
                            } else target.deleteRecursively()
                            throw t
                        }
                    } else vm.registerPrepared(configured)
                    staging?.deleteRecursively(); staging = null
                    withContext(Dispatchers.Main) { nav.navigate(Routes.manage(planned.id)) { popUpTo(Routes.PACK_IMPORT) { inclusive = true } } }
                    "已导入完整服务端"
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("确认配置并加入服务器列表") }
        }
    }
}
