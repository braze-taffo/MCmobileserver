package com.mcmobile.server.core.files

import com.mcmobile.server.core.CoreJarInspector
import com.mcmobile.server.core.McVersions
import com.mcmobile.server.core.launch.LaunchArgfiles
import com.mcmobile.server.core.launch.ManifestReader
import com.mcmobile.server.data.ServerInstance
import com.mcmobile.server.data.ServerType
import java.io.File
import java.util.jar.JarFile

object ServerPack {
    data class Detection(
        val directory: File,
        val type: ServerType?,
        val mcVersion: String?,
        val javaMajor: Int?,
        val coreVersion: String?,
        val jars: List<String>,
        val launchJar: String?,
        val argsFile: String?,
        val notes: List<String>,
        val jvmArgs: List<String> = emptyList(),
        val programArgs: List<String> = listOf("nogui"),
    )

    fun findRoots(directory: File): List<File> {
        val ops = ManagedFiles(directory)
        fun scan(dir: File, depth: Int): List<File> {
            ops.checked(dir, true)
            val files = dir.listFiles().orEmpty()
            if (files.any { it.isFile && it.extension.equals("jar", true) } ||
                File(dir, "libraries/net/neoforged/neoforge").isDirectory ||
                File(dir, "libraries/net/minecraftforge/forge").isDirectory) return listOf(dir)
            if (depth >= 4) return emptyList()
            return files.filter { it.isDirectory && it.name !in setOf("mods", "plugins", "world", "config", "__MACOSX") }
                .flatMap { scan(it, depth + 1) }
        }
        return scan(directory, 0)
    }

    fun inspect(directory: File): Detection {
        val ops = ManagedFiles(directory)
        val jars = directory.listFiles().orEmpty().filter { it.isFile && it.extension.equals("jar", true) }
        jars.forEach { ops.checked(it) }
        val argFile = LaunchArgfiles.findUnixArgsFile(directory)?.also { ops.checked(it) }
        val runnable = jars.filter { jar ->
            ManifestReader.readMainClass(jar)?.let { !it.contains("installer", true) && !it.contains("ServerStarter", true) } == true &&
                runCatching { JarFile(jar).use { it.getJarEntry("install_profile.json") == null } }.getOrDefault(false)
        }
        val declared = runnable.mapNotNull { jar -> CoreJarInspector.detect(jar)?.let { jar to it } }
        // Read a literal Java invocation; never execute shell/batch code or expand its variables.
        val script = directory.listFiles().orEmpty().filter { it.isFile && it.extension.lowercase() in setOf("sh", "bat", "cmd") && it.length() <= 262144 }
            .firstNotNullOfOrNull { file -> file.useLines { lines -> lines.firstNotNullOfOrNull { line ->
                val tokens = runCatching { LaunchArgfiles.tokenize(line.trim().removePrefix("@")) }.getOrNull() ?: return@firstNotNullOfOrNull null
                val java = tokens.indexOfFirst { it.replace('\\', '/').substringAfterLast('/').lowercase() in setOf("java", "java.exe") }
                val jarIndex = tokens.indexOf("-jar")
                if (java < 0 || jarIndex <= java || jarIndex + 1 >= tokens.size) return@firstNotNullOfOrNull null
                val entry = tokens[jarIndex + 1].replace('\\', '/').removePrefix("./")
                val opts = tokens.subList(java + 1, jarIndex)
                val args = tokens.drop(jarIndex + 2).filterNot { it in setOf("$" + "@", "%*") }
                if (entry !in runnable.map { it.name } || (opts + args).any { it.any { c -> c in "$%&|<>;`" } }) return@firstNotNullOfOrNull null
                Triple(entry, opts, args)
            } } }
        val preferred = when {
            argFile != null -> null
            script != null -> runnable.first { it.name == script.first }
            runnable.any { it.name == "fabric-server-launch.jar" } -> runnable.first { it.name == "fabric-server-launch.jar" }
            declared.any { it.second.kind == CoreJarInspector.Kind.FABRIC } -> declared.first { it.second.kind == CoreJarInspector.Kind.FABRIC }.first
            runnable.size == 1 -> runnable.single()
            else -> null
        }
        val info = preferred?.let { CoreJarInspector.detect(it) }
        val path = argFile?.relativeTo(directory)?.invariantSeparatorsPath
        val type = when {
            path?.contains("net/neoforged/") == true -> ServerType.NEOFORGE
            path?.contains("net/minecraftforge/") == true -> ServerType.FORGE
            info?.kind != null -> ServerType.valueOf(info.kind.name)
            preferred?.name?.contains("fabric", true) == true -> ServerType.FABRIC
            preferred?.name?.contains("forge", true) == true -> ServerType.FORGE
            preferred?.name?.contains("paper", true) == true -> ServerType.PAPER
            preferred?.name?.contains("folia", true) == true -> ServerType.FOLIA
            preferred != null -> ServerType.VANILLA
            else -> null
        }
        val core = argFile?.parentFile?.name ?: info?.coreVersion
        val mc = info?.mcVersion ?: when (type) {
            ServerType.NEOFORGE -> core?.let { McVersions.mcVersionOfNeoForge(it) }
            ServerType.FORGE -> core?.substringBefore('-')?.takeIf { it.matches(Regex("1\\.\\d+(\\.\\d+)?")) }
            else -> null
        } ?: jars.firstNotNullOfOrNull { CoreJarInspector.detect(it)?.mcVersion }
            ?: jars.firstNotNullOfOrNull { Regex("(?<!\\d)(1\\.\\d{1,2}(?:\\.\\d{1,2})?)(?!\\d)").find(it.name)?.value }
        val notes = buildList {
            if (argFile == null && preferred == null) add(if (runnable.isEmpty()) "未找到可直接启动的入口；安装器和下载引导器不属于完整服务端包" else "发现多个启动 JAR，请手动选择实际入口")
            if (mc == null) add("无法确定 Minecraft 版本，请手动填写")
            if (type == null) add("无法确定服务端类型，请手动选择")
            if (argFile != null) runCatching { LaunchArgfiles.parse(directory) }.exceptionOrNull()?.let { add("参数文件解析失败：${it.message}") }
            if (script != null) add("已读取启动脚本的 Java 参数；请核对下方结果，脚本中的其他命令不会执行")
        }
        return Detection(directory, type, mc, info?.requiredJava?.let { McVersions.bundledJavaMajorFor(it) }
            ?: mc?.let { McVersions.bundledJavaMajor(it) }, core,
            runnable.map { it.name }, preferred?.name, path, notes,
            if (argFile == null) script?.second.orEmpty().filterNot { it.startsWith("-Xm") } else emptyList(),
            if (argFile == null && script != null) script.third else listOf("nogui"))
    }

