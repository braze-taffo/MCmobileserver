package com.mcmobile.server.core.console

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** UI rendering may skip old lines, but command confirmations are completed
 * directly on live socket receipt before entering the display flow. */
internal class ConsoleLogEvents(capacity: Int = 8192) {
    private val display = MutableSharedFlow<String>(extraBufferCapacity = capacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val lines = display.asSharedFlow()
    private val lock = Any()
    private data class Expected(val pattern: Regex, val connection: Any?)
    private val replies = LinkedHashMap<CompletableDeferred<String>, Expected>()

    fun expect(pattern: Regex, connection: Any? = null): CompletableDeferred<String> {
        val reply = CompletableDeferred<String>()
        synchronized(lock) { replies[reply] = Expected(pattern, connection) }
        reply.invokeOnCompletion { synchronized(lock) { replies.remove(reply) }; Unit }
        return reply
    }

    fun publish(line: String, live: Boolean, connection: Any? = null) {
        if (live) {
            val matched = synchronized(lock) {
                replies.filterValues { it.connection === connection && it.pattern.containsMatchIn(line) }
                    .keys.toList().also { matches -> matches.forEach { replies.remove(it) } }
            }
            matched.forEach { it.complete(line) }
        }
        display.tryEmit(line)
    }

    fun disconnected(connection: Any? = null) {
        val pending = synchronized(lock) {
            replies.filterValues { it.connection === connection }.keys.toList().also { matches -> matches.forEach { replies.remove(it) } }
        }
        pending.forEach { it.completeExceptionally(java.io.IOException("控制台连接已断开，未收到命令确认")) }
    }
}
