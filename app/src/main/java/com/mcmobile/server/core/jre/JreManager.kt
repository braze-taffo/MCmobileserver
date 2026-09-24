package com.mcmobile.server.core.jre

import android.content.Context
import android.os.Build
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

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

    /**
     * libjvm 在 javaHome 里的标准位置。
     *
     * HotSpot 不认 `-Djava.home=`：`os::init_2()` 会用 dladdr 取 libjvm.so 的实际加载路径，
     * 去掉 /libjvm.so、/{server|client}、/lib 三级后**无条件覆盖** java.home，紧接着检查
     * `<java.home>/lib/modules`，不存在就 `Failed setting boot class path.` 直接 exit(1)。
     * 所以 libjvm 必须从磁盘上的这个位置 dlopen —— 直接用 APK 内嵌路径（...base.apk!/lib/...）
     * 反推出来的 java.home 是 `.../base.apk!`，必然失败。
     */
    const val LIBJVM_REL = "lib/server/libjvm.so"

    private val _state = MutableStateFlow(JreState())
    val state: StateFlow<JreState> = _state.asStateFlow()

    /** 同进程串行化解压：设置页连点/重入时不允许两个解压任务互相清场 */
    private val mutex = Mutex()

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

    fun libjvmFile(context: Context, javaMajor: Int = 21): File =
        File(javaHomeDir(context, javaMajor), LIBJVM_REL)

    fun isReady(context: Context, javaMajor: Int = 21): Boolean {
        val home = javaHomeDir(context, javaMajor)
        return File(home, "lib/modules").exists() && File(home, ".mcs-extracted").exists() &&
            libjvmFile(context, javaMajor).exists()
    }

    /**
     * 把 APK 里的 `lib/<abi>/libjvm<major>.so` 落一份到 `<javaHome>/lib/server/libjvm.so`。
     * 原生库仍然由 APK 提供（jniLibs 打包），这里只是让它出现在 HotSpot 期望的位置。
     */
    fun materializeLibjvm(context: Context, javaMajor: Int = 21): File {
        val dest = libjvmFile(context, javaMajor)
        if (dest.exists() && dest.length() > 0) return dest
        val abi = supportedAbi() ?: throw IllegalStateException("不支持的设备架构")
        val entryName = "lib/$abi/libjvm$javaMajor.so"
        dest.parentFile?.mkdirs()
        // 先写临时文件再换名：直写 dest 的话拷贝中途被杀会留下"非空但残缺"的假完成文件，
        // 之后所有启动都会 dlopen 失败且 isReady 仍显示就绪
        val tmp = File(dest.parentFile, "libjvm.so.tmp-${android.os.Process.myPid()}")
        java.util.zip.ZipFile(File(context.applicationInfo.sourceDir)).use { zf ->
            val entry = zf.getEntry(entryName)
                ?: throw IllegalStateException("APK 内缺少原生库 $entryName")
            zf.getInputStream(entry).use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
        }
        setPerms(tmp, executable = true)
        dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            throw IllegalStateException("libjvm 落盘失败: ${dest.absolutePath}")
        }
        return dest
    }

    /** 幂等：已解压则直接返回；否则后台解压并更新 [state]。 */
    suspend fun ensureExtracted(context: Context, javaMajor: Int = 21) = withContext(Dispatchers.IO) {
        mutex.withLock { ensureExtractedLocked(context.applicationContext, javaMajor) }
    }

    private suspend fun ensureExtractedLocked(appContext: Context, javaMajor: Int) {
        val abi = supportedAbi()
        if (abi == null) {
            _state.update { it.copy(status = JreStatus.FAILED, error = "不支持的设备架构（需要 arm64 或 x86_64）") }
            return
        }
        val home = javaHomeDir(appContext, javaMajor)
        val extracted = File(home, "lib/modules").exists() && File(home, ".mcs-extracted").exists()
        if (extracted) {
            // 已解压过：只补齐可能缺失的 libjvm（老安装结果里没有），避免重解压几百 MB
            try {
                materializeLibjvm(appContext, javaMajor)
                _state.update {
                    it.copy(
                        status = JreStatus.READY, abi = abi, javaHome = home, javaMajor = javaMajor,
                        javaVersion = readJavaVersion(home), progress = 1f, error = null,
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(status = JreStatus.FAILED, error = t.message ?: t.toString()) }
            }
            return
        }

        _state.update {
            it.copy(status = JreStatus.EXTRACTING, abi = abi, javaHome = home, javaMajor = javaMajor, progress = 0f)
        }
        // UI 进程与 :server 进程可能同时解压同一套 JRE（跨进程 mutex 管不到）：
        // 全程写进本进程独有的 staging 目录，全部就绪后一次性切换成 home。
        // 无论哪一方被杀/失败，home 要么是旧内容要么是某一方完整切换的结果，
        // 绝不会出现"带 .mcs-extracted 标记的残缺目录"。
        val staging = File(home.parentFile, "staging-$javaMajor-${android.os.Process.myPid()}")
        try {
            staging.deleteRecursively()
            home.parentFile?.mkdirs()

            // assets 流直接接 ZipInputStream 在部分设备上会解出 0 字节小文件，
            // 先原样拷成临时文件再用 ZipFile 按条目读，稳定且能取真实总数算进度。
            // 文件名带 pid：并发解压时不会互相截断对方的临时 zip
            val tmpZip = File(appContext.cacheDir, "jre-$abi-$javaMajor-${android.os.Process.myPid()}.zip")
            appContext.assets.open("jre/$abi/$javaMajor.zip").use { input ->
                tmpZip.outputStream().use { input.copyTo(it) }
            }
            java.util.zip.ZipFile(tmpZip).use { zf ->
                val entries = zf.entries().toList()
                var count = 0
                for (entry in entries) {
                    val out = File(staging, entry.name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        zf.getInputStream(entry).use { input ->
                            out.outputStream().use { input.copyTo(it) }
                        }
                        setPerms(out, executable = entry.name.endsWith(".so"))
                    }
                    count++
                    if (count % 20 == 0) {
                        _state.update {
                            it.copy(progress = (count.toFloat() / entries.size).coerceAtMost(0.99f))
                        }
                    }
                }
            }
            tmpZip.delete()
            // 标记先写进 staging 再切换：renameTo 成功的瞬间，home 一定携带完整内容
            File(staging, ".mcs-extracted").writeText("abi=$abi java=$javaMajor")
            home.deleteRecursively()
            if (!staging.renameTo(home)) {
                // 并发方刚把完整的 home 切换上来的情形：让位，自己重走快速路径即可
                throw IllegalStateException("JRE 目录切换失败：${staging.absolutePath}")
            }
            // libjvm 在切换后补齐：此处即使被杀，home 也完整，下次启动走上面的快速路径自愈
            materializeLibjvm(appContext, javaMajor)
            _state.update {
                it.copy(
                    status = JreStatus.READY, progress = 1f, javaMajor = javaMajor,
                    javaVersion = readJavaVersion(home), error = null,
                )
            }
        } catch (t: Throwable) {
            // 只清自己的 staging。home 可能是并发方刚切换好的完整目录，绝不能删
            staging.deleteRecursively()
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

    /** 修复入口：删除后重新解压（设置页「重新解压」走这里；ensureExtracted 对已就绪目录只补 libjvm） */
    suspend fun repair(context: Context, javaMajor: Int = 21) = withContext(Dispatchers.IO) {
        mutex.withLock {
            javaHomeDir(context.applicationContext, javaMajor).deleteRecursively()
            ensureExtractedLocked(context.applicationContext, javaMajor)
        }
    }
}
