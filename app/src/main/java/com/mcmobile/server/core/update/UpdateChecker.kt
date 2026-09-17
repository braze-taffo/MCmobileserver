package com.mcmobile.server.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AppRelease(val version: String, val notes: String, val pageUrl: String, val apkUrl: String)

sealed interface UpdateResult {
    data object NoRelease : UpdateResult
    data object Current : UpdateResult
    data object NoCompatibleApk : UpdateResult
    data class Available(val release: AppRelease) : UpdateResult
}

class UpdateChecker(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).build(),
    private val endpoint: String = "https://api.github.com/repos/braze-taffo/MCmobileserver/releases/latest",
) {
    suspend fun check(current: String, abis: List<String>): UpdateResult = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(endpoint)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "MC-Mobile-Server/$current").build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext UpdateResult.NoRelease
            if (!response.isSuccessful) throw IOException(
                if (response.code == 403 || response.code == 429) "GitHub 请求受限，请稍后再试"
                else "GitHub 请求失败（${response.code}）",
            )
            parse(response.body?.string() ?: throw IOException("GitHub 返回了空响应"), current, abis)
        }
    }

    companion object {
        const val REPOSITORY = "https://github.com/braze-taffo/MCmobileserver"
        private fun version(value: String): List<Long>? {
            val match = Regex("^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(value) ?: return null
            return match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
        }

        fun newer(candidate: String, current: String): Boolean {
            val next = version(candidate) ?: throw IOException("发行版本号格式不正确")
            val installed = version(current) ?: throw IOException("当前版本号格式不正确")
            return next.zip(installed).firstOrNull { it.first != it.second }?.let { it.first > it.second } ?: false
        }

        fun parse(body: String, current: String, abis: List<String>): UpdateResult {
            val release = Json.parseToJsonElement(body).jsonObject
            if (release["draft"]?.jsonPrimitive?.booleanOrNull == true ||
                release["prerelease"]?.jsonPrimitive?.booleanOrNull == true) return UpdateResult.NoRelease
            val tag = release["tag_name"]?.jsonPrimitive?.content ?: throw IOException("发行信息缺少版本号")
            if (!newer(tag, current)) return UpdateResult.Current
            val normalized = tag.removePrefix("v").removePrefix("V")
            val acceptedNames = abis.map { "mcmobileserver-$normalized-$it.apk" } + "mcmobileserver-$normalized-universal.apk"
            val assets = release["assets"]?.jsonArray.orEmpty()
            val asset = acceptedNames.firstNotNullOfOrNull { name ->
                assets.map { it.jsonObject }.firstOrNull {
                    it["name"]?.jsonPrimitive?.content == name &&
                        it["state"]?.jsonPrimitive?.content == "uploaded" &&
                        (it["size"]?.jsonPrimitive?.longOrNull ?: 0) > 0
                }
            } ?: return UpdateResult.NoCompatibleApk
            val download = asset["browser_download_url"]?.jsonPrimitive?.content ?: throw IOException("缺少 APK 下载地址")
            require(download.startsWith("$REPOSITORY/releases/download/") && download.endsWith(".apk")) { "APK 下载地址不属于本项目" }
            return UpdateResult.Available(AppRelease(
                normalized,
                release["body"]?.jsonPrimitive?.contentOrNull?.take(20000).orEmpty().ifBlank { "作者暂未填写更新说明。" },
                "$REPOSITORY/releases/tag/$tag", download,
            ))
        }
    }
}
