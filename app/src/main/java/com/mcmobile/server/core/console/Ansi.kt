package com.mcmobile.server.core.console

/**
 * 去掉日志里的 ANSI 转义序列。
 *
 * MC/NeoForge 的 log4j 配置会给控制台输出上色，我们的 stdout 是被接管的管道（不是终端），
 * 这些 ESC 序列不会渲染，只会原样出现在控制台界面和 logs/run-latest.log 里，所以直接剥掉。
 */
object Ansi {

    /** CSI：ESC [ 参数 中间字符 终止字符（覆盖 \u001B[32m、\u001B[0;1m、\u001B[m 等） */
    private val CSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    /** OSC：ESC ] ... BEL（终端标题等） */
    private val OSC = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")

    fun strip(line: String): String {
        if (line.indexOf('\u001B') < 0) return line
        return CSI.replace(OSC.replace(line, ""), "")
    }
}
