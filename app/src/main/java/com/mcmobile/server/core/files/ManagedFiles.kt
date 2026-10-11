package com.mcmobile.server.core.files

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Shared by the browser, pack importer and backups. Never follows symbolic links. */
class ManagedFiles(val root: File) {
    enum class Conflict { SKIP, OVERWRITE, KEEP_BOTH }
    data class Progress(val files: Int, val bytes: Long, val path: String)
    data class Result(val written: Int, val skipped: Int)

    fun checked(file: File, allowRoot: Boolean = false): File {
        val base = root.canonicalFile.toPath()
        val path = file.canonicalFile.toPath()
        require(path.startsWith(base) && (allowRoot || path != base)) { "文件路径超出服务器目录" }
        var cursor: File? = file.absoluteFile
        while (cursor != null && cursor.toPath().startsWith(root.absoluteFile.toPath())) {
            require(!Files.isSymbolicLink(cursor.toPath())) { "不支持符号链接：${file.name}" }
            if (cursor == root.absoluteFile) break
            cursor = cursor.parentFile
        }
        return file
    }

    fun child(directory: File, name: String): File {
        require(name.isNotBlank() && name !in listOf(".", "..") &&
            name.none { it in "/\\:\u0000\r\n" }) { "无效的文件名" }
        checked(directory, true)
        return checked(File(directory, name))
    }

    private fun mutable(file: File): File {
        checked(file)
        require(file.name != ".mcs-instance.json") { "实例标记由应用管理" }
        return file
    }

    fun create(directory: File, name: String, folder: Boolean) {
        val file = mutable(child(directory, name))
        require(!file.exists()) { "已有同名文件" }
        if (folder) check(file.mkdir()) { "无法创建文件夹" }
        else check(file.createNewFile()) { "无法创建文件" }
    }

    fun rename(source: File, name: String): File {
        mutable(source)
        val target = mutable(child(source.parentFile!!, name))
        require(!target.exists()) { "已有同名文件" }
        Files.move(source.toPath(), target.toPath())
        return target
    }

