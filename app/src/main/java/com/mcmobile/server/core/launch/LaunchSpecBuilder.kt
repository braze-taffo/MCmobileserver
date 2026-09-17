package com.mcmobile.server.core.launch

import com.mcmobile.server.core.jre.JreManager
import com.mcmobile.server.data.ServerInstance
import com.mcmobile.server.data.ServerType
import java.io.File

/**
 * 由实例生成可执行的 LaunchSpec。
 * - Vanilla/Paper/Fabric：server.jar 的 Manifest Main-Class
 * - Forge/NeoForge：解析 unix_args.txt / user_jvm_args.txt
 */
object LaunchSpecBuilder {

    data class Result(
        val spec: LaunchSpec? = null,
        val error: String? = null,
    )

    fun build(instance: ServerInstance, instanceDir: File, javaHome: File): Result {
        return try {
            Result(spec = buildOrThrow(instance, instanceDir, javaHome))
        } catch (t: Throwable) {
            Result(error = t.message ?: t.toString())
        }
    }

    private fun buildOrThrow(instance: ServerInstance, dir: File, javaHome: File): LaunchSpec {
        var argfileJvmOpts: List<String> = emptyList()
        val (classpath, mainClass, args) = when (instance.type) {
            ServerType.VANILLA, ServerType.PAPER, ServerType.FABRIC -> {
                val jar = findCoreJar(dir)
                    ?: throw IllegalStateException("实例目录中没有服务器核心 jar（server.jar）")
                val main = ManifestReader.readMainClass(jar)
                    ?: throw IllegalStateException("无法读取 ${jar.name} 的 Main-Class")
                Triple(jar.absolutePath, main, listOf("nogui"))
            }
            ServerType.FORGE, ServerType.NEOFORGE -> {
                val parsed = LaunchArgfiles.parse(dir)
                // NeoForge 1.21 的 -p（模块路径）jar 已并入 classpathEntries，
                // 但模块路径、--add-opens/--add-exports、-DlegacyClassPath 等
                // 必须原样传给 VM，否则 BootstrapLauncher 起不来。
                argfileJvmOpts = parsed.unixJvmArgs + parsed.userJvmArgs
                // JVM 在 Android/Linux 上，classpath 分隔符固定为 ':'
                val cp = parsed.classpathEntries.joinToString(":") {
                    File(dir, it).absolutePath
                }
                val args = if ("nogui" in parsed.programArgs) parsed.programArgs
                else parsed.programArgs + "nogui"
                Triple(cp, parsed.mainClass, args)
            }
        }

        // 顺序：unix_args → user_jvm_args → 实例自定义 → 堆参数（后者覆盖前者）
        val jvmOpts = buildList {
            addAll(argfileJvmOpts)
            addAll(instance.extraJvmArgs)
            add("-Xms${instance.minHeapMb}M")
            add("-Xmx${instance.maxHeapMb}M")
        }

        return LaunchSpec(
            instanceId = instance.id,
            name = instance.name,
            workDir = dir.absolutePath,
            javaHome = javaHome.absolutePath,
            classpath = classpath,
            mainClass = mainClass,
            jvmOpts = jvmOpts,
            args = args,
        )
    }

    /** 核心文件查找：约定名 server.jar，或目录里唯一的大 jar */
    fun findCoreJar(dir: File): File? {
        val preferred = File(dir, "server.jar")
        if (preferred.exists()) return preferred
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }
            ?.filter { it.name !in setOf("cache/magma.log") }
            ?.maxByOrNull { it.length() }
    }
}
