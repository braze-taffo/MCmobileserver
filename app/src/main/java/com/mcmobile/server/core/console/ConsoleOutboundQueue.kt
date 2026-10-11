package com.mcmobile.server.core.console

import java.util.ArrayDeque

/** Bounded display transport. A stalled socket only stalls its own sender.
 * Latest state and save replies take precedence; skipped display logs remain
 * in run-latest.log. No producer waits for socket IO or for queue capacity. */
internal class ConsoleOutboundQueue(
    private val maxBytes: Int = 1024 * 1024,
    private val maxLogs: Int = 4096,
    private val maxImportant: Int = 64,
) {
    private val monitor = Object()
    private val logs = ArrayDeque<ByteArray>()
    private val important = ArrayDeque<ByteArray>()
    private var state: ByteArray? = null
    private var bytes = 0
    private var skipped = 0L
    private var closed = false

    init { require(maxBytes > 0 && maxLogs > 0 && maxImportant > 0) }

    fun log(frame: ByteArray, priority: Boolean = false) = synchronized(monitor) {
        if (closed) return@synchronized
        if (frame.size > maxBytes) { skipped++; monitor.notifyAll(); return@synchronized }
        (if (priority) important else logs).addLast(frame)
        bytes += frame.size
        trim()
        monitor.notifyAll()
    }

    fun state(frame: ByteArray) = synchronized(monitor) {
        if (closed) return@synchronized
        state?.let { bytes -= it.size }
        state = frame
        bytes += frame.size
        trim()
        monitor.notifyAll()
    }

    private fun trim() {
        while (logs.size > maxLogs || important.size > maxImportant || bytes > maxBytes) {
            val removed = when {
                logs.isNotEmpty() -> logs.removeFirst()
                important.isNotEmpty() -> important.removeFirst()
                else -> break // A single latest state is retained even if unusually large.
            }
            bytes -= removed.size
            skipped++
        }
    }

    fun take(): ByteArray? = synchronized(monitor) {
        while (!closed && state == null && important.isEmpty() && skipped == 0L && logs.isEmpty()) monitor.wait()
        if (closed) return@synchronized null
        val next = when {
            state != null -> state!!.also { state = null }
            important.isNotEmpty() -> important.removeFirst()
            skipped > 0 -> {
                val count = skipped
                skipped = 0
                // H is display-only, so a notice cannot satisfy a command reply.
                return@synchronized "H\t[MC服务器] 控制台已省略 $count 条显示日志；完整记录见 logs/run-latest.log\n".toByteArray(Charsets.UTF_8)
            }
            else -> logs.removeFirst()
        }
        bytes -= next.size
        next
    }

    fun close() = synchronized(monitor) {
        closed = true
        logs.clear(); important.clear(); state = null; bytes = 0; skipped = 0
        monitor.notifyAll()
    }
}

internal object ConsoleReplyPatterns {
    // Linear substring checks on the producer path; avoid unbounded .* regex
    // backtracking when a mod emits a very long repetitive line.
    fun isSaveReply(line: String): Boolean = line.contains("Saved the game", true) ||
        (line.contains("Saved", true) && (line.contains("world", true) || line.contains("chunks", true))) ||
        ((line.contains("saving", true) || line.contains("already", true)) &&
            (line.contains("disabled", true) || line.contains("off", true)))
}
