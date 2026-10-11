package com.mcmobile.server.core.console

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConsoleBackpressureTest {
    @Test fun originalFlowOnlyBackpressuresWithAnActuallySlowSubscriber() = runBlocking {
        val original = MutableSharedFlow<String>(extraBufferCapacity = 2)
        withTimeout(1000) { repeat(20000) { original.emit("no-subscriber-$it") } }
        val block = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val reader = launch(start = CoroutineStart.UNDISPATCHED) { original.collect { entered.complete(Unit); block.await() } }
        original.emit("start")
        entered.await()
        val writer = launch(start = CoroutineStart.UNDISPATCHED) { repeat(100) { original.emit("blocked-$it") } }
        assertFalse(writer.isCompleted)
        block.complete(Unit)
        withTimeout(1000) { writer.join() }
        reader.cancelAndJoin()
    }
    @Test fun overflowKeepsLatestStateAndSaveReplyAndReportsSkippedDisplayLines() {
        val queue = ConsoleOutboundQueue(maxBytes = 200, maxLogs = 2)
        repeat(100) { queue.log("L\tline-$it\n".toByteArray()) }
        queue.log("L\tSaved the game\n".toByteArray(), priority = true)
        queue.state("S\told\n".toByteArray())
        queue.state("S\tlatest\n".toByteArray())
        assertEquals("S\tlatest\n", queue.take()!!.toString(Charsets.UTF_8))
        assertEquals("L\tSaved the game\n", queue.take()!!.toString(Charsets.UTF_8))
        assertTrue(queue.take()!!.toString(Charsets.UTF_8).startsWith("H\t[MC服务器] 控制台已省略 98"))
        assertEquals("L\tline-98\n", queue.take()!!.toString(Charsets.UTF_8))
        assertEquals("L\tline-99\n", queue.take()!!.toString(Charsets.UTF_8))
        queue.close()
        assertNull(queue.take())
    }

    @Test fun byteLimitAndOversizeLinesAreBoundedAndDoNotRemoveState() {
        val queue = ConsoleOutboundQueue(maxBytes = 32, maxLogs = 100)
        queue.state("S\tRUNNING\n".toByteArray())
        repeat(100) { queue.log("L\t0123456789\n".toByteArray()) }
        queue.log(ByteArray(100_000))
        assertEquals("S\tRUNNING\n", queue.take()!!.toString(Charsets.UTF_8))
        assertTrue(queue.take()!!.toString(Charsets.UTF_8).startsWith("H\t"))
        assertEquals("L\t0123456789\n", queue.take()!!.toString(Charsets.UTF_8))
        queue.close()
        assertNull(queue.take())
    }

    @Test fun blockedTransportDoesNotBlockPublishingAndCloseWakesWaitingSender() {
        val queue = ConsoleOutboundQueue(maxBytes = 1024, maxLogs = 4)
        val executor = Executors.newFixedThreadPool(2)
        val sending = CountDownLatch(1)
        val releaseSocket = CountDownLatch(1)
        try {
            queue.log("L\tfirst\n".toByteArray())
            val writer = executor.submit {
                assertNotNull(queue.take())
                sending.countDown()
                releaseSocket.await()
            }
            assertTrue(sending.await(1, TimeUnit.SECONDS))
            val producer = executor.submit { repeat(20000) { queue.log("L\tline-$it\n".toByteArray()) }; queue.state("S\tRUNNING\n".toByteArray()) }
            producer.get(3, TimeUnit.SECONDS)
            queue.close()
            releaseSocket.countDown()
            writer.get(1, TimeUnit.SECONDS)
            val empty = ConsoleOutboundQueue()
            val waiter = executor.submit<ByteArray?> { empty.take() }
            empty.close()
            assertNull(waiter.get(1, TimeUnit.SECONDS))
        } finally { releaseSocket.countDown(); executor.shutdownNow() }
    }

    @Test fun slowDisplayCannotConsumeLiveSaveReplyAndReplayCannotImpersonateOne() = runBlocking {
        val events = ConsoleLogEvents(capacity = 2)
        val blockDisplay = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val subscriber = launch(start = CoroutineStart.UNDISPATCHED) { events.lines.collect { entered.complete(Unit); blockDisplay.await() } }
        try {
            events.publish("first", live = true)
            entered.await()
            val reply = events.expect(Regex("Saved the game"))
            events.publish("Saved the game", live = false)
            assertFalse(reply.isCompleted)
            repeat(20000) { events.publish("log-$it", live = true) }
            events.publish("Saved the game", live = true)
            repeat(20000) { events.publish("later-$it", live = true) }
            assertEquals("Saved the game", withTimeout(1000) { reply.await() })
        } finally { blockDisplay.complete(Unit); subscriber.cancelAndJoin() }
    }

    @Test fun disconnectedOrCancelledReplyCannotLeakIntoTheNextRequest() = runBlocking {
        val events = ConsoleLogEvents(capacity = 2)
        val stale = events.expect(Regex("Saved the game"))
        stale.cancel()
        val pending = events.expect(Regex("Saved the game"))
        events.disconnected()
        assertTrue(pending.isCompleted)
        assertTrue(pending.isCancelled)
        val next = events.expect(Regex("Saved the game"))
        events.publish("Saved the game", live = true)
        assertEquals("Saved the game", next.await())
        assertTrue(stale.isCancelled)
    }

    @Test fun oldConnectionCannotCompleteOrCancelANewConnectionsConfirmation() = runBlocking {
        val events = ConsoleLogEvents(capacity = 2)
        val old = Any()
        val current = Any()
        val pending = events.expect(Regex("Saved the game"), current)
        events.publish("Saved the game", live = true, connection = old)
        events.disconnected(old)
        assertFalse(pending.isCompleted)
        events.publish("Saved the game", live = true, connection = current)
        assertEquals("Saved the game", pending.await())
    }
}
