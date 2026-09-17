package com.mcmobile.server.core.console

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.ArrayDeque

/**
 * :server 进程内的控制台桥：
 *  - 维护最近日志的环形缓冲（UI 重连后回放）
 *  - 持有 LocalServerSocket，向所有已连接 UI 客户端广播
 *  - 接收 UI 发来的命令（C\t<cmd>）与控制指令（STOP/KILL）
 *  - 每行日志同步写入实例 logs/run-latest.log（无缓冲，进程随时可能被 System.exit 带走）
 */
class ConsoleBridgeServer(
    socketName: String,
    logFile: File?,
    private val onCommand: (String) -> Unit,
    private val onControl: (String) -> Unit,
) {
    companion object {
        const val MAX_LINES = 3000
    }

    private val history = ArrayDeque<String>()
    private val clients = ArrayList<LocalSocket>()
    private val lock = Any()
    private val server: LocalServerSocket = LocalServerSocket(socketName)
    private val logWriter: FileOutputStream? = logFile?.let { runCatching { FileOutputStream(it, true) }.getOrNull() }
    @Volatile private var closed = false

    /** 最近一次广播的状态；新客户端连上时要补发，否则它会一直不知道服务器已在运行 */
    private var lastState: String? = null

    fun start() {
        Thread({ acceptLoop() }, "mcs-console-accept").start()
    }

    private fun acceptLoop() {
        while (!closed) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            synchronized(lock) {
                // 新连接：先回放历史日志，再补发当前状态。
                // 补状态是必须的：服务端广播 STARTING/RUNNING 时 UI 往往还没连上来，
                // 少了这一步 UI 的 serverStatus 会一直是 null，控制台的输入框和
                // 停止/强制结束按钮都会因为 running=false 而保持禁用。
                // 先写完再入列，避免并发 appendLine 抢在回放前面造成乱序。
                runCatching {
                    val os = socket.outputStream
                    for (line in history) os.write(frame(TYPE_LOG, line))
                    lastState?.let { os.write(frame(TYPE_STATE, it)) }
                }
                clients.add(socket)
            }
            Thread({ readLoop(socket) }, "mcs-console-client").start()
        }
    }

    private fun readLoop(socket: LocalSocket) {
        try {
            BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8)).useLines { seq ->
                for (raw in seq) handleClientLine(raw.trimEnd())
            }
        } catch (_: Exception) {
        }
        synchronized(lock) { clients.remove(socket) }
        runCatching { socket.close() }
    }

    private fun handleClientLine(raw: String) {
        when {
            raw.startsWith("C\t") -> onCommand(raw.substring(2))
            raw.startsWith("C ") -> onCommand(raw.substring(2))
            raw == "STOP" -> onControl("STOP")
            raw == "KILL" -> onControl("KILL")
        }
    }

    fun appendLine(line: String) {
        val bytes = frame(TYPE_LOG, line)
        synchronized(lock) {
            history.addLast(line)
            while (history.size > MAX_LINES) history.removeFirst()
            runCatching { logWriter?.write(bytes); logWriter?.fd?.sync() }
            for (c in clients) runCatching { c.outputStream.write(bytes) }
        }
    }

    fun broadcastState(json: String) {
        val bytes = frame(TYPE_STATE, json)
        synchronized(lock) {
            lastState = json
            for (c in clients) runCatching { c.outputStream.write(bytes) }
        }
    }

    fun close() {
        closed = true
        runCatching { server.close() }
        synchronized(lock) {
            clients.forEach { runCatching { it.close() } }
            clients.clear()
            runCatching { logWriter?.close() }
        }
    }

    private fun frame(type: String, payload: String): ByteArray =
        ("$type\t$payload\n").toByteArray(Charsets.UTF_8)
}

private const val TYPE_LOG = "L"
private const val TYPE_STATE = "S"
