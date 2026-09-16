package com.mcmobile.server.data

import kotlinx.serialization.Serializable

@Serializable
enum class ServerType(val label: String) {
    VANILLA("Vanilla 原版"),
    PAPER("Paper"),
    FABRIC("Fabric"),
    FORGE("Forge"),
    NEOFORGE("NeoForge"),
}

@Serializable
data class ServerInstance(
    val id: String,
    val name: String,
    val type: ServerType,
    /** MC 版本，如 "1.21.1" */
    val mcVersion: String,
    /** 核心版本：Paper build / NeoForge 版本号 / Forge 版本号等 */
    val coreVersion: String? = null,
    /** 实例目录名（files/servers/<dirName>） */
    val dirName: String,
    val minHeapMb: Int = 256,
    val maxHeapMb: Int = 1024,
    /** 该实例使用的内置 JRE 大版本（21/25，按 MC 版本自动定） */
    val javaMajor: Int = 21,
    val createdMs: Long,
    val lastStartedMs: Long? = null,
    /** Forge/NeoForge：服务器文件是否已通过安装器生成 */
    val installed: Boolean = false,
    /** 额外 JVM 参数（-D/-X 开头） */
    val extraJvmArgs: List<String> = emptyList(),
) {
    val needsInstaller: Boolean get() = type == ServerType.FORGE || type == ServerType.NEOFORGE
}