    fun delete(file: File) {
        mutable(file)
        // Walk without following links, including links within a selected folder.
        Files.walkFileTree(file.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(path: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                Files.delete(path)
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(path: java.nio.file.Path, error: java.io.IOException?): java.nio.file.FileVisitResult {
                if (error != null) throw error
                Files.delete(path)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

    fun write(file: File, input: InputStream, conflict: Conflict = Conflict.OVERWRITE): Boolean {
        mutable(file)
        if (file.exists() && conflict == Conflict.SKIP) return false
        val target = if (file.exists() && conflict == Conflict.KEEP_BOTH) unique(file) else file
        require(!target.isDirectory) { "目标是文件夹：${target.name}" }
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "无法创建目录" }
        val temp = File.createTempFile(".mcs-write-", ".tmp", target.parentFile)
        try {
            temp.outputStream().use { input.copyTo(it) }
            replace(temp, target)
        } finally { temp.delete() }
        return true
    }

    fun readText(file: File, maxBytes: Long = 2L * 1024 * 1024): String {
        checked(file)
        require(file.isFile && file.length() <= maxBytes) { "仅支持编辑 2 MB 以内的文本文件" }
        val bytes = file.readBytes()
        require(bytes.none { it == 0.toByte() }) { "这是二进制文件，请使用导出功能" }
        return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }

    fun saveText(file: File, text: String, expectedModified: Long, expectedSize: Long) {
        require(file.lastModified() == expectedModified && file.length() == expectedSize) {
            "文件在编辑期间已被修改，请重新加载后再保存"
        }
        text.byteInputStream().use { write(file, it) }
    }

    fun transfer(sources: List<File>, destination: File, move: Boolean, conflict: Conflict,
                 progress: (Progress) -> Unit = {}): Result {
        checked(destination, true)
        require(destination.isDirectory) { "目标文件夹不存在" }
        require(sources.distinct().size == sources.size) { "存在重复选择" }
        sources.forEach { source ->
            mutable(source)
            val target = child(destination, source.name)
            require(target.canonicalFile != source.canonicalFile) { "源与目标相同" }
            require(!destination.canonicalFile.toPath().startsWith(source.canonicalFile.toPath())) { "不能复制到自身或子目录" }
        }
        var written = 0
        var skipped = 0
        var bytes = 0L
        fun copy(source: File, requested: File) {
            checked(source)
            mutable(requested)
            var target = requested
            if (target.exists()) {
                if (conflict == Conflict.SKIP && !(source.isDirectory && target.isDirectory)) { skipped++; return }
                if (conflict == Conflict.KEEP_BOTH) target = unique(target)
            }
            if (source.isDirectory) {
                require(!target.exists() || target.isDirectory) { "文件与目录冲突：${target.name}" }
                check(target.isDirectory || target.mkdirs()) { "无法创建 ${target.name}" }
                source.listFiles().orEmpty().forEach { copy(it, File(target, it.name)) }
                if (move && source.listFiles().isNullOrEmpty()) Files.delete(source.toPath())
            } else {
                source.inputStream().use { write(target, it, conflict) }
                bytes += source.length()
                written++
                progress(Progress(written, bytes, target.relativeTo(root).path))
                if (move) Files.delete(source.toPath())
            }
        }
        sources.forEach { copy(it, child(destination, it.name)) }
        return Result(written, skipped)
    }

    fun compress(sources: List<File>, archive: File, progress: (Progress) -> Unit = {}) {
        require(sources.isNotEmpty()) { "请选择文件" }
        sources.forEach {
            checked(it, true)
            require(!archive.canonicalFile.toPath().startsWith(it.canonicalFile.toPath())) { "压缩包不能位于所选目录内" }
        }
        val temp = File.createTempFile(".archive-", ".zip", archive.parentFile)
        var count = 0
        var bytes = 0L
        try {
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                fun add(file: File, name: String) {
                    checked(file, true)
                    if (file.name == ".mcs-instance.json" || file.name.startsWith(".mcs-write-")) return
                    zip.putNextEntry(ZipEntry(name + if (file.isDirectory) "/" else ""))
                    if (file.isDirectory) {
                        zip.closeEntry()
                        file.listFiles().orEmpty().forEach { add(it, "$name/${it.name}") }
                    } else {
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        bytes += file.length(); count++
                        progress(Progress(count, bytes, name))
                    }
                }
                sources.forEach { source ->
                    if (source.canonicalFile == root.canonicalFile) source.listFiles().orEmpty().forEach { add(it, it.name) }
                    else add(source, source.name)
                }
            }
            replace(temp, archive)
        } finally { temp.delete() }
    }

    fun extract(archive: File, destination: File, conflict: Conflict, progress: (Progress) -> Unit = {}): Result {
        checked(destination, true)
        val staging = Files.createTempDirectory(root.parentFile!!.toPath(), ".mcs-extract-").toFile()
        try {
            unpack(archive, staging, progress = progress)
            // The destination and staging must share one confinement root for transfer.
            val common = ManagedFiles(commonParent(staging, root))
            return common.transfer(staging.listFiles().orEmpty().toList(), destination, false, conflict, progress)
        } finally { staging.deleteRecursively() }
    }

    companion object {
        private fun unique(file: File): File {
            var n = 1
            while (true) {
                val candidate = File(file.parentFile, if (file.isDirectory || file.extension.isEmpty()) "${file.name} ($n)"
                    else "${file.nameWithoutExtension} ($n).${file.extension}")
                if (!Files.exists(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)) return candidate
                n++
            }
        }

        fun replace(source: File, target: File) {
            try { Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        private fun commonParent(a: File, b: File): File {
            var parent = a.canonicalFile
            while (!b.canonicalFile.toPath().startsWith(parent.toPath())) parent = parent.parentFile!!
            return parent
        }

        fun unpack(archive: File, destination: File, maxBytes: Long = 64L * 1024 * 1024 * 1024,
                   maxEntries: Int = 200_000, progress: (Progress) -> Unit = {}) {
            require(!destination.exists() || destination.listFiles().isNullOrEmpty()) { "解压暂存目录必须为空" }
            check(destination.isDirectory || destination.mkdirs()) { "无法创建解压目录" }
            val ops = ManagedFiles(destination)
            var bytes = 0L
            var count = 0
            val seen = hashSetOf<String>()
            try {
                ZipFile(archive).use { zip ->
                    val entries = zip.entries().asSequence().take(maxEntries + 1).toList()
                    require(entries.size <= maxEntries) { "压缩包文件过多" }
                    // Validate every entry before writing, including ignored metadata paths.
                    val safe = entries.map { entry ->
                        val path = entry.name.replace('\\', '/')
                        require(!path.startsWith('/') && path.split('/').none { it == ".." || ':' in it || '\u0000' in it }) { "压缩包包含不安全路径：${entry.name}" }
                        val name = path.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
                        require(name.isNotEmpty()) { "压缩包包含空路径" }
                        require(seen.add(name.lowercase(java.util.Locale.ROOT))) { "压缩包包含重复路径：$name" }
                        entry to name
                    }
                    safe.forEach { (entry, name) ->
                        if (name.split('/').any { it == "__MACOSX" || it.startsWith("._") }) return@forEach
                        val target = ops.checked(File(destination, name))
                        if (entry.isDirectory) check(target.isDirectory || target.mkdirs()) { "无法创建 $name" }
                        else {
                            target.parentFile!!.mkdirs()
                            zip.getInputStream(entry).use { input -> target.outputStream().use { out ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    bytes += n
                                    require(bytes <= maxBytes) { "解压后超过大小上限" }
                                    out.write(buffer, 0, n)
                                    progress(Progress(count, bytes, name))
                                }
                            } }
                        }
                        count++
                        progress(Progress(count, bytes, name))
                    }
                }
            } catch (t: Throwable) { destination.deleteRecursively(); throw t }
        }
    }
}
