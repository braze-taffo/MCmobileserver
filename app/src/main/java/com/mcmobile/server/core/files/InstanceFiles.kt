package com.mcmobile.server.core.files

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipFile

/** File operations confined to one server instance. Existing imports are never overwritten. */
class InstanceFiles(private val root: File) {
    data class Result(val imported: Int, val skipped: Int)

    private fun checked(file: File): File {
        val base = root.canonicalFile.toPath()
        val path = file.canonicalFile.toPath()
        require(path != base && path.startsWith(base)) { "文件路径超出服务器目录" }
        return file
    }

    private fun validName(name: String) {
        require(name.isNotBlank() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it == ':' || it == '\u0000' }) { "无效的文件名：$name" }
    }

    fun delete(file: File) {
        checked(file)
        Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(path)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(path: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(path)
                return FileVisitResult.CONTINUE
            }
        })
    }

    fun importFile(directory: File, name: String, input: InputStream): Result {
        validName(name)
        val target = checked(File(directory, name))
        if (Files.exists(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return Result(0, 1)
        check(directory.isDirectory || directory.mkdirs()) { "无法创建目标目录" }
        val temp = File.createTempFile(".import-", ".tmp", directory)
        try {
            temp.outputStream().use { input.copyTo(it) }
            Files.move(temp.toPath(), target.toPath())
        } finally {
            temp.delete()
        }
        return Result(1, 0)
    }

    /**
     * 导入世界存档 ZIP，解到实例根目录，返回世界文件夹名。
     *
     * 存档定位：level.dat 的直接父目录即存档根（DIM1/DIM-1 等更深的 level.dat 只是旧版的下界/末地标记，
     * 不参与定位）；ZIP 根直接放 level.dat 时用 [fallbackWorldName] 命名文件夹。存档外的兄弟文件跳过。
     * 整包先解压到实例内的隐藏暂存目录、全部校验通过后一次性改名就位：失败不留残余，已有同名文件夹时不覆盖。
     */
    fun importWorldZip(
        archive: File,
        fallbackWorldName: String,
        maxBytes: Long = 8L * 1024 * 1024 * 1024,
        maxEntries: Int = 50_000,
    ): String {
        // 上次进程中途被杀可能留下残缺暂存目录，导入前先清掉
        root.listFiles()?.forEach {
            if (it.name.startsWith(".world-import-")) it.deleteRecursively()
        }
        val staging = Files.createTempDirectory(root.toPath(), ".world-import-").toFile()
        try {
            ZipFile(archive).use { zip ->
                data class Entry(val path: String, val directory: Boolean)

                fun normalize(name: String, directory: Boolean): Entry? {
                    val p = name.replace('\\', '/')
                    require(!p.startsWith('/') && p.split('/').none { it == ".." || ':' in it || '\u0000' in it }) {
                        "ZIP 包含不安全的路径：$name"
                    }
                    val segments = p.split('/').filter { it.isNotEmpty() && it != "." }
                    if (segments.isEmpty()) return null
                    if (segments.any { it == "__MACOSX" || it.startsWith("._") }) return null
                    return Entry(segments.joinToString("/"), directory)
                }

                val entries = mutableListOf<Entry>()
                zip.entries().asSequence().forEach { e ->
                    require(entries.size + 1 <= maxEntries) { "ZIP 内文件过多（超过 $maxEntries 项）" }
                    normalize(e.name, e.isDirectory)?.let { entries += it }
                }

                val levelParents = entries
                    .filter { !it.directory && (it.path == "level.dat" || it.path.endsWith("/level.dat")) }
                    .map { if (it.path == "level.dat") "" else it.path.removeSuffix("/level.dat") }
                    .distinct()
                require(levelParents.isNotEmpty()) { "ZIP 里没有找到存档：缺少 level.dat" }
                val worldParent = if ("" in levelParents) {
                    require(levelParents.size == 1) { "ZIP 里同时存在多个存档，无法确定要导入哪个" }
                    ""
                } else {
                    // 兼容 MyWorld/level.dat + MyWorld/DIM1/level.dat：所有候选共同的最短父目录才是存档根
                    val common = levelParents.filter { parent ->
                        levelParents.all { it == parent || it.startsWith("$parent/") }
                    }
                    require(common.isNotEmpty()) { "ZIP 里同时存在多个存档，无法确定要导入哪个" }
                    common.minBy { it.length }
                }
                val prefix = if (worldParent.isEmpty()) "" else "$worldParent/"
                val worldName = if (worldParent.isEmpty()) fallbackWorldName else worldParent.substringAfterLast('/')
                validName(worldName)

                var total = 0L
                zip.entries().asSequence().forEach { e ->
                    val safe = normalize(e.name, e.isDirectory) ?: return@forEach
                    if (prefix.isNotEmpty() && !safe.path.startsWith(prefix)) return@forEach
                    val relative = safe.path.removePrefix(prefix)
                    if (relative.isEmpty()) return@forEach
                    val target = checked(File(staging, relative))
                    if (safe.directory) {
                        target.mkdirs()
                        return@forEach
                    }
                    target.parentFile?.mkdirs()
                    zip.getInputStream(e).use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                require(total <= maxBytes) { "存档解压后超过大小上限（${maxBytes / (1024 * 1024)} MB）" }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
                require(File(staging, "level.dat").isFile) { "存档缺少 level.dat" }

                val worldDir = File(root, worldName)
                require(!worldDir.exists()) { "实例里已有同名文件夹「$worldName」，如需替换请先删除旧文件夹" }
                // 暂存目录与目标同在实例根下，move 即同卷改名，瞬间完成、无部分导入状态
                Files.move(staging.toPath(), worldDir.toPath())
                return worldName
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Stage and validate the entire archive before importing any JARs. */
    fun importModsZip(archive: File, stagingParent: File, maxBytes: Long = 2L * 1024 * 1024 * 1024): Result {
        val staging = Files.createTempDirectory(stagingParent.toPath(), "mods-").toFile()
        try {
            ZipFile(archive).use { zip ->
                var total = 0L
                var entries = 0
                val names = mutableSetOf<String>()
                val iterator = zip.entries()
                while (iterator.hasMoreElements()) {
                    val entry = iterator.nextElement()
                    require(++entries <= 20000) { "ZIP 内文件过多" }
                    val path = entry.name.replace('\\', '/')
                    require(!path.startsWith('/') && path.split('/').none { it == ".." || ':' in it || '\u0000' in it }) {
                        "ZIP 包含不安全的路径：${entry.name}"
                    }
                    if (entry.isDirectory || !path.endsWith(".jar", ignoreCase = true) ||
                        path.split('/').any { it == "__MACOSX" || it.startsWith("._") }) continue
                    val name = path.substringAfterLast('/')
                    validName(name)
                    require(names.add(name.lowercase(java.util.Locale.ROOT))) { "ZIP 内存在同名 Mod：$name" }
                    zip.getInputStream(entry).use { input ->
                        File(staging, name).outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                require(total <= maxBytes) { "ZIP 解压后的 Mod 总大小超过限制" }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
            }
            val jars = staging.listFiles().orEmpty()
            require(jars.isNotEmpty()) { "ZIP 中没有找到 JAR 模组文件" }
            val mods = checked(File(root, "mods"))
            var imported = 0
            var skipped = 0
            for (jar in jars) {
                val result = jar.inputStream().use { importFile(mods, jar.name, it) }
                imported += result.imported
                skipped += result.skipped
            }
            return Result(imported, skipped)
        } finally {
            staging.deleteRecursively()
        }
    }
}
