package com.mcmobile.server.core.jre

import com.mcmobile.server.core.files.ManagedFiles
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object RuntimeDownload {
    data class Artifact(val major: Int, val abi: String, val sha256: String) {
        val url = "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre$major/jre$major-android-${if (abi == "arm64-v8a") "arm64" else "x86_64"}.tar.xz"
    }
    val artifacts = listOf(
        Artifact(8, "arm64-v8a", "9a59124d9791957d55c68be664ab76831f336cf2e1e1cd4414220c6fdbf0e06d"),
        Artifact(8, "x86_64", "b1fbcef4965c17925894febe8216d089c6dd47b37950b5f945a89616443c1d0e"),
        Artifact(17, "arm64-v8a", "e162c860fe05ee4a4e4af7606437419879f6c748386a7b09fa77d10db6a64091"),
        Artifact(17, "x86_64", "893e27d2aed8b40407f29fe939e2a0f193e5d55f72892a303db634b0808a2b61"),
    )
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    fun prepare(major: Int, abi: String, destination: File, cache: File, progress: (Float, String) -> Unit) {
        val artifact = artifacts.firstOrNull { it.major == major && it.abi == abi } ?: error("没有匹配的 Java 运行时")
        val archive = File(cache, "jre-$major-$abi.tar.xz")
        fun digest(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        cache.mkdirs()
        if (!archive.isFile || digest(archive) != artifact.sha256) {
            val part = File(cache, "${archive.name}.${java.util.UUID.randomUUID()}.part")
            try {
                client.newCall(Request.Builder().url(artifact.url).header("User-Agent", "MC-Mobile-Server/Android").build()).execute().use { response ->
                    check(response.isSuccessful) { "Java 下载失败：HTTP ${response.code}" }
                    val body = response.body ?: error("下载响应为空")
                    val total = body.contentLength()
                    var bytes = 0L
                    body.byteStream().use { input -> part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            output.write(buffer, 0, n); bytes += n
                            require(bytes <= 256L * 1024 * 1024) { "运行时下载大小异常" }
                            progress(if (total > 0) (bytes.toFloat() / total * .65f).coerceAtMost(.65f) else 0f, "下载 Java $major：${bytes / 1048576} MB")
                        }
                    } }
                }
                check(digest(part) == artifact.sha256) { "Java 下载校验失败，请重试" }
                ManagedFiles.replace(part, archive)
            } finally { part.delete() }
        }
        unpack(archive, destination, major, abi, artifact.sha256, progress)
    }

    internal fun unpack(archive: File, destination: File, major: Int, abi: String, sha256: String,
                        progress: (Float, String) -> Unit = { _, _ -> }) {
        destination.mkdirs()
        val ops = ManagedFiles(destination)
        val links = mutableListOf<Pair<File, String>>()
        var total = 0L
        var count = 0
        XZInputStream(archive.inputStream().buffered()).use { xz -> TarArchiveInputStream(xz).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val path = entry.name.removePrefix("./").trimEnd('/')
                if (path.isEmpty()) continue
                require(!path.startsWith('/') && path.split('/').none { it == ".." || ':' in it }) { "运行时包含非法路径" }
                if (path == "bin" || path.startsWith("bin/") || path == "man" || path.startsWith("man/")) continue
                val target = ops.checked(File(destination, path))
                require(++count <= 30_000) { "运行时文件过多" }
                if (entry.isDirectory) target.mkdirs()
                else if (entry.isSymbolicLink || entry.isLink) {
                    val source = if (entry.isLink) File(destination, entry.linkName.removePrefix("./")) else File(target.parentFile, entry.linkName)
                    links += target to ops.checked(source).relativeTo(destination).invariantSeparatorsPath
                }
                else {
                    require(entry.isFile) { "不支持的运行时条目" }
                    target.parentFile!!.mkdirs()
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = tar.read(buffer); if (n < 0) break
                            total += n; require(total <= 1024L * 1024 * 1024) { "运行时解压大小异常" }
                            output.write(buffer, 0, n)
                        }
                    }
                    target.setReadable(true, false)
                    if (target.extension == "so") target.setExecutable(true, false)
                }
                progress(.65f + (total.toFloat() / (256 * 1048576)).coerceAtMost(.3f), "解压 Java $major：$path")
            }
        } }
        // Materialize archive links as regular files after validating both ends.
        val pending = links.toMutableList()
        repeat(links.size + 1) {
            pending.toList().forEach { (target, link) ->
                val source = ops.checked(File(destination, link))
                if (source.isFile) { target.parentFile!!.mkdirs(); source.copyTo(target, true); pending.remove(target to link) }
            }
        }
        require(pending.isEmpty()) { "运行时包含无法解析的链接" }
        val libjvm = destination.walkTopDown().firstOrNull { it.isFile && it.name == "libjvm.so" }
            ?: error("运行时缺少 libjvm.so")
        if (major == 8) {
            val javaLib = libjvm.parentFile!!.parentFile!!
            // Java 8 uses lib/<arch>/server and rt.jar; JNI library lookup needs that architecture directory.
            File(destination, ".mcs-lib-dir").writeText(javaLib.relativeTo(destination).invariantSeparatorsPath)
            require(destination.walkTopDown().any { it.name == "rt.jar" }) { "Java 8 缺少 rt.jar" }
        } else require(File(destination, "lib/modules").isFile) { "Java $major 缺少 modules" }
        File(destination, ".mcs-libjvm").writeText(libjvm.relativeTo(destination).invariantSeparatorsPath)
        File(destination, ".mcs-extracted").writeText("abi=$abi java=$major sha256=$sha256")
    }
}
