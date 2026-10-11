package com.mcmobile.server.core.manage

import com.mcmobile.server.core.files.ManagedFiles
import com.mcmobile.server.data.ServerType
import java.io.File
import java.nio.file.Files
import java.util.jar.JarFile

/** Downloads are fully verified before any installed JAR is replaced. */
object AddonInstall {
    fun install(root: File, type: ServerType, versions: List<Addons.Version>, previous: File?,
                history: File, progress: (String) -> Unit) {
        val staging = Files.createTempDirectory(root.parentFile!!.toPath(), ".addon-download-").toFile()
        val rollback = File(history, "addons-${System.currentTimeMillis()}")
        val destination = ManagedFiles(root).child(root, Addons.directory(type)).apply { mkdirs() }
        var committed = false
        val applied = mutableListOf<File>()
        val moved = mutableListOf<Pair<File, File>>()
        try {
            val downloaded = versions.map { version -> Addons.download(version, staging) { bytes, total ->
                progress("${version.name}：${bytes / 1024} KB" + if (total > 0) " / ${total / 1024} KB" else "")
            } }
            require(downloaded.map { it.name }.distinct().size == downloaded.size) { "依赖使用了重复文件名，取消安装" }
            downloaded.forEach { JarFile(it).use { jar -> require(jar.entries().hasMoreElements()) { "下载的 JAR 为空" } } }
            val existing = Addons.local(root, type)
            // Metadata IDs identify renamed dependency JARs, avoiding duplicate versions.
            val stagingRoot = File(staging, "inspect").apply { mkdir() }
            val stagingDir = File(stagingRoot, Addons.directory(type)).apply { mkdir() }
            downloaded.forEach { it.copyTo(File(stagingDir, it.name)) }
            val newMetadata = Addons.local(stagingRoot, type).associateBy { it.file.name }
            val targets = downloaded.map { file ->
                val id = newMetadata[file.name]?.id
                val disabled = (previous?.name?.endsWith(".disabled") == true && file == downloaded.last()) ||
                    existing.any { !it.enabled && ((id != null && it.id == id) || it.file.name.removeSuffix(".disabled") == file.name) }
                file to ManagedFiles(root).child(destination, file.name + if (disabled) ".disabled" else "")
            }
            val old = linkedSetOf<File>()
            if (previous != null) old += ManagedFiles(root).checked(previous)
            targets.forEach { (file, target) ->
                if (target.exists()) old += target
                val id = newMetadata[file.name]?.id
                if (id != null) old += existing.filter { it.id == id }.map { it.file }
            }
            rollback.mkdirs()
            old.forEach { file ->
                val stored = File(rollback, file.name)
                require(!stored.exists()) { "旧版本备份文件名冲突" }
                Files.move(file.toPath(), stored.toPath()); moved += stored to file
            }
            targets.forEach { (file, target) -> Files.move(file.toPath(), target.toPath()); applied += target }
            committed = true
        } catch (t: Throwable) {
            var rollbackFailure: Throwable? = null
            applied.forEach { runCatching { Files.deleteIfExists(it.toPath()) }.onFailure { rollbackFailure = it } }
            moved.reversed().forEach { (stored, original) -> runCatching { Files.move(stored.toPath(), original.toPath()) }.onFailure { rollbackFailure = it } }
            if (rollbackFailure != null) throw IllegalStateException("安装失败；部分旧文件保留在 ${rollback.absolutePath}，请手动恢复", t)
            throw t
        } finally {
            staging.deleteRecursively()
            // Committed updates retain their old JARs for manual rollback.
            if (!committed && moved.all { it.second.exists() }) rollback.deleteRecursively()
        }
    }
}
