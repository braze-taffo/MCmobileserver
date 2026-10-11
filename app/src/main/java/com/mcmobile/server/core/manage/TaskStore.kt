package com.mcmobile.server.core.manage

import com.mcmobile.server.data.InstanceManifest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ServerTask(val id: String, val instanceId: String, val name: String,
    val action: String, val intervalMinutes: Int, val command: String = "", val enabled: Boolean = true)

class TaskStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun load(): List<ServerTask> = if (file.isFile) json.decodeFromString(file.readText()) else emptyList()
    @Synchronized fun save(tasks: List<ServerTask>) {
        require(tasks.map { it.id }.distinct().size == tasks.size) { "任务 ID 重复" }
        tasks.forEach(::validate)
        InstanceManifest.writeAtomically(file, json.encodeToString(tasks))
    }
    companion object {
        val ACTIONS = listOf("BACKUP_WORLD", "BACKUP_FULL", "RESTART", "COMMAND")
        fun validate(task: ServerTask) {
            require(task.action in ACTIONS) { "不支持的任务类型" }
            require(task.intervalMinutes in 1..525600) { "间隔需为 1 分钟到 1 年" }
            require(task.name.isNotBlank() && task.instanceId.isNotBlank()) { "任务名称和服务器不能为空" }
            if (task.action == "COMMAND") require(task.command.isNotBlank() && task.command.none { it == '\n' || it == '\r' || it == '\u0000' }) { "请输入单行命令" }
        }
    }
}
