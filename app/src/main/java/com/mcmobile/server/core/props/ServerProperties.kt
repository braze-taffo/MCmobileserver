package com.mcmobile.server.core.props

import java.io.File

/**
 * server.properties 解析/保存：保留原顺序与注释，仅改值。
 */
class ServerProperties private constructor(val lines: MutableList<String>) {

    val keys: List<String>
        get() = lines.filter { isProperty(it) }.map { it.substringBefore('=').trim() }

    operator fun get(key: String): String? = lines
        .firstOrNull { isProperty(it) && it.substringBefore('=').trim() == key }
        ?.substringAfter('=', "")?.trim()

    operator fun set(key: String, value: String) {
        val idx = lines.indexOfFirst {
            isProperty(it) && it.substringBefore('=').trim() == key
        }
        if (idx >= 0) {
            lines[idx] = "$key=$value"
        } else {
            lines.add("$key=$value")
        }
    }

    fun toText(): String = lines.joinToString("\n", postfix = "\n")

    companion object {
        private fun isProperty(line: String): Boolean {
            val t = line.trim()
            return t.isNotEmpty() && !t.startsWith("#") && t.contains('=')
        }

        fun parse(text: String) = ServerProperties(text.lineSequence().toMutableList())

        fun load(file: File): ServerProperties? =
            if (file.exists()) runCatching { parse(file.readText()) }.getOrNull() else null

        /** 首次启动前服务器尚未生成时，提供常用默认 */
        fun default(): ServerProperties = parse(
            """
            #Minecraft server properties
            server-port=25565
            gamemode=survival
            difficulty=normal
            motd=A Minecraft Server
            max-players=20
            online-mode=true
            view-distance=8
            simulation-distance=8
            pvp=true
            white-list=false
            """.trimIndent(),
        )
    }

    /** 常用项的中文说明 */
    val descriptions: Map<String, String> = mapOf(
        "server-port" to "端口（25565 默认）",
        "max-players" to "最大玩家数",
        "motd" to "服务器描述",
        "online-mode" to "正版验证（false 允许离线进入）",
        "difficulty" to "难度 peaceful/easy/normal/hard",
        "gamemode" to "默认模式 survival/creative/adventure",
        "view-distance" to "视距（区块，手机建议 ≤8）",
        "simulation-distance" to "模拟距离（区块，手机建议 ≤6）",
        "white-list" to "白名单",
        "pvp" to "允许 PvP",
        "allow-flight" to "允许飞行",
        "spawn-protection" to "出生点保护半径",
        "level-type" to "世界类型 minecraft:normal/flat/large_biomes/amplified",
        "level-seed" to "世界种子（留空随机）",
        "max-world-size" to "世界大小上限（MB）",
        "enable-command-block" to "启用命令方块",
        "resource-pack" to "资源包 URL",
    )
}
