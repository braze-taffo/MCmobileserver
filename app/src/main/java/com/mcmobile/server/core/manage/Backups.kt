package com.mcmobile.server.core.manage

import com.mcmobile.server.core.files.ManagedFiles
import com.mcmobile.server.core.props.ServerProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipFile

object Backups {
    @Serializable data class Info(val kind: String, val level: String, val created: Long)
    data class Preview(val staging: File, val info: Info, val replacements: List<String>)
    private val json = Json { ignoreUnknownKeys = true }
    fun directory(filesDir: File, instance: com.mcmobile.server.data.ServerInstance, root: File): File =
        instance.backupRoot?.let(::File) ?: if (instance.storage == com.mcmobile.server.data.StorageKind.EXTERNAL)
            File(root.parentFile, ".mcs-backups/${instance.id}") else File(filesDir, "backups/${instance.id}")

    fun worldName(root: File): String {
        val name = ServerProperties.load(File(root, "server.properties"))?.get("level-name") ?: "world"
        ManagedFiles(root).child(root, name)
        return name
    }

    fun worlds(root: File): List<File> = root.listFiles().orEmpty().filter {
        it.isDirectory && !Files.isSymbolicLink(it.toPath()) && File(it, "level.dat").isFile
    }

    fun create(root: File, directory: File, full: Boolean, progress: (ManagedFiles.Progress) -> Unit = {}): File {
        require(!directory.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "备份目录不能在服务端目录内" }
        directory.mkdirs()
        val stamp = System.currentTimeMillis()
        val archive = File(directory, "${if (full) "server" else "world"}-$stamp-${UUID.randomUUID().toString().take(4)}.zip")
        val level = worldName(root)
        val sources = if (full) listOf(root) else listOf(level, "${level}_nether", "${level}_the_end")
            .map { ManagedFiles(root).child(root, it) }.filter { it.isDirectory }
        require(full || sources.isNotEmpty()) { "没有找到当前世界存档" }
        try {
            ManagedFiles(root).compress(sources, archive, progress)
            // A sidecar preserves the ZIP's ordinary, portable server directory structure.
            File(directory, "${archive.name}.json").writeText(json.encodeToString(Info(if (full) "server" else "world", level, stamp)))
            return archive
        } catch (t: Throwable) { archive.delete(); throw t }
    }

    fun preview(root: File, archive: File, progress: (ManagedFiles.Progress) -> Unit = {}): Preview {
        val staging = Files.createTempDirectory(root.parentFile!!.toPath(), ".restore-").toFile()
        try {
            ManagedFiles.unpack(archive, staging, progress = progress)
            File(staging, ".mcs-instance.json").delete()
            val sidecar = File(archive.parentFile, "${archive.name}.json")
            val info = if (sidecar.isFile) json.decodeFromString<Info>(sidecar.readText()) else {
                val onlyWorlds = staging.listFiles().orEmpty().all { it.isDirectory && File(it, "level.dat").isFile }
                val mainWorld = staging.listFiles().orEmpty().filter { File(it, "level.dat").isFile }
                    .sortedWith(compareBy<File> { it.name.endsWith("_nether") || it.name.endsWith("_the_end") }.thenBy { it.name.length })
                    .firstOrNull()?.name ?: worldName(root)
                Info(if (onlyWorlds) "world" else "server", mainWorld, 0)
            }
            require(info.kind in setOf("world", "server")) { "未知的备份类型" }
            ManagedFiles(root).child(root, info.level)
            val children = staging.listFiles().orEmpty()
            require(children.isNotEmpty()) { "备份内容为空" }
            if (info.kind == "world") require(children.all { it.isDirectory && File(it, "level.dat").isFile }) { "世界备份内容不完整" }
            return Preview(staging, info, children.map { it.name }.sorted())
        } catch (t: Throwable) { staging.deleteRecursively(); throw t }
    }

    /** Switch complete trees while stopped; failed switches roll back before returning. */
    fun restore(root: File, preview: Preview) {
        val parent = root.parentFile!!
        val rollback = Files.createTempDirectory(parent.toPath(), ".rollback-").toFile()
        var safeToClean = false
        try {
            if (preview.info.kind == "server") {
                val marker = File(root, ".mcs-instance.json")
                if (marker.isFile) marker.copyTo(File(preview.staging, marker.name), true)
                val previous = File(rollback, "server")
                Files.move(root.toPath(), previous.toPath())
                try { Files.move(preview.staging.toPath(), root.toPath()); safeToClean = true }
                catch (t: Throwable) { Files.move(previous.toPath(), root.toPath()); safeToClean = true; throw t }
            } else {
                val applied = mutableListOf<File>()
                val removed = mutableListOf<Pair<File, File>>()
                try {
                    preview.staging.listFiles().orEmpty().forEach { world ->
                        val target = ManagedFiles(root).child(root, world.name)
                        if (target.exists()) {
                            val old = File(rollback, world.name)
                            Files.move(target.toPath(), old.toPath()); removed += old to target
                        }
                        Files.move(world.toPath(), target.toPath()); applied += target
                    }
                    val propsFile = File(root, "server.properties")
                    val props = ServerProperties.load(propsFile) ?: ServerProperties.default()
                    val requested = preview.info.level.takeIf { File(root, "$it/level.dat").isFile }
                        ?: applied.first { File(it, "level.dat").isFile }.name
                    props["level-name"] = requested
                    props.toText().byteInputStream().use { ManagedFiles(root).write(propsFile, it) }
                    safeToClean = true
                } catch (t: Throwable) {
                    applied.forEach { it.deleteRecursively() }
                    removed.reversed().forEach { (old, target) -> Files.move(old.toPath(), target.toPath()) }
                    safeToClean = true
                    throw t
                }
            }
        } catch (t: Throwable) {
            if (!safeToClean && rollback.listFiles()?.isNotEmpty() == true)
                throw IllegalStateException("恢复失败；原文件保留在 ${rollback.absolutePath}，请勿删除该目录", t)
            throw t
        } finally { if (safeToClean || rollback.listFiles().isNullOrEmpty()) rollback.deleteRecursively(); preview.staging.deleteRecursively() }
    }
}
