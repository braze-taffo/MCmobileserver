package com.mcmobile.server.core.console

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * :server 进程内的控制台桥：
 *  - 维护最近日志的环形缓冲（UI 重连后回放）
 *  - 持有 LocalServerSocket，向所有已连接 UI 客户端广播
 *  - 接收 UI 发来的命令（C\t<cmd>）与控制指令（STOP/KILL）
 *  - 日志直接写入系统文件缓存；逐行不 fsync，正常关闭时同步一次
 *  - 每个客户端独立发送、有界显示队列；慢客户端不阻塞日志泵
 */
class ConsoleBridgeServer(
    socketName: String,
    logFile: File?,
    private val onCommand: (String) -> Unit,
    private val onControl: (String) -> Unit,
) {
    companion object {
        const val MAX_LINES = 3000
        private const val MAX_HISTORY_BYTES = 1024 * 1024
        private const val MAX_CLIENTS = 8
    }

    private val history = ArrayDeque<ByteArray>()
    private var historyBytes = 0
    private val clients = ArrayList<Client>()
    private val lock = Any()
    private val logLock = Any()
    private val server: LocalServerSocket = LocalServerSocket(socketName)
    private val logWriter: FileOutputStream? = logFile?.let { runCatching { FileOutputStream(it, true) }.getOrNull() }
    @Volatile private var closed = false

    /** 最近一次广播的状态；新客户端连上时要补发，否则它会一直不知道服务器已在运行 */
    private var lastState: ByteArray? = null

    fun start() {
        Thread({ acceptLoop() }, "mcs-console-accept").start()
    }

    private fun acceptLoop() {
        while (!closed) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            val client = Client(socket)
            val accepted = synchronized(lock) {
                if (closed || clients.size >= MAX_CLIENTS) false else {
                    // State need not wait for history. H marks replay so old
                    // save confirmations cannot complete a new backup request.
                    lastState?.let { client.queue.state(it) }
                    for (bytes in history) client.queue.log(bytes.copyOf().also { it[0] = 'H'.code.toByte() })
                    clients.add(client)
                    true
                }
            }
            if (accepted) client.start() else client.close()
        }
    }

    private fun readLoop(client: Client) {
        try {
            BufferedReader(InputStreamReader(client.socket.inputStream, Charsets.UTF_8)).useLines { seq ->
                for (raw in seq) handleClientLine(raw.trimEnd())
            }
        } catch (_: Exception) {
        } finally { client.close() }
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
        // Preserve file/live order across producers; never hold the client lock
        // during file IO. Unbuffered write survives System.exit via kernel cache.
        synchronized(logLock) {
            if (closed) return
            runCatching { logWriter?.write(bytes) }
            val priority = ConsoleReplyPatterns.isSaveReply(line)
            synchronized(lock) {
                history.addLast(bytes)
                historyBytes += bytes.size
                while (history.size > MAX_LINES || historyBytes > MAX_HISTORY_BYTES) historyBytes -= history.removeFirst().size
                for (client in clients) client.queue.log(bytes, priority)
            }
        }
    }

    fun broadcastState(json: String) {
        val bytes = frame(TYPE_STATE, json)
        synchronized(lock) {
            lastState = bytes
            for (client in clients) client.queue.state(bytes)
        }
    }

    fun close() {
        closed = true
        runCatching { server.close() }
        val snapshot = synchronized(lock) { clients.toList().also { clients.clear() } }
        snapshot.forEach { it.close() }
        synchronized(logLock) {
            runCatching { logWriter?.fd?.sync() }
            runCatching { logWriter?.close() }
        }
    }

    private inner class Client(val socket: LocalSocket) {
        val queue = ConsoleOutboundQueue()
        private val done = AtomicBoolean(false)
        fun start() {
            Thread({
                try {
                    val output = socket.outputStream
                    while (!done.get()) output.write(queue.take() ?: break)
                } catch (_: Exception) {
                } finally { close() }
            }, "mcs-console-send").start()
            Thread({ readLoop(this) }, "mcs-console-client").start()
        }
        fun close() {
            if (!done.compareAndSet(false, true)) return
            synchronized(lock) { clients.remove(this) }
            queue.close()
            // shutdown wakes blocked native reads/writes; close alone need not
            // interrupt an IO operation already holding the kernel descriptor.
            runCatching { socket.shutdownInput() }
            runCatching { socket.shutdownOutput() }
            runCatching { socket.close() }
        }
    }

    private fun frame(type: String, payload: String): ByteArray =
        ("$type\t$payload\n").toByteArray(Charsets.UTF_8)
}

private const val TYPE_LOG = "L"
private const val TYPE_STATE = "S"
