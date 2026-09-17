package com.mcmobile.server.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcmobile.server.core.CoreInstaller
import com.mcmobile.server.core.Eula
import com.mcmobile.server.core.ServerController
import com.mcmobile.server.core.console.ConsoleSession
import com.mcmobile.server.data.InstanceRepository
import com.mcmobile.server.data.ServerInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

sealed interface UiEvent {
    data class Error(val message: String) : UiEvent
    data class NeedEula(val instance: ServerInstance) : UiEvent
    data object NavigateConsole : UiEvent
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = InstanceRepository.get(app)
    val controller = ServerController(app, repo)
    val installer = CoreInstaller(app, repo)

    val instances: StateFlow<List<ServerInstance>> = repo.instances

    private val _consoleLines = MutableStateFlow<List<String>>(emptyList())
    val consoleLines: StateFlow<List<String>> = _consoleLines.asStateFlow()

    val consoleState = ConsoleSession.state

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    /** 正在运行/安装的实例 id（v1 同时只有一个） */
    private val _activeInstanceId = MutableStateFlow<String?>(null)
    val activeInstanceId: StateFlow<String?> = _activeInstanceId.asStateFlow()

    /** 正在通过安装器安装的实例 id */
    private val _installingId = MutableStateFlow<String?>(null)
    val installingId: StateFlow<String?> = _installingId.asStateFlow()

    /** 当前实例的局域网地址（`ip:端口`），供玩家在电脑上连接；服务器未运行时为 null */
    private val _lanAddress = MutableStateFlow<String?>(null)
    val lanAddress: StateFlow<String?> = _lanAddress.asStateFlow()

    init {
        viewModelScope.launch { repo.load() }
        viewModelScope.launch {
            ConsoleSession.lines.collect { line ->
                _consoleLines.update { (it + line).takeLast(3000) }
            }
        }
        // 进程退出（含服务器自行 System.exit）→ 若在安装，校验安装结果
        viewModelScope.launch {
            ConsoleSession.state.collect { st ->
                when {
                    st.status == "EXITED" -> {
                        val active = _activeInstanceId.value
                        _activeInstanceId.value = null
                        _lanAddress.value = null
                        // JVM 初始化失败时 HotSpot 直接 exit，最后几行只在 jvm-init.log 里
                        if (active != null) appendJvmInitLog(active)
                        val installing = _installingId.value
                        if (installing != null) {
                            _installingId.value = null
                            repo.byId(installing)?.let { controller.verifyInstalled(it) }
                        }
                    }
                    // 连接不上 :server 进程：不会再有 EXITED 事件，这里兜底复位
                    st.status == "DISCONNECTED" && st.error != null -> {
                        _activeInstanceId.value = null
                        _installingId.value = null
                        _lanAddress.value = null
                    }
                    // 连上且服务器在跑 → 算一次局域网地址（枚举网卡 + 读文件，别放主线程）。
                    // 每次状态变化都重算：中途切 Wi-Fi/热点时 IP 会变，显示要跟着更新。
                    st.status == "CONNECTED" &&
                        st.serverStatus in setOf("RUNNING", "STARTING", "STOPPING") -> {
                        val id = _activeInstanceId.value
                        if (id != null) {
                            viewModelScope.launch(Dispatchers.IO) {
                                repo.byId(id)?.let { instance ->
                                    _lanAddress.value = com.mcmobile.server.core.net.LanAddress
                                        .of(repo.instanceDir(instance))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun appendJvmInitLog(instanceId: String) {
        val instance = repo.byId(instanceId) ?: return
        val initLog = File(repo.instanceDir(instance), "logs/jvm-init.log")
        val tail = runCatching { initLog.readLines().drop(1) }.getOrNull()
            ?.filter { it.isNotBlank() } ?: return
        if (tail.isEmpty()) return
        _consoleLines.update { (it + "[MC服务器] JVM 初始化输出：" + tail).takeLast(3000) }
    }

    suspend fun createInstance(
        name: String,
        type: com.mcmobile.server.data.ServerType,
        mcVersion: String,
        coreVersion: String?,
        heapMb: Int,
    ): ServerInstance = repo.create(
        name = name,
        type = type,
        mcVersion = mcVersion,
        coreVersion = coreVersion,
        maxHeapMb = heapMb,
        javaMajor = com.mcmobile.server.core.McVersions.bundledJavaMajor(mcVersion),
    )

    fun start(instance: ServerInstance) {
        viewModelScope.launch {
            when (val r = controller.start(instance)) {
                is ServerController.StartResult.Started -> {
                    _activeInstanceId.value = instance.id
                    if (!instance.installed) _installingId.value = instance.id
                    ConsoleSession.connect()
                    _events.emit(UiEvent.NavigateConsole)
                }

                is ServerController.StartResult.NeedEula ->
                    _events.emit(UiEvent.NeedEula(r.instance))

                is ServerController.StartResult.Error ->
                    _events.emit(UiEvent.Error(r.message))
            }
        }
    }

    fun acceptEula(instance: ServerInstance) {
        viewModelScope.launch {
            Eula.accept(repo.instanceDir(instance))
            start(instance)
        }
    }

    fun stopServer(force: Boolean) {
        if (force) ConsoleSession.requestKill() else ConsoleSession.requestStop()
    }

    fun instanceDirOf(instance: ServerInstance) = repo.instanceDir(instance)

    fun instanceById(id: String) = repo.byId(id)

    fun delete(instance: ServerInstance) {
        viewModelScope.launch { repo.delete(instance) }
    }

    fun clearConsole() {
        _consoleLines.value = emptyList()
    }
}
