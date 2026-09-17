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
