package com.mcmobile.server.data.api

import com.mcmobile.server.core.McVersions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 所有核心元数据 API 与下载器的统一入口（纯 HTTP + JSON，无 Android 依赖，便于单测） */
object CoreApi {

    val json = Json { ignoreUnknownKeys = true }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val resp = http.newCall(Request.Builder().url(url).build()).execute()
        resp.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} for $url")
            it.body!!.string()
        }
    }

    /** 下载到文件，onProgress(已下载字节, 总字节或-1) */
    suspend fun download(
        url: String,
        target: File,
        expectedSha256: String? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val resp = http.newCall(Request.Builder().url(url).build()).execute()
        resp.use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
            val body = r.body!!
            val total = body.contentLength()
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".part")
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (expectedSha256 != null) {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                tmp.inputStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual == expectedSha256) { "校验失败 sha256=$actual" }
            }
            check(tmp.renameTo(target)) { "重命名失败: $tmp" }
            target
        }
    }

    // ---------- Vanilla (Mojang piston-meta) ----------

    private const val MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

    /** 可下载服务端的 release 版本（含 javaVersion 的缓存） */
    suspend fun vanillaVersions(): List<VanillaVersion> {
        val root = json.parseToJsonElement(get(MANIFEST_URL)).jsonObject
        return root["versions"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["type"]!!.jsonPrimitive.content == "release" }
            .map { v ->
                VanillaVersion(
                    id = v["id"]!!.jsonPrimitive.content,
                    url = v["url"]!!.jsonPrimitive.content,
                )
            }
    }

    /** 版本详情（含 downloads.server 与 javaVersion），磁盘缓存 files/api-cache/ */
    suspend fun vanillaVersionDetail(id: String, url: String, cacheDir: File): VanillaDetail {
        val f = File(cacheDir, "vanilla-$id.json")
        val text = if (f.exists()) f.readText() else get(url).also { f.parentFile?.mkdirs(); f.writeText(it) }
        val root = json.parseToJsonElement(text).jsonObject
        val server = root["downloads"]!!.jsonObject["server"]?.jsonObject
        val java = root["javaVersion"]?.jsonObject
        return VanillaDetail(
            id = id,
            serverUrl = server?.get("url")?.jsonPrimitive?.content,
            serverSha1 = server?.get("sha1")?.jsonPrimitive?.content,
            serverSize = server?.get("size")?.jsonPrimitive?.content?.toLongOrNull(),
            javaMajor = java?.get("majorVersion")?.jsonPrimitive?.content?.toIntOrNull(),
        )
    }

    // ---------- Paper (fill v3) ----------

    private const val FILL = "https://fill.papermc.io/v3"

    /**
     * 可下载的 Paper/Folia 版本。
     *
     * fill 的 `versions` 是「族 → 版本数组」的映射，族名（"1.21"、"26.1"）**不是**版本号：
     * 拿族名当版本用会漏掉 1.21.11 / 26.1.2 这些真实版本，而且 26.1 这类族名下载必然 404。
     * 所以这里展平 values，再滤掉 rc/pre。
     */
    suspend fun paperVersions(project: String = "paper"): List<String> {
        val root = json.parseToJsonElement(get("$FILL/projects/$project")).jsonObject
        return root["versions"]!!.jsonObject.values
            .flatMap { family -> family.jsonArray.map { it.jsonPrimitive.content } }
            .distinct()
            .filter { McVersions.isRelease(it) && McVersions.isSupported(it) }
            .sortedWith { a, b -> McVersions.compare(b, a) }
    }

    suspend fun paperDownload(mcVersion: String, project: String = "paper"): CoreDownload {
        val b = json.parseToJsonElement(
            get("$FILL/projects/$project/versions/$mcVersion/builds/latest"),
        ).jsonObject
        val dl = b["downloads"]!!.jsonObject["server:default"]!!.jsonObject
        return CoreDownload(
            url = dl["url"]!!.jsonPrimitive.content,
            sha256 = dl["checksums"]?.jsonObject?.get("sha256")?.jsonPrimitive?.content,
            fileName = dl["name"]!!.jsonPrimitive.content,
            sizeBytes = dl["size"]?.jsonPrimitive?.content?.toLongOrNull(),
            build = b["id"]?.jsonPrimitive?.content,
        )
    }

    // ---------- Fabric ----------

    private const val FABRIC = "https://meta.fabricmc.net/v2"

    /** Fabric 侧的一个构件（loader / installer），带官方 stable 标记 */
    data class FabricArtifact(val version: String, val stable: Boolean)

    suspend fun fabricGameVersions(): List<String> {
        val arr = json.parseToJsonElement(get("$FABRIC/versions/game")).jsonArray
        return arr.map { it.jsonObject["version"]!!.jsonPrimitive.content }
    }

    suspend fun fabricLoaderVersions(): List<FabricArtifact> = fabricArtifacts("loader")

    suspend fun fabricInstallerVersions(): List<FabricArtifact> = fabricArtifacts("installer")

    private suspend fun fabricArtifacts(kind: String): List<FabricArtifact> {
        val arr = json.parseToJsonElement(get("$FABRIC/versions/$kind")).jsonArray
        return arr.map { item ->
            val obj = item.jsonObject
            FabricArtifact(
                version = obj["version"]!!.jsonPrimitive.content,
                stable = obj["stable"]?.jsonPrimitive?.content?.toBoolean() ?: false,
            )
        }
    }

    /** 列表里第一个 stable 版本号；全都不 stable 时退化为列表首个 */
    fun newestStable(artifacts: List<FabricArtifact>): String? =
        (artifacts.firstOrNull { it.stable } ?: artifacts.firstOrNull())?.version

    /**
     * server launcher 下载地址。
     * 必须带 installer 版本段：meta 只提供 `/{game}/{loader}/{installer}/server/jar`，
     * 缺该段的旧路径实测 404。
     */
    fun fabricServerJarUrl(mcVersion: String, loader: String, installer: String): String =
        "$FABRIC/versions/loader/$mcVersion/$loader/$installer/server/jar"

    // ---------- NeoForge ----------

    suspend fun neoForgeVersions(): List<String> {
        val root = json.parseToJsonElement(
            get("https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge"),
        ).jsonObject
        return root["versions"]!!.jsonArray.map { it.jsonPrimitive.content }
    }

    /** MC "1.21.1" → 前缀 "21.1."；MC "26.2" → "26.2." */
    fun neoForgePrefix(mcVersion: String): String =
        mcVersion.removePrefix("1.") + "."

    fun neoForgeInstallerUrl(version: String): String =
        "https://maven.neoforged.net/releases/net/neoforged/neoforge/$version/neoforge-$version-installer.jar"

    // ---------- Forge ----------

    suspend fun forgePromos(): Map<String, Pair<String, String>> {
        val root = json.parseToJsonElement(
            get("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"),
        ).jsonObject
        val promos = root["promos"]!!.jsonObject
        val out = mutableMapOf<String, Pair<String, String>>()
        for ((k, v) in promos) {
            val m = Regex("^(.*)-(recommended|latest)$").find(k) ?: continue
            val mc = m.groupValues[1]
            val kind = m.groupValues[2]
            val ver = v.jsonPrimitive.content
            if (ver.isBlank()) continue
            val cur = out[mc]
            val pair = if (kind == "recommended") ver to (cur?.second ?: "") else (cur?.first ?: "") to ver
            out[mc] = pair
        }
        return out
    }

    fun forgeInstallerUrl(mcVersion: String, forgeVersion: String): String =
        "https://maven.minecraftforge.net/net/minecraftforge/forge/$mcVersion-$forgeVersion/forge-$mcVersion-$forgeVersion-installer.jar"

    // ---------- 数据类 ----------

    @kotlinx.serialization.Serializable
    data class VanillaVersion(val id: String, val url: String)

    @kotlinx.serialization.Serializable
    data class VanillaDetail(
        val id: String,
        val serverUrl: String?,
        val serverSha1: String?,
        val serverSize: Long?,
        val javaMajor: Int?,
    )

    data class CoreDownload(
        val url: String,
        val sha256: String?,
        val fileName: String,
        val sizeBytes: Long?,
        val build: String?,
    )
}
