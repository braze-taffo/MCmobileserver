package com.mcmobile.server.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.mcmobile.server.core.update.AppRelease
import com.mcmobile.server.core.update.UpdateChecker
import com.mcmobile.server.core.update.UpdateResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UpdateState(
    val autoCheck: Boolean = true,
    val checking: Boolean = false,
    val message: String? = null,
    val release: AppRelease? = null,
    val showDialog: Boolean = false,
)

class UpdateViewModel(app: Application) : AndroidViewModel(app), DefaultLifecycleObserver {
    private val prefs = app.getSharedPreferences("app_settings", 0)
    val currentVersion: String = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "1.0.0"
    private val _state = MutableStateFlow(UpdateState(autoCheck = prefs.getBoolean("auto_update_check", true)))
    val state = _state.asStateFlow()
    private val checker = UpdateChecker()
    private var job: Job? = null
    private var manualRequest = false
    private var dismissedVersion: String? = null

    init { ProcessLifecycleOwner.get().lifecycle.addObserver(this) }

    override fun onStart(owner: LifecycleOwner) { if (_state.value.autoCheck) check() }

    fun setAutoCheck(enabled: Boolean) {
        prefs.edit().putBoolean("auto_update_check", enabled).apply()
        _state.update { it.copy(autoCheck = enabled) }
        if (!enabled && !manualRequest) {
            job?.cancel()
            _state.update { it.copy(checking = false, showDialog = false) }
        }
    }

    fun check(manual: Boolean = false) {
        if (job?.isActive == true) return
        manualRequest = manual
        job = viewModelScope.launch {
            _state.update { it.copy(checking = true, message = null) }
            try {
                val result = checker.check(currentVersion, Build.SUPPORTED_ABIS.toList())
                val release = (result as? UpdateResult.Available)?.release
                val message = when (result) {
                    UpdateResult.NoRelease -> "暂无正式发行版"
                    UpdateResult.Current -> "已是最新版本"
                    UpdateResult.NoCompatibleApk -> "发现新版，但尚未提供适配此设备的安装包"
                    is UpdateResult.Available -> "发现新版本 ${result.release.version}"
                }
                _state.update { it.copy(
                    release = release, message = message,
                    showDialog = release != null && (manual || release.version != dismissedVersion),
                ) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = "检查失败，请稍后重试：${e.message ?: "网络不可用"}") }
            } finally {
                _state.update { it.copy(checking = false) }
            }
        }
    }

    fun showRelease() { _state.update { it.copy(showDialog = it.release != null) } }
    fun dismissRelease() {
        dismissedVersion = _state.value.release?.version
        _state.update { it.copy(showDialog = false) }
    }

    override fun onCleared() { ProcessLifecycleOwner.get().lifecycle.removeObserver(this) }
}
