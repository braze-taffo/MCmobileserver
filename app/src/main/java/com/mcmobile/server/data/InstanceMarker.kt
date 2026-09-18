package com.mcmobile.server.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 实例目录里的自述文件 `.mcs-instance.json`。
 *
 * 实例清单只存在应用私有存储里，而外部目录里的实例在卸载重装（或清除数据）之后仍在磁盘上
 * 却已不在清单里——没有这个标记文件，用户只能看到一个不知属于谁、什么版本的目录。
 * 它的内容全部是最基本的身份信息，写失败不影响实例本身。
 */
@Serializable
data class InstanceMarker(
    val format: Int = CURRENT_FORMAT,
    val id: String,
    val name: String,
    val type: ServerType,
    val mcVersion: String,
    val createdMs: Long,
) {
    companion object {
        const val CURRENT_FORMAT = 1
    }
}

object InstanceMarkers {

    const val FILE_NAME = ".mcs-instance.json"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun fileOf(instanceDir: File): File = File(instanceDir, FILE_NAME)

    fun toMarker(instance: ServerInstance) = InstanceMarker(
        id = instance.id,
        name = instance.name,
        type = instance.type,
        mcVersion = instance.mcVersion,
        createdMs = instance.createdMs,
    )

    /** 返回是否写入成功；写不进去（外部目录被拔卡等）不应该让创建实例失败 */
    fun write(instanceDir: File, instance: ServerInstance): Boolean = runCatching {
        fileOf(instanceDir).writeText(json.encodeToString(InstanceMarker.serializer(), toMarker(instance)))
        true
    }.getOrDefault(false)

    /** 目录里没有标记文件或文件损坏都返回 null */
    fun read(instanceDir: File): InstanceMarker? {
        val f = fileOf(instanceDir)
        if (!f.isFile) return null
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        return runCatching { json.decodeFromString(InstanceMarker.serializer(), text) }.getOrNull()
    }
}
