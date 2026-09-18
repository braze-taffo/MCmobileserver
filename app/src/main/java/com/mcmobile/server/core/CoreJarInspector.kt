package com.mcmobile.server.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

/**
 * 从核心 jar 内部识别 MC 版本、Java 要求与核心版本。
 *
 * 只看 jar 内容、不看文件名——SAF 导入进来的文件常被重命名甚至没有版本号。
 * 实测字段位置（2026-09 现网样本）：
 *  - Paper / Folia / Vanilla：根 `version.json` 的 `id`（纯 MC 版本），同文件的 `java_version` 是权威 Java 要求
 *  - Fabric launcher：根 `install.properties` 的 `game-version` / `fabric-loader-version`
 *  - Forge / NeoForge 安装器：根 `install_profile.json` 的 `minecraft` / `version`
 *
 * 注意安装器里的 `version.json.id` **不是** MC 版本（Forge 是 "1.21.1-forge-52.0.1"，
 * NeoForge 是 "neoforge-26.2.0.88"），所以安装器只认 `install_profile.json` 或 `inheritsFrom`。
 */
object CoreJarInspector {

    enum class Kind { VANILLA, PAPER, FOLIA, FABRIC, FORGE, NEOFORGE }

    data class Detected(
        /** 纯 MC 版本，如 "26.2"、"1.21.1" */
        val mcVersion: String,
        /** jar 内声明的 Java 大版本，仅 Paper/Folia/Vanilla 有 */
        val requiredJava: Int? = null,
        /** 核心自身版本：Forge 52.0.1 / NeoForge 26.2.0.88 / Fabric loader 0.19.5 */
        val coreVersion: String? = null,
        val kind: Kind? = null,
    )

    /** 纯 MC 版本形态，用来挡掉 "1.21.1-forge-52.0.1"、"neoforge-26.2.0.88" 这类拼串 */
    private val MC_VERSION = Regex("^\\d+(\\.\\d+)+$")

    /** 单条目读取上限，防止有人塞个超大 json 进来把内存吃光 */
    private const val MAX_ENTRY_BYTES = 1 shl 20

    private val json = Json { ignoreUnknownKeys = true }

    /** 探测失败一律返回 null，由调用方决定兜底策略（不抛异常，因为它是"猜"的一部分） */
    fun detect(jar: File): Detected? = runCatching {
        ZipFile(jar).use { zip ->
            fromInstallProfile(zip)
                ?: fromInstallProperties(zip)
                ?: fromVersionJson(zip)
        }
    }.getOrNull()

    private fun fromInstallProfile(zip: ZipFile): Detected? {
        val obj = readEntry(zip, "install_profile.json")
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: return null
        val mc = obj["minecraft"]?.jsonPrimitive?.content?.takeIf { MC_VERSION.matches(it) }
            ?: return null
        val raw = obj["version"]?.jsonPrimitive?.content
        val kind = when {
            raw == null -> Kind.FORGE
            raw.contains("neoforge", ignoreCase = true) -> Kind.NEOFORGE
            else -> Kind.FORGE
        }
        // NeoForge 的 version 是 "neoforge-26.2.0.88"，Forge 的形如 "1.21.1-forge-52.0.1"
        val core = when (kind) {
            Kind.NEOFORGE -> raw?.substringAfter("neoforge-", raw)
            else -> raw?.removePrefix("$mc-")?.removePrefix("forge-")
        }
        return Detected(mcVersion = mc, coreVersion = core, kind = kind)
    }

    private fun fromInstallProperties(zip: ZipFile): Detected? {
        val text = readEntry(zip, "install.properties") ?: return null
        val props = runCatching { Properties().apply { text.reader().use { load(it) } } }.getOrNull()
            ?: return null
        val mc = props.getProperty("game-version")?.trim()?.takeIf { MC_VERSION.matches(it) }
            ?: return null
        return Detected(
            mcVersion = mc,
            coreVersion = props.getProperty("fabric-loader-version")?.trim(),
            kind = Kind.FABRIC,
        )
    }

    private fun fromVersionJson(zip: ZipFile): Detected? {
        val obj = readEntry(zip, "version.json")
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: return null
        val id = obj["id"]?.jsonPrimitive?.content
        val inherited = obj["inheritsFrom"]?.jsonPrimitive?.content
        // 安装器（Forge/NeoForge）的 id 是拼串，只有 inheritsFrom 是干净的 MC 版本
        val mc = (inherited ?: id)?.takeIf { MC_VERSION.matches(it) } ?: return null
        val java = obj["java_version"]?.jsonPrimitive?.content?.toIntOrNull()?.takeIf { it > 0 }
        val kind = if (inherited != null) installerKind(id) else serverJarKind(zip)
        return Detected(mcVersion = mc, requiredJava = java, kind = kind)
    }

    private fun installerKind(id: String?): Kind? = when {
        id == null -> null
        id.contains("neoforge", ignoreCase = true) -> Kind.NEOFORGE
        id.contains("forge", ignoreCase = true) -> Kind.FORGE
        else -> null
    }

    /** 服务端 jar 的 `META-INF/versions.list` 每行是 "sha1\t<版本>\t<版本>/<文件名>"，文件名前缀即发行版 */
    private fun serverJarKind(zip: ZipFile): Kind? {
        val line = readEntry(zip, "META-INF/versions.list")
            ?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: return null
        val name = line.split('\t').lastOrNull()?.trim()?.substringAfterLast('/') ?: return null
        return when {
            name.startsWith("paper-", ignoreCase = true) -> Kind.PAPER
            name.startsWith("folia-", ignoreCase = true) -> Kind.FOLIA
            name.startsWith("server-", ignoreCase = true) -> Kind.VANILLA
            else -> null
        }
    }

    private fun readEntry(zip: ZipFile, name: String): String? {
        val entry = zip.getEntry(name)
            ?: zip.entries().asSequence().firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return null
        if (entry.isDirectory || entry.size > MAX_ENTRY_BYTES) return null
        return zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8)
    }
}
