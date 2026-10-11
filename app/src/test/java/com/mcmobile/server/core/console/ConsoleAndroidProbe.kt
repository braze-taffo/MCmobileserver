package com.mcmobile.server.core.console

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlin.system.exitProcess

/** Standalone ART regression entry; compiled with tests, never packaged in APKs. */
object ConsoleAndroidProbe {
    @JvmStatic fun main(args: Array<String>) {
        try {
            runBlocking { verify() }
            println("ANDROID_SESSION_PROBE_PASSED")
            exitProcess(0)
        } catch (error: Throwable) {
            error.printStackTrace(System.out)
            exitProcess(1)
        }
    }

    private suspend fun verify() = coroutineScope {
        lateinit var bridge: ConsoleBridgeServer
        val commands = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        bridge = ConsoleBridgeServer(ConsoleSocketIdentity.name, null, { command ->
            if (command == "save-all flush") commands.launch {
                delay(500)
                bridge.appendLine("Saved the game")
            }
        }, {})
        // An old save confirmation must be displayed as history, never used
        // to confirm the next request now that state precedes replay.
        bridge.appendLine("Saved the game")
        repeat(1000) { bridge.appendLine("historical-$it") }
        bridge.broadcastState("""{"status":"RUNNING","name":"fixture","instanceId":"audit-fixture"}""")
        bridge.start()
        val blocked = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val display = launch(start = CoroutineStart.UNDISPATCHED) {
            ConsoleSession.lines.collect { entered.complete(Unit); blocked.await() }
        }
        try {
            val active = ConsoleSession.activeServer(attempts = 1, timeoutMs = 3000)
            check(active?.instanceId == "audit-fixture") { "Current state was not received" }
            entered.await()
            val reply = async { ConsoleSession.commandReply("save-all flush", Regex("Saved the game"), 10000) }
            delay(100)
            check(!reply.isCompleted) { "History impersonated the new live save response" }
            // Flood both the server display queue and SharedFlow while the
            // subscribed renderer is deliberately suspended.
            withContext(Dispatchers.IO) { repeat(20000) { bridge.appendLine("live-$it " + "x".repeat(120)) } }
            check(withTimeout(10000) { reply.await() } == "Saved the game")
            check(ConsoleSession.state.value.serverStatus == "RUNNING")
            println("SESSION replayCannotAck=true stalledDisplayCannotBlockReply=true state=RUNNING")
        } finally {
            blocked.complete(Unit)
            display.cancelAndJoin()
            commands.cancel()
            bridge.close()
        }
    }
}
