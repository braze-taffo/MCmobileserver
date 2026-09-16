package com.mcmobile.server.ui.files

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
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Card
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mcmobile.server.data.ServerInstance
import com.mcmobile.server.ui.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(vm: AppViewModel, instance: ServerInstance, nav: NavController) {
    val context = LocalContext.current
    val root = vm.instanceDirOf(instance)
    var cwd by remember { mutableStateOf(root) }
    var refresh by remember { mutableStateOf(0) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runBlocking(Dispatchers.IO) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "imported.bin"
            val target = File(cwd, name)
            context.contentResolver.openInputStream(uri)!!.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        refresh++
    }

    val files = remember(cwd, refresh) {
        cwd.listFiles()?.sortedWith(
            compareBy<File> { !it.isDirectory }.thenByDescending { it.lastModified() },
        ) ?: emptyList()
    }

    Scaffold(
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
                    IconButton(onClick = {
                        importLauncher.launch(arrayOf("*/*"))
                    }) { Icon(Icons.Default.UploadFile, "导入文件到当前目录") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (cwd == root) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(
                        "提示：模组放进 mods/，配置在 config/。用右上角按钮把文件导入当前目录。",
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
                        modifier = Modifier.clickable(enabled = f.isDirectory) { cwd = f },
                    )
                }
            }
        }
    }
}
