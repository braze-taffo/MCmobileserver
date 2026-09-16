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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
        val exitCode: Int? = null,
        val error: String? = null,
    )

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
    suspend fun connect() = withContext(Dispatchers.IO) {
        if (isConnected()) return@withContext
        connectMutex.withLock {
            if (isConnected()) return@withLock
            var lastError: Exception? = null
            repeat(25) {
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

    fun isConnected(): Boolean = socket?.isConnected == true && socket?.isClosed == false

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
        val exitCode: Int? = null,
        val error: String? = null,
    )
}
