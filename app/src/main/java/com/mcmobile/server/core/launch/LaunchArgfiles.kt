package com.mcmobile.server.core.launch

import java.io.File

/**
 * 解析 Forge/NeoForge `--installServer` 生成的启动文件：
 *  - libraries/**/unix_args.txt：完整命令行（-cp、主类、程序参数）
 *  - user_jvm_args.txt：JVM 选项（含必需的 -DignoreList 等）
 *
 * 我们不用 java 启动器（无 @argfile 能力），因此自己解析后经 JNI 拼装。
 * 参考 run.sh：exec java @user_jvm_args.txt @libraries/.../unix_args.txt "$@"
 */
object LaunchArgfiles {

    data class Parsed(
        /** classpath 条目（相对实例目录），用 File.pathSeparator 拼接前保持原样 */
        val classpathEntries: List<String>,
        val mainClass: String,
        val programArgs: List<String>,
        val jvmArgs: List<String>,
        val unixArgsFile: File?,
    )

    fun findUnixArgsFile(instanceDir: File): File? {
        val libDir = File(instanceDir, "libraries")
        if (!libDir.isDirectory) return null
        // libraries/net/neoforged/neoforge/<v>/unix_args.txt 或 net/minecraftforge/forge/<v>/unix_args.txt
        return libDir.walkTopDown()
            .filter { it.isFile && it.name == "unix_args.txt" }
            .maxByOrNull { it.length() }
    }

    fun parse(instanceDir: File): Parsed {
        val unixArgsFile = findUnixArgsFile(instanceDir)
            ?: throw IllegalStateException("未找到 unix_args.txt（服务器尚未安装？）")
        val tokens = tokenize(unixArgsFile.readText())

        val cp = mutableListOf<String>()
        var mainClass: String? = null
        val programArgs = mutableListOf<String>()

        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (mainClass == null) {
                when {
                    t == "-cp" || t == "-classpath" || t == "--class-path" -> {
                        i++
                        // unix_args.txt 由 run.sh（Linux 语义）生成，分隔符恒为 ':'
                        if (i < tokens.size) cp += tokens[i].split(':')
                    }
                    t == "-p" || t == "--module-path" || t.startsWith("--module-path=") -> {
                        // BootstrapLauncher 场景类路径通常仍走 -cp；模块路径忽略（模块化的 MC 不存在）
                        if (t.contains('=')) Unit else i++
                    }
                    t.startsWith("-") -> Unit // 其他 JVM 风格选项（不应出现在 unix_args，容错跳过）
                    else -> mainClass = t
                }
            } else {
                programArgs += t
            }
            i++
        }

        val jvmArgs = parseUserJvmArgs(File(instanceDir, "user_jvm_args.txt"))
        return Parsed(
            classpathEntries = cp.map { it.removePrefix("./") },
            mainClass = mainClass ?: throw IllegalStateException("unix_args.txt 中未找到主类"),
            programArgs = programArgs,
            jvmArgs = jvmArgs,
            unixArgsFile = unixArgsFile,
        )
    }

    /** user_jvm_args.txt：跳过注释行，收集 -X/-D 选项 */
    fun parseUserJvmArgs(file: File): List<String> {
        if (!file.exists()) return emptyList()
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .flatMap { tokenize(it) }
    }

    /** argfile 分词：空白分隔，支持成对单/双引号 */
    internal fun tokenize(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        var hasToken = false
        for (c in text) {
            when {
                quote != null -> {
                    if (c == quote) quote = null else sb.append(c)
                }
                c == '"' || c == '\'' -> {
                    quote = c
                    hasToken = true
                }
                c.isWhitespace() -> {
                    if (hasToken) {
                        out += sb.toString()
                        sb.setLength(0)
                        hasToken = false
                    }
                }
                else -> {
                    sb.append(c)
                    hasToken = true
                }
            }
        }
        if (hasToken) out += sb.toString()
        return out
    }
}
