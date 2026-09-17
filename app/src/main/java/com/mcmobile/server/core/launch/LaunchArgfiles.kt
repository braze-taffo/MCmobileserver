package com.mcmobile.server.core.launch

import java.io.File

/**
 * 解析 Forge/NeoForge `--installServer` 生成的启动文件：
 *  - libraries/**/unix_args.txt：完整命令行（-cp/-p、JVM 选项、主类、程序参数）
 *  - user_jvm_args.txt：用户 JVM 选项
 *
 * 我们不用 java 启动器（无 @argfile 能力），因此自己解析后经 JNI 拼装。
 * 参考 run.sh：exec java @user_jvm_args.txt @libraries/.../unix_args.txt "$@"
 *
 * 注意 NeoForge 1.21 的 unix_args.txt 是**模块路径**形态：
 *   -p <8 个 jar> --add-modules=ALL-MODULE-PATH --add-opens ... -D... <主类> <程序参数>
 * 而 JNI 侧只有一个 FindClass（app class loader，只能看 classpath，看不到具名模块），
 * 所以这里把 -p 的 jar 同时放进 classpath；具名模块的 --add-opens/--add-exports
 * 另外补一份 =ALL-UNNAMED —— 走 classpath 的那份代码在未具名模块里。
 */
object LaunchArgfiles {

    data class Parsed(
        /** classpath 条目（-cp 与 -p 的并集，相对实例目录，由调用方解析为绝对路径） */
        val classpathEntries: List<String>,
        val mainClass: String,
        val programArgs: List<String>,
        /** unix_args.txt 里的 JVM 选项（模块路径、--add-opens、-D 属性等），已是 VM 认的 `=` 形式 */
        val unixJvmArgs: List<String>,
        /** user_jvm_args.txt 里的选项（去注释） */
        val userJvmArgs: List<String>,
        val unixArgsFile: File?,
    )

    /** VM 选项要求整条作为单个 JavaVMOption 传入，因此这些「空格分参数」的选项要拼成 `--opt=value` */
    private val VALUE_OPTIONS = setOf(
        "--add-opens", "--add-exports", "--add-reads", "--add-modules",
        "--patch-module", "--limit-modules", "--upgrade-module-path",
    )

    private val MODULE_PATH_OPTIONS = setOf("-p", "--module-path")
    private val CLASSPATH_OPTIONS = setOf("-cp", "-classpath", "--class-path")

    /** 这些选项的内容需要以未具名模块身份再授权一次（见类注释） */
    private val DUPLICATE_AS_UNNAMED = setOf("--add-opens", "--add-exports", "--add-reads")

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
        val jvmArgs = mutableListOf<String>()
        var mainClass: String? = null
        val programArgs = mutableListOf<String>()

        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (mainClass != null) {
                programArgs += t
                i++
                continue
            }
            // 取出「选项 + 值」里的值；`--opt=value` 自带值
            fun valueOf(name: String): Pair<String, Int>? = when {
                t.startsWith("$name=") -> t.substring(name.length + 1) to 1
                t == name && i + 1 < tokens.size -> tokens[i + 1] to 2
                else -> null
            }

            val classpath = CLASSPATH_OPTIONS.firstNotNullOfOrNull { valueOf(it) }
            val modulePath = MODULE_PATH_OPTIONS.firstNotNullOfOrNull { valueOf(it) }
            val joined = VALUE_OPTIONS.firstNotNullOfOrNull { name ->
                valueOf(name)?.let { (v, n) -> Triple(name, v, n) }
            }
            when {
                classpath != null -> {
                    // unix_args.txt 由 run.sh（Linux 语义）生成，分隔符恒为 ':'
                    cp += classpath.first.split(':')
                    i += classpath.second
                }
                modulePath != null -> {
                    val entries = modulePath.first.split(':')
                    // FindClass 只能走 classpath，模块路径的 jar 必须同时在 classpath 上
                    cp += entries
                    jvmArgs += "--module-path=" + entries.joinToString(":") {
                        File(instanceDir, it).absolutePath
                    }
                    i += modulePath.second
                }
                joined != null -> {
                    val (name, value, n) = joined
                    jvmArgs += "$name=$value"
                    if (name in DUPLICATE_AS_UNNAMED) {
                        // value 形如 a/b=target.mod，把 target 换成 ALL-UNNAMED 再加一份
                        val head = value.substringBeforeLast('=')
                        jvmArgs += "$name=$head=ALL-UNNAMED"
                    }
                    i += n
                }
                t == "-m" || t == "--module" -> {
                    // `--module mod/Class` 形态：模块 jar 已在 classpath 上，主类取 Class 部分
                    val v = if (i + 1 < tokens.size) tokens[i + 1] else ""
                    mainClass = v.substringAfter('/', v).replace('/', '.')
                    i += 2
                }
                t.startsWith("-") -> {
                    jvmArgs += t
                    i++
                }
                else -> {
                    mainClass = t
                    i++
                }
            }
        }

        return Parsed(
            classpathEntries = cp.map { it.removePrefix("./") },
            mainClass = mainClass ?: throw IllegalStateException("unix_args.txt 中未找到主类"),
            programArgs = programArgs,
            unixJvmArgs = jvmArgs,
            userJvmArgs = parseUserJvmArgs(File(instanceDir, "user_jvm_args.txt")),
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