    fun verify(instance: ServerInstance, directory: File) {
        val ops = ManagedFiles(directory)
        require(instance.mcVersion.isNotBlank()) { "请填写 Minecraft 版本" }
        if (instance.launchArgsFile != null) {
            val selected = ops.checked(File(directory, instance.launchArgsFile))
            val parsed = LaunchArgfiles.parse(directory, selected)
            require(parsed.classpathEntries.isNotEmpty()) { "启动参数没有 classpath / module-path" }
            parsed.classpathEntries.filter { it.isNotBlank() }.forEach { relative ->
                val file = ops.checked(File(directory, relative.removeSuffix("/*")))
                require(file.exists()) { "缺少依赖：$relative；请导入已安装完整的服务端包" }
            }
        } else if (instance.launchMainClass != null) {
            require(instance.launchClasspath.isNotEmpty()) { "请设置主类对应的 classpath" }
            instance.launchClasspath.forEach { require(ops.checked(File(directory, it.removeSuffix("/*"))).exists()) { "缺少依赖：$it" } }
        } else {
            val path = instance.launchJar ?: error("请选择启动 JAR 或参数文件")
            val jar = ops.checked(File(directory, path))
            require(jar.isFile && ManifestReader.readMainClass(jar) != null) { "启动 JAR 不存在或缺少 Main-Class" }
            JarFile(jar).use { require(it.getJarEntry("install_profile.json") == null) { "这是安装器，请导入已安装完整的服务端包" } }
            val main = ManifestReader.readMainClass(jar)
            if (main in setOf("net.fabricmc.installer.ServerLauncher", "net.fabricmc.loader.impl.launch.server.FabricServerLauncher", "net.fabricmc.loader.launch.server.FabricServerLauncher")) {
                val properties = java.util.Properties()
                File(directory, "fabric-server-launcher.properties").takeIf { it.isFile }?.inputStream()?.use { properties.load(it) }
                val server = ops.checked(File(directory, properties.getProperty("serverJar", "server.jar")))
                require(server.isFile) { "Fabric 包缺少原版核心 ${server.name}；请导入已安装完整的服务端包" }
            }
            ManifestReader.readClassPath(jar).forEach { relative ->
                require(ops.checked(File(jar.parentFile, relative)).exists()) { "缺少 JAR 依赖：$relative" }
            }
        }
    }

    fun copy(source: File, destination: File, progress: (ManagedFiles.Progress) -> Unit = {}) {
        require(!destination.canonicalFile.toPath().startsWith(source.canonicalFile.toPath())) { "不能复制到源目录内" }
        require(!destination.exists() || destination.listFiles().isNullOrEmpty()) { "目标目录必须为空" }
        destination.mkdirs()
        val ops = ManagedFiles(source)
        var bytes = 0L
        var count = 0
        try {
            fun copyFile(file: File) {
                ops.checked(file, true)
                if (file.name == ".mcs-instance.json") return
                val relative = file.relativeTo(source).invariantSeparatorsPath
                val target = File(destination, relative)
                if (file.isDirectory) {
                    target.mkdirs()
                    file.listFiles().orEmpty().forEach(::copyFile)
                } else {
                    file.inputStream().use { input -> target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            output.write(buffer, 0, n); bytes += n
                            progress(ManagedFiles.Progress(count, bytes, relative))
                        }
                    } }
                    count++
                }
            }
            copyFile(source)
        } catch (t: Throwable) { destination.deleteRecursively(); throw t }
    }
}
