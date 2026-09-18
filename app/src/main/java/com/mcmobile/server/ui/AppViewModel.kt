package com.mcmobile.server.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcmobile.server.core.CoreInstaller
import com.mcmobile.server.core.Eula
import com.mcmobile.server.core.ServerController
import com.mcmobile.server.core.console.ConsoleSession
import com.mcmobile.server.core.console.StartConflict
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
import kotlinx.coroutines.withContext
import java.io.File

sealed interface UiEvent {
    data class Error(val message: String) : UiEvent
    data class Info(val message: String) : UiEvent
    data class NeedEula(val instance: ServerInstance) : UiEvent
    data object NavigateConsole : UiEvent
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = InstanceRepository.get(app)
    val controller = ServerController(app, repo)
    val installer = CoreInstaller(app, repo)

    val instances: StateFlow<List<ServerInstance>> = repo.instances

    /** 实例清单损坏等加载期问题，供首页提示 */
    val loadError: StateFlow<String?> = repo.loadError

    fun dismissLoadError() = repo.dismissLoadError()

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

    /** 正在执行删除（含等待服务器停止）的实例 id */
    private val _deletingId = MutableStateFlow<String?>(null)
    val deletingId: StateFlow<String?> = _deletingId.asStateFlow()

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
                    st.status == "CONNECTED" && StartConflict.isActive(st.serverStatus) -> {
                        // 优先信状态帧报的实例：UI 重启后 _activeInstanceId 是空的，
                        // 但 :server 可能还在跑（此时局域网地址一样要显示出来）
                        (_activeInstanceId.value ?: st.instanceId)?.let { id ->
                            viewModelScope.launch { refreshLanAddress(id) }
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

    /** 枚举网卡 + 读 server.properties，别放主线程 */
    private suspend fun refreshLanAddress(instanceId: String?) {
        if (instanceId == null) {
            _lanAddress.value = null
            return
        }
        withContext(Dispatchers.IO) {
            val instance = repo.byId(instanceId) ?: return@withContext
            _lanAddress.value = com.mcmobile.server.core.net.LanAddress.of(repo.instanceDir(instance))
        }
    }

    suspend fun createInstance(
        name: String,
        type: com.mcmobile.server.data.ServerType,
        mcVersion: String,
        coreVersion: String?,
        heapMb: Int,
        /** 核心 jar 内声明的 Java 要求（若有）：比版本号启发式更权威 */
        requiredJava: Int? = null,
        storage: com.mcmobile.server.data.StorageKind = com.mcmobile.server.data.StorageKind.INTERNAL,
        /** EXTERNAL 时为用户所选目录的绝对路径 */
        externalRoot: String? = null,
    ): ServerInstance = repo.create(
        name = name,
        type = type,
        mcVersion = mcVersion,
        coreVersion = coreVersion,
        maxHeapMb = heapMb,
        javaMajor = requiredJava?.let { com.mcmobile.server.core.McVersions.bundledJavaMajorFor(it) }
            ?: com.mcmobile.server.core.McVersions.bundledJavaMajor(mcVersion),
        storage = storage,
        externalRoot = externalRoot,
    )

    fun start(instance: ServerInstance) {
        viewModelScope.launch {
            when (val r = controller.start(instance)) {
                is ServerController.StartResult.Started -> {
                    // 换实例就必须换一份日志：控制台缓冲是全局的，
                    // 不清掉的话新实例的控制台里会混着上一个实例（甚至已被删除的实例）的输出
                    _consoleLines.value = emptyList()
                    // :server 进程同时只跑一个实例。若它已经在跑别的实例，本次启动会被服务端拒绝，
                    // 这里必须说清楚，否则用户看到旧实例的日志会以为新实例起来了。
                    val running = ConsoleSession.activeServer()
                    val conflict = running?.let {
                        StartConflict.describe(it.status, it.instanceId, it.name, instance.id)
                    }
                    if (running != null && conflict != null) {
                        // 界面对齐真实情况：把"正在跑的那个"标成活动实例，用户才能从控制台停掉它
                        _activeInstanceId.value = running.instanceId
                        refreshLanAddress(running.instanceId)
                        _events.emit(UiEvent.Error(conflict))
                        _events.emit(UiEvent.NavigateConsole)
                        return@launch
                    }
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

    /**
     * 删除实例。运行中的实例必须先停服：:server 进程持着实例目录作为工作目录，
     * 目录被删掉之后进程还会继续占着 25565 端口跑（"幽灵服务器"），
     * 列表里看不到、日志还在、之后启动任何实例都会被它顶掉。
     *
     * @param deleteFiles false 表示只从列表里移除（外部目录里的实例，用户可能想自己留着文件）
     */
    fun delete(instance: ServerInstance, deleteFiles: Boolean = true) {
        viewModelScope.launch {
            _deletingId.value = instance.id
            try {
                val wasActive = _activeInstanceId.value == instance.id
                // UI 进程重启后 _activeInstanceId 是空的，但 :server 可能还在跑，
                // 所以向进程本身问一句"现在在跑哪个实例"
                val runningId = ConsoleSession.activeServer(attempts = 4)?.instanceId
                if (wasActive || runningId == instance.id) {
                    if (!controller.stopAndWait()) {
                        _events.emit(UiEvent.Error("服务器没有停下来，已取消删除；可在控制台强制结束后重试"))
                        return@launch
                    }
                }
                if (wasActive) {
                    _activeInstanceId.value = null
                    _lanAddress.value = null
                    // 控制台缓冲属于被删掉的那个实例，留着只会让人以为它还在
                    _consoleLines.value = emptyList()
                }
                if (!repo.delete(instance, deleteFiles = deleteFiles)) {
                    _events.emit(UiEvent.Error("实例已从列表移除，但目录未能完全删除"))
                } else if (!deleteFiles) {
                    // 只移出列表时磁盘文件还在，必须给个回执，否则用户会以为文件被删了
                    _events.emit(
                        UiEvent.Info(
                            "已从列表移除；文件仍保留在 ${repo.instanceDir(instance).absolutePath}",
                        ),
                    )
                }
            } finally {
                _deletingId.value = null
            }
        }
    }

    fun clearConsole() {
        _consoleLines.value = emptyList()
    }
}
