package com.mcmobile.server.core.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File
import java.net.URLDecoder

/**
 * 「用户可访问目录」的解析、校验与权限判定。
 *
 * 文件夹选择器（SAF）给的是 `content://` URI，而内嵌 JVM 的 cwd 必须是真实路径，
 * 所以要把
 * `content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FMCServers`
 * 映射回 `/storage/emulated/0/Documents/MCServers`。只有 ExternalStorageProvider 的
 * tree 能这样映射（云盘、媒体库之类没有真实路径），其余一律拒绝——否则实例会被建到
 * 一个 JVM 根本打不开的位置。
 *
 * 单纯拿到路径还不够：Android 11 起应用即使知道了路径，也必须持有「所有文件访问权限」
 * 才能按路径读写共享存储，所以创建流程要先确保 [hasWriteAccess] 为真。
 */
object StorageLocation {

    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    sealed interface Resolution {
        /** 映射成功，[dir] 是可以交给 java.io.File 使用的真实路径 */
        data class Ok(val dir: File) : Resolution

        /** 该选择不能被用作实例目录，[reason] 直接展示给用户 */
        data class Rejected(val reason: String) : Resolution
    }

    /**
     * 把 SAF tree URI 映射为真实目录。纯字符串运算，不依赖 Android，故可单测。
     *
     * @param volumes 存储卷 id → 卷根目录，例如 `primary` → `/storage/emulated/0`
     */
    fun resolveTree(uri: String, volumes: Map<String, File>): Resolution {
        if (!uri.startsWith("content://")) return Resolution.Rejected("这不是一个文件夹地址")

        val body = uri.removePrefix("content://")
        val slash = body.indexOf('/')
        if (slash <= 0) return Resolution.Rejected("这不是一个文件夹地址")

        val authority = body.substring(0, slash)
        val segments = body.substring(slash + 1).split('/')
        if (authority != EXTERNAL_STORAGE_AUTHORITY) {
            return Resolution.Rejected("这里没有真实的文件路径（云盘、媒体库等），请选择本机存储里的文件夹")
        }
        if (segments.size < 2 || segments[0] != "tree") return Resolution.Rejected("这不是一个文件夹地址")

        val docId = percentDecode(segments[1])
        val colon = docId.indexOf(':')
        if (colon <= 0) return Resolution.Rejected("无法识别所选文件夹：$docId")

        val volumeId = docId.substring(0, colon)
        val relative = docId.substring(colon + 1)
        val root = volumes[volumeId]
            ?: return Resolution.Rejected("无法识别的存储卷：$volumeId（存储卡可能已拔出）")

        val parts = relative.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return Resolution.Rejected("请不要选择整个存储，建议先新建一个专用文件夹")
        if (parts.any { it == "." || it == ".." }) return Resolution.Rejected("文件夹路径不合法")
        if (parts.first().equals("Android", ignoreCase = true)) {
            return Resolution.Rejected("Android 目录受系统保护，请选择其他文件夹")
        }

        val dir = File(root, parts.joinToString("/"))
        // 规范化后必须仍在所选卷内，挡掉符号链接/`..` 之类的越界
        return try {
            val base = root.canonicalPath.trimEnd(File.separatorChar)
            val target = dir.canonicalPath
            if (target == base || target.startsWith(base + File.separator)) Resolution.Ok(dir)
            else Resolution.Rejected("文件夹路径不合法")
        } catch (e: Exception) {
            Resolution.Rejected("无法解析所选文件夹：${e.message ?: "路径不可用"}")
        }
    }

    /** 从系统选择器拿到的 URI 解析成真实目录 */
    fun resolvePicked(uri: Uri): Resolution = resolveTree(uri.toString(), volumes())

    /** 当前已挂载的存储卷。主共享存储的卷 id 固定是 `primary`，可移动卷（SD 卡）由系统分配。 */
    fun volumes(): Map<String, File> {
        val map = mutableMapOf<String, File>()
        map["primary"] = Environment.getExternalStorageDirectory()
        val storage = File("/storage")
        storage.listFiles()?.forEach { f ->
            val name = f.name
            if (f.isDirectory && !name.equals("emulated", true) &&
                !name.equals("self", true) && !name.equals("enc_emulated", true)
            ) {
                map[name] = f
            }
        }
        return map
    }

    /** 目录可写性探测：返回 null 表示可用，否则是给用户看的原因 */
    fun checkWritable(dir: File): String? = when {
        !dir.exists() -> "文件夹不存在：${dir.absolutePath}"
        !dir.isDirectory -> "这不是一个文件夹：${dir.absolutePath}"
        else -> try {
            val probe = File(dir, ".mcs-write-test")
            probe.writeText("ok")
            probe.delete()
            null
        } catch (e: Exception) {
            "没有写入权限：${dir.absolutePath}（${e.message ?: "未知错误"}）"
        }
    }

    /** 外部实例使用前的可用性检查：返回 null 表示可用 */
    fun availabilityError(instanceDir: File): String? = when {
        !instanceDir.exists() ->
            "实例目录不存在：${instanceDir.absolutePath}\n外部目录可能已被移动或改名，或所在存储未挂载。"
        !instanceDir.isDirectory -> "实例路径不是一个文件夹：${instanceDir.absolutePath}"
        !instanceDir.canWrite() -> "实例目录不可写：${instanceDir.absolutePath}"
        else -> null
    }

    /**
     * 是否具备按真实路径读写共享存储的权限。
     * Android 11+ 走「所有文件访问权限」（MANAGE_EXTERNAL_STORAGE），10 及以下走运行时写权限。
     */
    fun hasWriteAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** 授权页面；低于 Android 11 时该权限不在应用详情页，没有更好的入口 */
    fun permissionSettingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            )
        }

    /**
     * Uri.decode 的等价实现：只解 %XX，不把 `+` 当空格（`URLDecoder` 会，文件夹名里的
     * `+` 会被吃掉）。
     */
    internal fun percentDecode(s: String): String {
        if ('%' !in s) return s
        return runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
    }
}
