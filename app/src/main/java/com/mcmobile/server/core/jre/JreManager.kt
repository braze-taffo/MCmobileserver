package com.mcmobile.server.core.jre

import android.content.Context
import android.os.Build
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

enum class JreStatus { NOT_READY, EXTRACTING, READY, FAILED }

data class JreState(
    val status: JreStatus = JreStatus.NOT_READY,
    val abi: String? = null,
    val javaHome: File? = null,
    val javaVersion: String? = null,
    /** 正在准备的 Java 大版本（21/25） */
    val javaMajor: Int = 21,
    /** 0f..1f，解压进度（按条目数估算） */
    val progress: Float = 0f,
    val error: String? = null,
)

/**
 * 管理内嵌 JRE（21 与 25 两个运行时，按 MC 版本需求选择）：
 * 构建期已将各 JRE 拆为 APK 的 jniLibs/libjvm<major>.so + assets/jre/<abi>/<major>.zip，
 * 这里负责把 zip 解压到 files/jre/<abi>/<major>/ 形成完整 javaHome。
 */
object JreManager {

    /** 应用打包的 Java 大版本 */
    val AVAILABLE_MAJORS = listOf(21, 25)

    private const val ZIP_ENTRY_COUNT_GUESS = 280

    private val _state = MutableStateFlow(JreState())
    val state: StateFlow<JreState> = _state.asStateFlow()

    fun supportedAbi(): String? {
        val primary = Build.SUPPORTED_ABIS.firstOrNull() ?: return null
        return when {
            primary.startsWith("arm64") -> "arm64-v8a"
            primary == "x86_64" -> "x86_64"
            else -> null
        }
    }

    fun javaHomeDir(context: Context, javaMajor: Int = 21): File =
        File(context.filesDir, "jre/${supportedAbi() ?: "unknown"}/$javaMajor")

    fun isReady(context: Context, javaMajor: Int = 21): Boolean {
        val home = javaHomeDir(context, javaMajor)
        return File(home, "lib/modules").exists() && File(home, ".mcs-extracted").exists()
    }

    /** 幂等：已解压则直接返回；否则后台解压并更新 [state]。 */
    suspend fun ensureExtracted(context: Context, javaMajor: Int = 21) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val abi = supportedAbi()
        if (abi == null) {
            _state.update { it.copy(status = JreStatus.FAILED, error = "不支持的设备架构（需要 arm64 或 x86_64）") }
            return@withContext
        }
        val home = javaHomeDir(appContext, javaMajor)
        if (isReady(appContext, javaMajor)) {
            _state.update {
                it.copy(
                    status = JreStatus.READY, abi = abi, javaHome = home, javaMajor = javaMajor,
                    javaVersion = readJavaVersion(home), progress = 1f, error = null,
                )
            }
            return@withContext
        }

        _state.update {
            it.copy(status = JreStatus.EXTRACTING, abi = abi, javaHome = home, javaMajor = javaMajor, progress = 0f)
        }
        try {
            home.deleteRecursively()
            home.parentFile?.mkdirs()
            var count = 0
            appContext.assets.open("jre/$abi/$javaMajor.zip").use { asset ->
                ZipInputStream(asset.buffered(1 shl 16)).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        val out = File(home, entry.name)
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile.mkdirs()
                            zis.copyTo(out.outputStream().buffered(1 shl 16))
                            setPerms(out, executable = entry.name.endsWith(".so"))
                        }
                        zis.closeEntry()
                        count++
                        if (count % 20 == 0) {
                            _state.update { it.copy(progress = (count.toFloat() / ZIP_ENTRY_COUNT_GUESS).coerceAtMost(0.99f)) }
                        }
                    }
                }
            }
            File(home, ".mcs-extracted").writeText("abi=$abi java=$javaMajor")
            _state.update {
                it.copy(
                    status = JreStatus.READY, progress = 1f, javaMajor = javaMajor,
                    javaVersion = readJavaVersion(home), error = null,
                )
            }
        } catch (t: Throwable) {
            home.deleteRecursively()
            _state.update { it.copy(status = JreStatus.FAILED, error = t.message ?: t.toString()) }
        }
    }

    private fun setPerms(file: File, executable: Boolean) {
        try {
            Os.chmod(file.absolutePath, if (executable) 0b111_101_101 else 0b110_100_100)
        } catch (_: Exception) {
            // 部分文件系统不支持 chmod，dlopen 对执行位并不强制
        }
    }

    private fun readJavaVersion(home: File): String? = runCatching {
        File(home, "release").readLines()
            .firstOrNull { it.startsWith("JAVA_VERSION=") }
            ?.substringAfter('"')?.substringBefore('"')
    }.getOrNull()

    /** 修复入口：删除后重新解压 */
    suspend fun repair(context: Context, javaMajor: Int = 21) {
        javaHomeDir(context, javaMajor).deleteRecursively()
        ensureExtracted(context, javaMajor)
    }
}
