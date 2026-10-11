package com.mcmobile.server.core.manage

import com.mcmobile.server.core.console.ConsoleSession
import com.mcmobile.server.core.files.ManagedFiles
import com.mcmobile.server.data.ServerInstance
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

object LiveBackup {
    private val mutex = Mutex()
    private suspend fun acknowledged(command: String, pattern: Regex) = ConsoleSession.commandReply(command, pattern)
    suspend fun create(instance: ServerInstance, root: File, directory: File, full: Boolean,
                       progress: (ManagedFiles.Progress) -> Unit = {}): File = mutex.withLock {
        val active = ConsoleSession.activeServer(attempts = 1)
        val live = active?.instanceId == instance.id
        if (!live) return@withLock withContext(Dispatchers.IO) { Backups.create(root, directory, full, progress) }
        check(active?.status == "RUNNING") { "服务器尚未运行就绪，暂不能备份" }
        var enableSaving = true
        try {
            val reply = acknowledged("save-off", Regex("(?:saving.*(?:disabled|off)|already.*(?:disabled|off))", RegexOption.IGNORE_CASE))
            enableSaving = !reply.contains("already", true)
            acknowledged(if (instance.javaMajor == 8) "save-all" else "save-all flush", Regex("(?:Saved the game|Saved.*world|Saved.*chunks)", RegexOption.IGNORE_CASE))
            require(ConsoleSession.state.value.instanceId == instance.id && ConsoleSession.isConnected()) { "服务器在备份期间退出" }
            val archive = withContext(Dispatchers.IO) { Backups.create(root, directory, full, progress) }
            if (!ConsoleSession.isConnected() || ConsoleSession.state.value.instanceId != instance.id || ConsoleSession.state.value.serverStatus != "RUNNING") {
                archive.delete(); File(archive.parentFile, "${archive.name}.json").delete()
                error("服务器在备份期间停止，已取消不完整的备份")
            }
            archive
        } finally {
            if (enableSaving && ConsoleSession.state.value.instanceId == instance.id)
                withContext(NonCancellable) { ConsoleSession.sendCommand("save-on") }
        }
    }
}
