package com.mcmobile.server.ui.settings

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.mcmobile.server.core.repack.CustomApk
import com.mcmobile.server.ui.manage.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CustomApkState(val busy: Boolean = false, val message: String = "", val result: CustomApk.Result? = null)

class CustomApkViewModel(app: Application) : AndroidViewModel(app) {
    private val mutable = MutableStateFlow(CustomApkState())
    val state = mutable.asStateFlow()
    fun generate(name: String) {
        if (mutable.value.busy) return
        mutable.value = CustomApkState(busy = true)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    CustomApk.generate(getApplication(), name.trim()) { mutable.value = mutable.value.copy(message = it) }
                }
                mutable.value = CustomApkState(message = "生成完成，可安装或导出", result = result)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = CustomApkState(message = "生成失败：${e.message}") }
        }
    }
    fun install() {
        try { state.value.result?.let { CustomApk.install(getApplication(), it.file) } }
        catch (e: Exception) { mutable.value = mutable.value.copy(message = "无法打开安装界面：${e.message}") }
    }
    fun export(uri: android.net.Uri) {
        val file = state.value.result?.file ?: return
        if (state.value.busy) return
        mutable.value = state.value.copy(busy = true, message = "正在导出…")
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { output ->
                        file.inputStream().use { it.copyTo(output, 64 * 1024) }
                    } ?: error("无法写入所选位置")
                }
                mutable.value = state.value.copy(message = "已导出 APK")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = state.value.copy(message = "导出失败：${e.message}") }
            finally { mutable.value = state.value.copy(busy = false) }
        }
    }
}

@Composable
fun CustomApkScreen(nav: NavController, vm: CustomApkViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var name by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("com.mcmobile.custom") }
    val panel = rememberPanelState()
    panel.busy = state.busy
    panel.progress = state.message
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) {
        it?.let(vm::export)
    }
    Panel("自签名与自定义包名", nav, panel) {
        Text("当前包名：${context.packageName}", style = MaterialTheme.typography.bodySmall)
        Text("输入新包名，生成可独立安装的应用。版本号与当前应用一致。", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(name, { name = it }, enabled = !state.busy, singleLine = true,
            label = { Text("新包名") }, supportingText = { Text("例如 com.example.mcserver") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = !state.busy, onClick = { vm.generate(name) }, modifier = Modifier.fillMaxWidth()) { Text("生成并自签名 APK") }
        if (!state.busy && state.message.isNotBlank()) SelectionText(state.message)
        state.result?.let { result ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text(result.file.name, style = MaterialTheme.typography.titleMedium)
                Text("证书 SHA-256", style = MaterialTheme.typography.labelMedium)
                SelectionText(result.fingerprint)
                Row {
                    Button(enabled = !state.busy, onClick = vm::install) { Text("安装 / 授权安装") }
                    TextButton(enabled = !state.busy, onClick = { export.launch(result.file.name) }) { Text("导出 APK") }
                }
            } }
        }
        Text("本机签名证书会重复使用，方便以后更新同一包名。卸载或清除当前应用后，证书会丢失，请保留生成的 APK。", style = MaterialTheme.typography.bodySmall)
        Text("新包名使用独立应用数据。已有世界不会自动复制，请通过整合包导出 / 导入迁移。与其他应用重名且签名不同时，系统会拒绝安装。", style = MaterialTheme.typography.bodySmall)
        Text("自签名版本不能直接安装官方更新包。请在原应用更新后，用同一台设备和证书重新生成。", style = MaterialTheme.typography.bodySmall)
    }
}
