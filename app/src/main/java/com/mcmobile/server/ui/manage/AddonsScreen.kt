package com.mcmobile.server.ui.manage

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavController
import com.mcmobile.server.core.manage.*
import com.mcmobile.server.core.files.ManagedFiles
import com.mcmobile.server.data.ServerInstance
import com.mcmobile.server.ui.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddonsScreen(vm: AppViewModel, instance: ServerInstance, nav: NavController) {
    val root = vm.instanceDirOf(instance)
    val state = rememberPanelState()
    val installedScroll = rememberLazyListState()
    val downloadScroll = rememberLazyListState()
    var localRevision by remember { mutableIntStateOf(0) }
    var local by remember(instance.id) { mutableStateOf<List<Addons.Local>>(emptyList()) }
    var tab by rememberSaveable(instance.id) { mutableIntStateOf(0) }
    var filter by rememberSaveable(instance.id) { mutableStateOf("") }
    var status by rememberSaveable(instance.id) { mutableIntStateOf(0) }
    var query by rememberSaveable(instance.id) { mutableStateOf("") }
    var sort by remember { mutableStateOf(Addons.Sort.RELEVANCE) }
    var searched by remember { mutableStateOf("") }
    var searchedSort by remember { mutableStateOf(Addons.Sort.RELEVANCE) }
    var result by remember(instance.id) { mutableStateOf<Addons.SearchPage?>(null) }
    var selected by remember { mutableStateOf<Addons.Hit?>(null) }
    var versions by remember { mutableStateOf<List<Addons.Version>>(emptyList()) }
    var plan by remember { mutableStateOf<List<Addons.Version>?>(null) }
    var previous by remember { mutableStateOf<File?>(null) }
    var removing by remember { mutableStateOf<Addons.Local?>(null) }
    val loader = Addons.loader(instance.type)
    LaunchedEffect(instance.id, localRevision) {
        try { local = withContext(Dispatchers.IO) { Addons.local(root, instance.type) } }
        catch (e: Exception) { state.message = "列表读取失败：${e.message}" }
    }
    fun search(offset: Int, fresh: Boolean = false) {
        val text = if (fresh) query else searched
        val order = if (fresh) sort else searchedSort
        state.work {
            val next = Addons.searchPage(text, instance.type, instance.mcVersion, offset, order)
            result = next; searched = text; searchedSort = order
            if (next.hits.isEmpty()) "没有兼容结果，试试英文名称或更短的关键词" else ""
        }
    }
    removing?.let { addon -> Confirm("删除扩展？", "${addon.name}\n配置文件会保留。", { removing = null }) {
        state.work { requireStopped(vm, instance); ManagedFiles(root).delete(addon.file); localRevision++; "已删除 ${addon.name}" }
    } }
    selected?.let { hit ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(hit.title) },
            text = { LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("MC ${instance.mcVersion} · $loader · 选择版本") }
                if (versions.isEmpty()) item { Text("未找到可安装的兼容版本") }
                items(versions, key = { it.id }) { version ->
                    OutlinedButton(enabled = !state.busy, onClick = {
                        selected = null
                        state.work { previous = null; plan = Addons.plan(version, instance.type, instance.mcVersion); "" }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(version.number)
                            Text("${releaseLabel(version.releaseType)} · ${version.published.take(10)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } }, confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } })
    }
    plan?.let { downloads ->
        AlertDialog(onDismissRequest = { plan = null }, title = { Text("安装预览") },
            text = { LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item { Text("MC ${instance.mcVersion} · $loader\n包含必需依赖，需要先停服。") }
                items(downloads, key = { it.id }) { Text("${it.name}\n${it.number} · ${releaseLabel(it.releaseType)}", Modifier.padding(vertical = 8.dp)) }
                item { Text("同 ID 的旧 JAR 会移入备份目录；下载和校验完成后再替换。", style = MaterialTheme.typography.bodySmall) }
            } }, confirmButton = { TextButton(enabled = !state.busy, onClick = {
                val old = previous; plan = null
                state.work {
                    requireStopped(vm, instance)
                    var lastProgress = 0L
                    AddonInstall.install(root, instance.type, downloads, old, vm.backupsDir(instance)) {
                        val now = System.nanoTime()
                        if (now - lastProgress > 100_000_000) { state.progress = it; lastProgress = now }
                    }
                    localRevision++
                    "安装完成，下次启动生效；请核对加载日志"
                }
            }) { Text("下载安装") } }, dismissButton = { TextButton(onClick = { plan = null }) { Text("取消") } })
    }
    val displayed = remember(local, filter, status) {
        local.filter { addon ->
            (status == 0 || addon.enabled == (status == 1)) &&
                listOfNotNull(addon.name, addon.id, addon.file.name).any { it.contains(filter.trim(), true) }
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("模组与插件") }, navigationIcon = {
        IconButton(enabled = !state.busy, onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text("${instance.type.label} · Minecraft ${instance.mcVersion}", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("已安装 · ${local.size}") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("下载模组 / 插件") })
            }
            if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(state.progress.ifBlank { "正在处理…" }, Modifier.padding(12.dp)) }
            if (state.message.isNotBlank()) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { state.message = "" }) { Text("关闭") }
            }
            LazyColumn(Modifier.weight(1f), state = if (tab == 0) installedScroll else downloadScroll,
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tab == 0) {
                    item {
                        OutlinedTextField(filter, { filter = it }, singleLine = true, label = { Text("搜索已安装名称 / ID / 文件名") }, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("全部", "已启用", "已禁用").forEachIndexed { index, label -> FilterChip(selected = status == index, onClick = { status = index }, label = { Text(label) }) }
                        }
                        Text("已启用 ${local.count { it.enabled }} · 已禁用 ${local.count { !it.enabled }}", style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(enabled = !state.busy, onClick = { localRevision++ }) { Text("刷新") }
                            TextButton(enabled = !state.busy, onClick = {
                                state.work {
                                    val folder = ManagedFiles(root).child(root, Addons.directory(instance.type))
                                    if (!folder.isDirectory) { requireStopped(vm, instance); check(folder.mkdirs()) { "无法创建目录" } }
                                    withContext(Dispatchers.Main) { nav.navigate("files/${instance.id}?initial=${Addons.directory(instance.type)}") }
                                    ""
                                }
                            }) { Text("导入本地 JAR") }
                        }
                    }
                    if (displayed.isEmpty()) item {
                        Text(if (local.isEmpty()) "尚未安装扩展，可在线搜索或导入本地 JAR。" else "没有匹配的本地扩展")
                        if (local.isEmpty()) Button(onClick = { tab = 1 }) { Text("搜索下载") }
                    }
                    items(displayed, key = { it.file.absolutePath }) { addon ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row {
                                Text(addon.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                Text(if (addon.enabled) "启用" else "禁用", color = if (addon.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("${addon.version ?: "版本未知"}${addon.id?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                            Text(addon.file.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(enabled = !state.busy, onClick = { state.work {
                                    requireStopped(vm, instance)
                                    ManagedFiles(root).rename(addon.file, if (addon.enabled) addon.file.name + ".disabled" else addon.file.name.removeSuffix(".disabled"))
                                    localRevision++
                                    "已${if (addon.enabled) "禁用" else "启用"} ${addon.name}，下次启动生效"
                                } }) { Text(if (addon.enabled) "禁用" else "启用") }
                                TextButton(enabled = !state.busy && loader != null, onClick = { state.work {
                                    previous = addon.file
                                    val latest = Addons.update(addon.file, instance.type, instance.mcVersion)
                                    if (latest == null) "没有可匹配的兼容更新，可到下载页手动搜索"
                                    else { plan = Addons.plan(latest, instance.type, instance.mcVersion); "" }
                                } }) { Text("更新") }
                                TextButton(enabled = !state.busy, onClick = { removing = addon }) { Text("删除") }
                            }
                        } }
                    }
                } else {
                    item {
                        Text("Modrinth · 自动筛选服务端、MC 版本与加载器", style = MaterialTheme.typography.bodySmall)
                        if (loader == null) Text("原版服务端无法加载扩展，请先创建模组或插件服务端。")
                        OutlinedTextField(query, { query = it }, enabled = !state.busy, singleLine = true, label = { Text("名称或关键词（推荐英文）") }, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Addons.Sort.entries.forEach { order -> FilterChip(selected = sort == order, enabled = !state.busy, onClick = { sort = order }, label = { Text(order.label) }) }
                        }
                        Button(enabled = !state.busy && loader != null, onClick = { search(0, true) }, modifier = Modifier.fillMaxWidth()) { Text(if (query.isBlank()) "浏览兼容扩展" else "搜索") }
                    }
                    result?.let { page ->
                        item { Text("共 ${page.total} 项 · 第 ${page.offset / 20 + 1} 页", style = MaterialTheme.typography.bodySmall) }
                        items(page.hits, key = { it.id }) { hit ->
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(hit.title, style = MaterialTheme.typography.titleMedium)
                                Text(hit.description, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                Text("下载 ${hit.downloads}", style = MaterialTheme.typography.labelMedium)
                                Row {
                                    Button(enabled = !state.busy, onClick = { state.work {
                                        val available = Addons.versions(hit.id, instance.type, instance.mcVersion)
                                        val latest = available.firstOrNull { it.releaseType == "release" } ?: available.firstOrNull()
                                        if (latest == null) "未找到兼容 JAR"
                                        else { previous = null; plan = Addons.plan(latest, instance.type, instance.mcVersion); "" }
                                    } }) { Text("安装最新版") }
                                    TextButton(enabled = !state.busy, onClick = { state.work {
                                        versions = Addons.versions(hit.id, instance.type, instance.mcVersion); selected = hit; ""
                                    } }) { Text("选版本") }
                                }
                            } }
                        }
                        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            OutlinedButton(enabled = !state.busy && page.offset > 0, onClick = { search((page.offset - 20).coerceAtLeast(0)) }) { Text("上一页") }
                            OutlinedButton(enabled = !state.busy && page.hasNext, onClick = { search(page.offset + 20) }) { Text("下一页") }
                        } }
                    }
                }
            }
        }
    }
}

private fun releaseLabel(type: String): String = when (type) { "release" -> "正式版"; "beta" -> "测试版"; else -> "预览版" }
