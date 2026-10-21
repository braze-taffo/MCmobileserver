package com.mcmobile.server.core.manage

import com.mcmobile.server.core.files.ManagedFiles
import com.mcmobile.server.data.ServerType
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile

object Addons {
    data class Local(val file: File, val id: String?, val name: String, val version: String?, val enabled: Boolean, val description: String? = null)
    data class Hit(val id: String, val title: String, val description: String, val downloads: Long)
    data class SearchPage(val hits: List<Hit>, val total: Int, val offset: Int) {
        val hasNext: Boolean get() = offset + hits.size < total
    }
    enum class Sort(val api: String, val label: String) {
        RELEVANCE("relevance", "相关度"), DOWNLOADS("downloads", "下载量"), UPDATED("updated", "最近更新")
    }
    data class Download(val name: String, val url: String, val sha512: String?, val sha1: String?)
    data class Version(val id: String, val project: String, val name: String, val number: String,
        val loaders: List<String>, val games: List<String>, val files: List<Download>, val dependencies: List<Pair<String?, String?>>,
        val releaseType: String = "release", val published: String = "")
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    fun loader(type: ServerType): String? = when (type) {
        ServerType.FABRIC -> "fabric"; ServerType.FORGE -> "forge"; ServerType.NEOFORGE -> "neoforge"
        ServerType.PAPER -> "paper"; ServerType.FOLIA -> "folia"; else -> null
    }
    fun directory(type: ServerType): String = if (type in setOf(ServerType.PAPER, ServerType.FOLIA)) "plugins" else "mods"
    fun local(root: File, type: ServerType): List<Local> {
        val dir = ManagedFiles(root).child(root, directory(type))
        return dir.listFiles().orEmpty().filter { it.isFile && (it.name.endsWith(".jar", true) || it.name.endsWith(".jar.disabled", true)) }.map { file ->
            ManagedFiles(root).checked(file)
            var id: String? = null; var name = file.name.removeSuffix(".disabled").removeSuffix(".jar"); var version: String? = null; var description: String? = null
            runCatching { JarFile(file).use { jar ->
                fun read(path: String): String? = jar.getJarEntry(path)?.takeIf { it.size <= 1048576 }?.let { entry ->
                    jar.getInputStream(entry).use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > 1048576) return@use null
                            output.write(buffer, 0, count)
                        }
                        ModMetadataText.decode(output.toByteArray())
                    }
                }
                fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                fun readObject(path: String): JsonObject? = runCatching {
                    read(path)?.let { json.parseToJsonElement(it) as? JsonObject }
                }.getOrNull()
                val fabric = readObject("fabric.mod.json")
                val quiltLoader = if (fabric == null) (readObject("quilt.mod.json")?.get("quilt_loader") as? JsonObject)
                    ?.takeIf { it.string("id") != null } else null
                if (fabric != null) {
                    id = fabric.string("id"); name = fabric.string("name") ?: name
                    version = fabric.string("version"); description = fabric.string("description")
                } else if (quiltLoader != null) {
                    val meta = quiltLoader["metadata"] as? JsonObject
                    id = quiltLoader.string("id"); name = meta?.string("name") ?: name
                    version = quiltLoader.string("version"); description = meta?.string("description")
                } else {
                    val toml = read("META-INF/neoforge.mods.toml") ?: read("META-INF/mods.toml")
                    val yaml = read("paper-plugin.yml") ?: read("plugin.yml")
                    fun field(text: String?, key: String, separator: String, topLevel: Boolean = false): String? = text?.let {
                        val indent = if (topLevel) "" else "[ \\t]*"
                        Regex("(?m)^$indent$key[ \\t]*$separator[ \\t]*[\"']?([^\"'\\r\\n]+)").find(it)?.groupValues?.get(1)?.trim()
                    }
                    id = field(toml, "modId", "=") ?: field(yaml, "name", ":")
                    name = field(toml, "displayName", "=") ?: field(yaml, "name", ":") ?: name
                    version = field(toml, "version", "=")?.takeUnless { it.contains('$') } ?: field(yaml, "version", ":")
                    description = AddonMetadataFields.tomlString(toml, "description") ?: field(yaml, "description", ":", topLevel = true)
                }
                version = version ?: jar.manifest?.mainAttributes?.getValue("Implementation-Version")
            } }
            Local(file, id, name, version, !file.name.endsWith(".disabled", true), description)
        }.sortedBy { it.name.lowercase() }
    }
    private fun get(path: String, query: Map<String, String> = emptyMap(), allowMissing: Boolean = false): JsonElement? {
        val url = "https://api.modrinth.com/v2/$path".toHttpUrl().newBuilder()
        query.forEach { (key, value) -> url.addQueryParameter(key, value) }
        client.newCall(Request.Builder().url(url.build()).header("User-Agent", "braze-taffo/MCmobileserver (Android)").build()).execute().use { response ->
            if (allowMissing && response.code == 404) return null
            check(response.isSuccessful) { "Modrinth 请求失败：HTTP ${response.code} ${response.body?.string()?.take(500)}" }
            return json.parseToJsonElement(response.body?.string() ?: error("响应为空"))
        }
    }
    fun search(query: String, type: ServerType, mc: String, offset: Int = 0): List<Hit> {
        return searchPage(query, type, mc, offset).hits
    }
    internal fun searchParameters(query: String, type: ServerType, mc: String, offset: Int, sort: Sort): Map<String, String> {
        val loader = loader(type) ?: error("原版服务端不支持加载模组或插件")
        require(offset >= 0)
        val facets = buildJsonArray {
            add(buildJsonArray { add("project_type:mod") })
            add(buildJsonArray { add("categories:$loader") })
            add(buildJsonArray { add("versions:$mc") })
            add(buildJsonArray { add("server_side!=unsupported") })
        }
        return mapOf("query" to query.trim(), "facets" to facets.toString(), "limit" to "20", "offset" to offset.toString(), "index" to sort.api)
    }
    fun searchPage(query: String, type: ServerType, mc: String, offset: Int = 0, sort: Sort = Sort.RELEVANCE): SearchPage =
        parseSearch(get("search", searchParameters(query, type, mc, offset, sort))!!.jsonObject)

    internal fun parseSearch(obj: JsonObject): SearchPage {
        val hits = obj["hits"]!!.jsonArray.map { item -> val hit = item.jsonObject
            Hit(hit["project_id"]!!.jsonPrimitive.content, hit["title"]!!.jsonPrimitive.content, hit["description"]!!.jsonPrimitive.content, hit["downloads"]!!.jsonPrimitive.long)
        }
        return SearchPage(hits, obj["total_hits"]!!.jsonPrimitive.int, obj["offset"]!!.jsonPrimitive.int)
    }
    private fun parse(element: JsonElement): Version {
        val obj = element.jsonObject
        return Version(obj["id"]!!.jsonPrimitive.content, obj["project_id"]!!.jsonPrimitive.content,
            obj["name"]!!.jsonPrimitive.content, obj["version_number"]!!.jsonPrimitive.content,
            obj["loaders"]!!.jsonArray.map { it.jsonPrimitive.content }, obj["game_versions"]!!.jsonArray.map { it.jsonPrimitive.content },
            obj["files"]!!.jsonArray.map { it.jsonObject }.sortedByDescending { it["primary"]?.jsonPrimitive?.booleanOrNull == true }.map { file ->
                Download(file["filename"]!!.jsonPrimitive.content, file["url"]!!.jsonPrimitive.content,
                    file["hashes"]?.jsonObject?.get("sha512")?.jsonPrimitive?.content, file["hashes"]?.jsonObject?.get("sha1")?.jsonPrimitive?.content)
            }, obj["dependencies"]!!.jsonArray.map { it.jsonObject }.filter { it["dependency_type"]?.jsonPrimitive?.content == "required" }
                .map { it["version_id"]?.jsonPrimitive?.contentOrNull to it["project_id"]?.jsonPrimitive?.contentOrNull },
            obj["version_type"]?.jsonPrimitive?.content ?: "release", obj["date_published"]?.jsonPrimitive?.content ?: "")
    }
    fun versions(project: String, type: ServerType, mc: String): List<Version> {
        val loader = loader(type) ?: error("核心不支持扩展")
        return get("project/$project/version", mapOf("loaders" to JsonArray(listOf(JsonPrimitive(loader))).toString(), "game_versions" to JsonArray(listOf(JsonPrimitive(mc))).toString()))!!.jsonArray.map(::parse)
            .filter { mc in it.games && loader in it.loaders && it.files.any { file -> file.name.endsWith(".jar", true) } }
            .sortedByDescending { it.published }
    }
    fun hash(file: File, algorithm: String = "SHA-512"): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun identified(file: File): Version? = get("version_file/${hash(file)}", mapOf("algorithm" to "sha512"), true)?.let(::parse)
    fun update(file: File, type: ServerType, mc: String): Version? {
        val current = identified(file) ?: return null
        val body = buildJsonObject { put("loaders", buildJsonArray { add(loader(type) ?: error("不支持")) }); put("game_versions", buildJsonArray { add(mc) }) }
        val req = Request.Builder().url("https://api.modrinth.com/v2/version_file/${hash(file)}/update?algorithm=sha512")
            .header("User-Agent", "braze-taffo/MCmobileserver (Android)").post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { response ->
            check(response.isSuccessful) { "检查更新失败：HTTP ${response.code}" }
            val latest = parse(json.parseToJsonElement(response.body!!.string()))
            return latest.takeIf { it.id != current.id }
        }
    }
    fun plan(version: Version, type: ServerType, mc: String): List<Version> {
        val result = linkedMapOf<String, Version>()
        val visiting = hashSetOf<String>()
        fun visit(v: Version) {
            if (result.containsKey(v.project)) { require(result[v.project]?.id == v.id) { "依赖版本冲突，请手动选择兼容版本" }; return }
            require(visiting.add(v.id)) { "发现循环依赖，请手动处理" }
            require(result.size + visiting.size <= 40) { "依赖过多，请手动处理" }
            require(mc in v.games && loader(type) in v.loaders) { "依赖 ${v.name} 与当前 MC 版本或加载器不匹配" }
            val project = get("project/${v.project}")!!.jsonObject
            require(project["server_side"]?.jsonPrimitive?.content != "unsupported") { "${v.name} 是仅客户端扩展" }
            v.dependencies.forEach { (id, projectId) ->
                val dependency = if (id != null) parse(get("version/$id")!!) else if (projectId != null) {
                    val compatible = versions(projectId, type, mc)
                    compatible.firstOrNull { it.releaseType == "release" } ?: compatible.firstOrNull() ?: error("依赖缺少兼容版本：$projectId")
                } else error("依赖未提供项目或版本 ID")
                visit(dependency)
            }
            visiting.remove(v.id); result[v.project] = v
        }
        visit(version)
        return result.values.toList()
    }
    fun download(version: Version, directory: File, progress: (Long, Long) -> Unit): File {
        val file = version.files.firstOrNull { it.name.endsWith(".jar", true) } ?: error("没有可安装的 JAR 文件")
        require(file.url.toHttpUrl().scheme == "https") { "下载地址必须使用 HTTPS" }
        require(file.sha512 != null || file.sha1 != null) { "下载缺少校验值" }
        val target = ManagedFiles(directory).child(directory, file.name)
        client.newCall(Request.Builder().url(file.url).header("User-Agent", "MC-Mobile-Server/Android").build()).execute().use { response ->
            check(response.isSuccessful) { "下载失败：HTTP ${response.code}" }
            val body = response.body ?: error("下载内容为空")
            val max = 1024L * 1024 * 1024
            var bytes = 0L
            body.byteStream().use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; bytes += n; require(bytes <= max) { "文件大小异常" }; output.write(buffer, 0, n); progress(bytes, body.contentLength()) }
            } }
        }
        val valid = if (file.sha512 != null) hash(target) == file.sha512 else hash(target, "SHA-1") == file.sha1
        if (!valid) { target.delete(); error("下载校验失败") }
        return target
    }
}
