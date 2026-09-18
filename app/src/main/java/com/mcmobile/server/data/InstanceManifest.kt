package com.mcmobile.server.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * instances.json 的编解码与落盘（纯文件操作，不依赖 Android，便于单测）。
 *
 * 实例清单是所有存档目录的唯一索引：一旦解析失败还必须能读回来，
 * 所以这里不做"坏了就当空"的处理——损坏必须备份并让上层报出来。
 */
object InstanceManifest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val serializer = ListSerializer(ServerInstance.serializer())

    fun encode(instances: List<ServerInstance>): String = json.encodeToString(serializer, instances)

    /** 解析失败返回 null，由调用方备份原文件并上报 */
    fun decode(text: String): List<ServerInstance>? =
        runCatching { json.decodeFromString(serializer, text) }.getOrNull()

    /** 先写 .tmp 再原子替换：中途被杀也不会留下半截 JSON */
    fun writeAtomically(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text)
        try {
            Files.move(
                tmp.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            // 某些文件系统不支持 ATOMIC_MOVE，退化为普通替换
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** 把损坏清单改名备份，返回备份文件；改名失败返回 null（此时不要覆盖原文件） */
    fun backupCorrupt(target: File, stamp: Long = System.currentTimeMillis()): File? {
        val backup = File(target.parentFile, "${target.name}.corrupt-$stamp")
        return runCatching {
            if (target.renameTo(backup)) backup
            else target.copyTo(backup, overwrite = true).takeIf { target.delete() }
        }.getOrNull()
    }
}
