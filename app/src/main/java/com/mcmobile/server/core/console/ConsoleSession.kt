package com.mcmobile.server.core.console

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.mcmobile.server.service.ServerForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * UI 进程侧的控制台会话（单例，v1 同时只运行一个服务器实例）。
 * 连接 :server 进程的 LocalSocket：收日志行/状态，发命令。
 */
object ConsoleSession {

    /** 断连后推断出的终态 */
    @Serializable
    data class UiState(
        val status: String = "DISCONNECTED", // CONNECTED / DISCONNECTED / EXITED
        val serverStatus: String? = null,    // :server 进程广播的 RunStatus
        val name: String = "",
        /** :server 进程正在跑的实例 id；控制台据此判断"这份日志属于哪个实例" */
        val instanceId: String? = null,
        val exitCode: Int? = null,
        val error: String? = null,
    )

    /** :server 进程当前正在跑的实例 */
    data class Active(val instanceId: String?, val name: String, val status: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _lines = MutableSharedFlow<String>(extraBufferCapacity = 8192)
    val lines: SharedFlow<String> = _lines.asSharedFlow()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var socket: LocalSocket? = null
    private var writer: java.io.OutputStream? = null
    private val connectMutex = Mutex()

    /** 尝试连接（:server 进程可能还在启动），最多等 ~8s */
    suspend fun connect(attempts: Int = 25) = withContext(Dispatchers.IO) {
        if (isConnected()) return@withContext
        connectMutex.withLock {
            if (isConnected()) return@withLock
            var lastError: Exception? = null
            repeat(attempts) {
                try {
                    val s = LocalSocket()
                    s.connect(
                        LocalSocketAddress(
                            ServerForegroundService.SOCKET_NAME,
                            LocalSocketAddress.Namespace.ABSTRACT,
                        ),
                    )
                    socket = s
                    writer = s.outputStream
                    _state.value = UiState(status = "CONNECTED")
                    scope.launch { readLoop(s) }
                    return@withLock
                } catch (e: Exception) {
                    lastError = e
                    delay(300)
                }
            }
            _state.value = UiState(status = "DISCONNECTED", error = "无法连接服务器进程: ${lastError?.message}")
        }
    }

    /**
     * 注意：不能写成 `socket.isClosed == false` —— Android 的 [LocalSocket.isClosed] 是
     * 不支持的方法，一调用就抛 UnsupportedOperationException，会把 UI 进程直接带崩
     * （真机复现：删掉实例后启动下一个实例，必崩）。socket 失效由 [readLoop] 退出时置 null 体现。
     */
    fun isConnected(): Boolean = socket?.isConnected == true

    /**
     * 连上 :server 进程并问清楚它现在在跑哪个实例。
     *
     * UI 进程被系统回收后重启时，只有这里能发现"还有服务器在跑"——否则会拿它当没在跑，
     * 既可能重复启动，也可能把正在被使用的实例目录删掉。
     * 连不上进程（= 没有服务器在跑）返回 null。
     */
    suspend fun activeServer(attempts: Int = 8, timeoutMs: Long = 1500): Active? {
        if (!isConnected()) connect(attempts)
        if (!isConnected()) return null
        // 连上后本进程会先收到历史日志、再收到状态帧；等不到就用当前值兜底
        val st = withTimeoutOrNull(timeoutMs) {
            state.first { it.serverStatus != null || it.status == "EXITED" }
        } ?: state.value
        val serverStatus = st.serverStatus ?: return null
        if (!StartConflict.isActive(serverStatus)) return null
        return Active(st.instanceId, st.name, serverStatus)
    }

    private suspend fun readLoop(s: LocalSocket) {
        try {
            BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8)).useLines { seq ->
                for (raw in seq) {
                    val line = raw.trimEnd()
                    when {
                        line.startsWith("L\t") -> _lines.emit(line.substring(2))
                        line.startsWith("S\t") -> runCatching {
                            val msg = json.decodeFromString<ServerState>(line.substring(2))
                            _state.value = UiState(
                                status = "CONNECTED",
                                serverStatus = msg.status,
                                name = msg.name,
                                instanceId = msg.instanceId,
                                exitCode = msg.exitCode,
                                error = msg.error,
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        // 对端关闭：若从未收到终态状态，推断进程已退出（服务器 System.exit 路径）
        val prev = _state.value
        val inferred = prev.serverStatus in setOf("STOPPING", "RUNNING", "STARTING")
        _state.value = UiState(
            status = "EXITED",
            serverStatus = if (inferred || prev.serverStatus == null) "STOPPED" else prev.serverStatus,
            name = prev.name,
            instanceId = prev.instanceId,
            exitCode = prev.exitCode,
            error = prev.error,
        )
        socket = null
        writer = null
    }

    fun sendCommand(command: String) {
        send("C\t$command")
    }

    fun requestStop() {
        send("STOP")
    }

    fun requestKill() {
        send("KILL")
    }

    private fun send(frame: String) {
        try {
            writer?.let {
                it.write((frame + "\n").toByteArray(Charsets.UTF_8))
                it.flush()
            }
        } catch (_: Exception) {
        }
    }

    @Serializable
    private data class ServerState(
        val status: String,
        val name: String = "",
        val instanceId: String? = null,
        val exitCode: Int? = null,
        val error: String? = null,
    )
}
