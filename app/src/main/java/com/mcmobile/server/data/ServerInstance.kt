package com.mcmobile.server.data

import kotlinx.serialization.Serializable

@Serializable
enum class ServerType(val label: String) {
    VANILLA("Vanilla 原版"),
    PAPER("Paper"),
    FOLIA("Folia"),
    FABRIC("Fabric"),
    FORGE("Forge"),
    NEOFORGE("NeoForge"),
}

/** 实例存放位置 */
@Serializable
enum class StorageKind(val label: String) {
    /** 应用私有存储 files/servers：无需任何权限、性能最好，卸载应用时一并删除 */
    INTERNAL("应用内部存储"),

    /** 用户用文件夹选择器指定的目录：文件管理器/电脑都能直接读写，需要「所有文件访问权限」 */
    EXTERNAL("用户可访问目录"),
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
    /** 实例目录名：INTERNAL 为 files/servers/<dirName>，EXTERNAL 为 <externalRoot>/<dirName> */
    val dirName: String,
    /** 存放位置。旧清单没有这个字段，反序列化落到 INTERNAL，与升级前的行为完全一致 */
    val storage: StorageKind = StorageKind.INTERNAL,
    /** EXTERNAL 实例的父目录（用户所选目录的绝对路径）；INTERNAL 时为 null */
    val externalRoot: String? = null,
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
