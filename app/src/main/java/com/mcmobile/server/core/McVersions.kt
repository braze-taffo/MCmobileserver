package com.mcmobile.server.core

/** MC 版本号解析、比较与 Java 兼容性判定 */
object McVersions {

    /** "1.21.1" → [1,21,1]；"26.3" → [26,3]；非数字段按 0 */
    fun parse(version: String): List<Int> =
        version.split('.').map { it.toIntOrNull() ?: 0 }

    /** 语义比较；解析失败时按字符串比较 */
    fun compare(a: String, b: String): Int {
        val pa = parse(a)
        val pb = parse(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return a.compareTo(b)
    }

    fun isRelease(id: String): Boolean = !id.contains(Regex("rc|pre|snapshot|exp|craftmine", RegexOption.IGNORE_CASE))

    /**
     * 该 MC 版本服务器端要求的最低 Java 大版本。
     * 依据 Mojang 版本元数据：1.17→16，1.18→17，1.20.5→21，26.x→25。
     */
    fun requiredJava(mcVersion: String): Int {
        val p = parse(mcVersion)
        val a = p.getOrElse(0) { 0 }
        val b = p.getOrElse(1) { 0 }
        val c = p.getOrElse(2) { 0 }
        return when {
            a >= 25 -> 25            // 2026 起日期式版本（26.1+）
            a == 1 && b >= 21 -> 21  // 1.21.x
            a == 1 && b == 20 && c >= 5 -> 21
            a == 1 && b >= 18 -> 17
            a == 1 && b == 17 -> 16
            else -> 8
        }
    }

    /** 应用内置运行时能否跑这个 MC 版本（Java 8 时代的旧版在 21 上会崩，16/17/18/20 由 21 覆盖） */
    fun isSupported(mcVersion: String): Boolean {
        val req = requiredJava(mcVersion)
        return req >= 16 && (req <= 21 || req == 25)
    }

    /** 应使用的内置 JRE 大版本 */
    fun bundledJavaMajor(mcVersion: String): Int =
        if (requiredJava(mcVersion) >= 25) 25 else 21
}
