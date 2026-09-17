package com.mcmobile.server.ui.files

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mcmobile.server.data.ServerInstance
import com.mcmobile.server.core.files.InstanceFiles
import com.mcmobile.server.ui.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(vm: AppViewModel, instance: ServerInstance, nav: NavController) {
    val context = LocalContext.current
    val root = vm.instanceDirOf(instance)
    var cwd by remember { mutableStateOf(root) }
    var refresh by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<File?>(null) }
    var importDirectory by remember { mutableStateOf(root) }
    var modsImport by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val operations = remember(root) { InstanceFiles(root) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val destination = importDirectory
        val toMods = modsImport
        busy = true
        scope.launch {
            try {
                val message = withContext(Dispatchers.IO) {
                    var imported = 0
                    var skipped = 0
                    val errors = mutableListOf<String>()
                    for (uri in uris) {
                        var name = "所选文件"
                        try {
                            name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                                if (cursor.moveToFirst()) cursor.getString(0) else null
                            } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "imported.bin"
                            val result = context.contentResolver.openInputStream(uri)?.use { input ->
                                if ((toMods || destination == File(root, "mods")) && name.endsWith(".zip", true)) {
                                    val archive = File.createTempFile("mod-upload-", ".zip", context.cacheDir)
                                    try {
                                        archive.outputStream().use { input.copyTo(it) }
                                        operations.importModsZip(archive, context.cacheDir)
                                    } finally { archive.delete() }
                                } else {
                                    require(!toMods || name.endsWith(".jar", true)) { "请选择 JAR 模组或 ZIP 压缩包" }
                                    operations.importFile(destination, name, input)
                                }
                            } ?: error("无法读取文件")
                            imported += result.imported
                            skipped += result.skipped
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            errors += "$name：${e.message ?: "读取失败"}"
                        }
                    }
                    buildString {
                        append("已导入 $imported 个文件")
                        if (skipped > 0) append("，跳过 $skipped 个同名文件")
                        if (errors.isNotEmpty()) append("；${errors.size} 个导入失败：${errors.first()}")
                    }
                }
                refresh++
                busy = false
                snackbar.showSnackbar(message)
            } finally {
                busy = false
            }
        }
    }

    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除${if (file.isDirectory) "文件夹" else "文件"}？") },
            text = { Text("将永久删除“${file.name}”${if (file.isDirectory) "及其全部内容" else ""}，无法撤销。建议先停止服务器。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    busy = true
                    scope.launch {
                        val message = try {
                            withContext(Dispatchers.IO) { operations.delete(file) }
                            "已删除 ${file.name}"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            "删除失败：${e.message}"
                        } finally {
                            refresh++
                            busy = false
                        }
                        snackbar.showSnackbar(message)
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    val files = remember(cwd, refresh) {
        cwd.listFiles()?.sortedWith(
            compareBy<File> { !it.isDirectory }.thenByDescending { it.lastModified() },
        ) ?: emptyList()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(instance.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            cwd.toRelativeString(root).ifEmpty { "." },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (cwd == root) nav.popBackStack() else cwd = cwd.parentFile ?: root
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    TextButton(enabled = !busy, onClick = {
                        importDirectory = File(root, "mods")
                        modsImport = true
                        importLauncher.launch(arrayOf("*/*"))
                    }) { Text("导入 Mod / ZIP") }
                    IconButton(enabled = !busy, onClick = {
                        importDirectory = cwd
                        modsImport = false
                        importLauncher.launch(arrayOf("*/*"))
                    }) { Icon(Icons.Default.UploadFile, "导入文件到当前目录") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (cwd == root) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(
                        "可多选导入文件。“导入 Mod / ZIP”会将 JAR 或 ZIP 内的所有 JAR 放进 mods/；同名文件跳过。也可在 mods/ 中直接导入 ZIP。删除请点文件右侧垃圾桶。",
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(files, key = { it.absolutePath }) { f ->
                    ListItem(
                        headlineContent = { Text(f.name) },
                        supportingContent = {
                            if (f.isFile) {
                                Text("${f.length() / 1024} KB")
                            }
                        },
                        leadingContent = {
                            Icon(
                                if (f.isDirectory) Icons.Default.Folder
                                else Icons.AutoMirrored.Filled.InsertDriveFile,
                                null,
                            )
                        },
                        trailingContent = {
                            IconButton(enabled = !busy, onClick = { pendingDelete = f }) {
                                Icon(Icons.Default.Delete, "删除 ${f.name}")
                            }
                        },
                        modifier = Modifier.clickable(enabled = !busy && f.isDirectory) { cwd = f },
                    )
                }
            }
        }
    }
}
