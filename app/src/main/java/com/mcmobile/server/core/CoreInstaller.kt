package com.mcmobile.server.core

import android.content.Context
import com.mcmobile.server.core.console.ConsoleSession
import com.mcmobile.server.core.storage.StorageLocation
import com.mcmobile.server.data.InstanceRepository
import com.mcmobile.server.data.ServerInstance
import com.mcmobile.server.data.ServerType
import com.mcmobile.server.data.StorageKind
import com.mcmobile.server.data.api.CoreApi
import com.mcmobile.server.service.ServerForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** 下载核心文件到实例目录（Vanilla/Paper/Folia/Fabric 完成即用；Forge/NeoForge 还需跑安装器） */
class CoreInstaller(private val context: Context, private val repo: InstanceRepository) {

    sealed interface Progress {
        data class Downloading(val message: String, val done: Long, val total: Long) : Progress
        data class Message(val message: String) : Progress
        data class Done(val installed: Boolean) : Progress
        data class Error(val message: String) : Progress
    }

    private val _progress = MutableStateFlow<Progress>(Progress.Message(""))
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /**
     * 每次操作前把进度复位。
     *
     * [progress] 是常驻的 StateFlow，下载完若不复位，最后一个"下载中 52/52MB"会一直留着，
     * 新建下一个实例时配置页会拿它冒充本次进度（真机复现：连续两次看到上一个已删除实例的体积）。
     */
    private fun resetProgress() {
        _progress.value = Progress.Message("")
    }

    private fun cacheDir(): File = File(context.filesDir, "api-cache")

    suspend fun install(instance: ServerInstance): Boolean = withContext(Dispatchers.IO) {
        resetProgress()
        try {
            val dir = repo.instanceDir(instance)
            when (instance.type) {
                ServerType.VANILLA -> {
                    val versions = CoreApi.vanillaVersions()
                    val v = versions.firstOrNull { it.id == instance.mcVersion }
                        ?: error("Mojang 清单中找不到 ${instance.mcVersion}")
                    val detail = CoreApi.vanillaVersionDetail(v.id, v.url, cacheDir())
                    val url = detail.serverUrl ?: error("${instance.mcVersion} 无服务端下载")
                    _progress.value = Progress.Message("下载原版服务端 ${instance.mcVersion} …")
                    val jar = CoreApi.download(url, File(dir, "server.jar"), null) { d, t ->
                        _progress.value = Progress.Downloading("原版服务端", d, t)
                    }
                    reconcile(instance, jar)
                    true
                }

                ServerType.PAPER, ServerType.FOLIA -> {
                    val project = if (instance.type == ServerType.FOLIA) "folia" else "paper"
                    val dl = CoreApi.paperDownload(instance.mcVersion, project)
                    _progress.value = Progress.Message(
                        "下载 ${instance.type.label} ${instance.mcVersion} build ${dl.build} …",
                    )
                    val jar = CoreApi.download(dl.url, File(dir, "server.jar"), dl.sha256) { d, t ->
                        _progress.value = Progress.Downloading("${instance.type.label} 核心", d, t)
                    }
                    reconcile(instance, jar)
                    true
                }

                ServerType.FABRIC -> {
                    // Fabric server launcher 需要旁边的原版 server.jar
                    val versions = CoreApi.vanillaVersions()
                    val v = versions.firstOrNull { it.id == instance.mcVersion }
                        ?: error("Mojang 清单中找不到 ${instance.mcVersion}")
                    val detail = CoreApi.vanillaVersionDetail(v.id, v.url, cacheDir())
                    val vanillaUrl = detail.serverUrl ?: error("${instance.mcVersion} 无服务端下载")
                    CoreApi.download(vanillaUrl, File(dir, "server.jar")) { d, t ->
                        _progress.value = Progress.Downloading("原版服务端", d, t)
                    }
                    val loader = CoreApi.newestStable(CoreApi.fabricLoaderVersions())
                        ?: error("Fabric 没有可用的 loader 版本")
                    val installer = CoreApi.newestStable(CoreApi.fabricInstallerVersions())
                        ?: error("Fabric 没有可用的 installer 版本")
                    _progress.value = Progress.Message("下载 Fabric loader $loader …")
                    val launcher = CoreApi.download(
                        CoreApi.fabricServerJarUrl(instance.mcVersion, loader, installer),
                        File(dir, "fabric-server-launch.jar"),
                    ) { d, t ->
                        _progress.value = Progress.Downloading("Fabric 启动器", d, t)
                    }
                    reconcile(instance, launcher)
                    true
                }

                ServerType.FORGE, ServerType.NEOFORGE -> {
                    val url = if (instance.type == ServerType.FORGE) {
                        CoreApi.forgeInstallerUrl(instance.mcVersion, instance.coreVersion!!)
                    } else {
                        CoreApi.neoForgeInstallerUrl(instance.coreVersion!!)
                    }
                    _progress.value = Progress.Message("下载 ${instance.type} 安装器 ${instance.coreVersion} …")
                    CoreApi.download(url, File(dir, "installer.jar")) { d, t ->
                        _progress.value = Progress.Downloading("安装器", d, t)
                    }
                    // 安装器通过 :server 进程里的 JVM 执行，见 ServerController.continueInstall
                    false
                }
            }
        } catch (t: Throwable) {
            _progress.value = Progress.Error(t.message ?: t.toString())
            false
        }
    }

    /**
     * 用核心 jar 自带的权威版本信息校正实例元数据。
     * jar 内部（version.json / install.properties）一定有真实版本，用它兜住
     * "列表或文件名把版本给错"的情况——否则会一路错到用错 JRE。
     */
    private suspend fun reconcile(instance: ServerInstance, jar: File): ServerInstance {
        val detected = CoreJarInspector.detect(jar) ?: return instance
        val javaMajor = detected.requiredJava?.let { McVersions.bundledJavaMajorFor(it) }
            ?: McVersions.bundledJavaMajor(detected.mcVersion)
        if (detected.mcVersion == instance.mcVersion && javaMajor == instance.javaMajor) return instance
        val fixed = instance.copy(mcVersion = detected.mcVersion, javaMajor = javaMajor)
        repo.update(fixed)
        _progress.value = Progress.Message(
            "核心实为 ${instance.type.label} ${detected.mcVersion}（Java $javaMajor），已校正实例信息",
        )
        return fixed
    }

    /** 从 SAF 导入的核心 jar 复制进实例目录；复制成功即 true */
    suspend fun importJar(instance: ServerInstance, source: File): Boolean = withContext(Dispatchers.IO) {
        resetProgress()
        try {
            val target = File(repo.instanceDir(instance), "server.jar")
            source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            _progress.value = Progress.Done(true)
            true
        } catch (t: Throwable) {
            _progress.value = Progress.Error(t.message ?: t.toString())
            false
        }
    }

    /** SAF 导入的安装器 jar（Forge/NeoForge）：复制成功即 true，真正的安装由 :server 进程跑 */
    suspend fun importInstaller(instance: ServerInstance, source: File): Boolean = withContext(Dispatchers.IO) {
        resetProgress()
        try {
            val target = File(repo.instanceDir(instance), "installer.jar")
            source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            true
        } catch (t: Throwable) {
            _progress.value = Progress.Error(t.message ?: t.toString())
            false
        }
    }
}

/** UI 进程的启动/停止控制 + EULA + Forge/NeoForge 安装编排 */
class ServerController(private val context: Context, private val repo: InstanceRepository) {

    sealed interface StartResult {
        data object Started : StartResult
        data class NeedEula(val instance: ServerInstance) : StartResult
        data class Error(val message: String) : StartResult
    }

    suspend fun start(instance: ServerInstance): StartResult {
        val dir = repo.instanceDir(instance)
        // 「所有文件访问权限」被撤销后，外部目录连 stat 都会被拒，看起来就像"目录不存在"。
        // 这是可恢复的（重新授权即可），必须说清楚而不是让用户去找目录。
        if (instance.storage == StorageKind.EXTERNAL && !StorageLocation.hasWriteAccess(context)) {
            return StartResult.Error(
                "没有「所有文件访问权限」，无法访问外部目录：${dir.absolutePath}\n" +
                        "可在设置页重新授予该权限，或把实例建在应用内部存储。",
            )
        }
        // 外部目录还可能被用户移走/改名，或所在存储卷没挂载；在启动前说清楚，
        // 好过把 :server 进程拉起来再报一句"实例目录不存在"
        if (!dir.isDirectory) {
            return StartResult.Error(
                if (instance.storage == StorageKind.EXTERNAL) {
                    "实例目录不存在：${dir.absolutePath}\n" +
                            "外部目录可能已被移动或改名，或所在存储未挂载。"
                } else {
                    "实例目录不存在：${dir.absolutePath}"
                },
            )
        }
        if (instance.needsInstaller && !instance.installed) {
            // 安装产物（unix_args.txt）可能已经在了，只是上次没来得及回写 installed
            // （安装器跑完进程被强制结束、App 被杀等）。这种情况直接自愈，别白白再装一遍。
            if (verifyInstalled(instance)) {
                return start(instance.copy(installed = true))
            }
            // 需要（重新）跑安装器：下载好了 installer.jar 才会走到这里
            val installer = File(dir, "installer.jar")
            if (!installer.exists()) return StartResult.Error("缺少 installer.jar，请先重建/下载核心")
            return runInstallerInternal(instance)
        }

        if (!Eula.isAccepted(dir)) return StartResult.NeedEula(instance)

        // JRE 由 :server 进程在启动时保证解压
        val home = com.mcmobile.server.core.jre.JreManager.javaHomeDir(context, instance.javaMajor)
        val built = com.mcmobile.server.core.launch.LaunchSpecBuilder.build(instance, dir, home)
        val spec = built.spec ?: return StartResult.Error(built.error ?: "构建启动参数失败")

        ServerForegroundService.start(context, spec)
        return StartResult.Started
    }

    /** 运行 Forge/NeoForge 安装器（同样走 :server 进程的 JVM） */
    private fun runInstallerInternal(instance: ServerInstance): StartResult {
        val dir = repo.instanceDir(instance)
        val installer = File(dir, "installer.jar")
        val mainClass = com.mcmobile.server.core.launch.ManifestReader.readMainClass(installer)
            ?: return StartResult.Error("无法读取安装器 Main-Class")
        val spec = com.mcmobile.server.core.launch.LaunchSpec(
            instanceId = instance.id,
            name = "${instance.name}（安装中）",
            workDir = dir.absolutePath,
            javaHome = com.mcmobile.server.core.jre.JreManager.javaHomeDir(context, instance.javaMajor).absolutePath,
            classpath = installer.absolutePath,
            mainClass = mainClass,
            jvmOpts = listOf("-Xms128M", "-Xmx768M"),
            args = listOf("--installServer"),
            javaMajor = instance.javaMajor,
            // 安装器是"跑完即止"的工具类：main 返回就代表装完了。
            // 它跑完会残留一个停住的非守护线程，若等 DestroyJavaVM 会永远等下去，
            // 进程不退、UI 一直卡在"安装中"。
            waitForNonDaemonThreads = false,
        )
        ServerForegroundService.start(context, spec)
        return StartResult.Started
    }

    /** 安装器跑完后调用：检测 unix_args.txt → 标记 installed */
    suspend fun verifyInstalled(instance: ServerInstance): Boolean {
        val dir = repo.instanceDir(instance)
        val ok = com.mcmobile.server.core.launch.LaunchArgfiles.findUnixArgsFile(dir) != null
        if (ok && !instance.installed) repo.update(instance.copy(installed = true))
        return ok
    }

    fun stop(force: Boolean) = ServerForegroundService.requestStop(context, force)

    /**
     * 停止当前服务器并等 :server 进程真的退出。
     *
     * 删除实例前必须先走这一步：进程的运行目录就是实例目录，目录删掉后它不会自己退出，
     * 会继续监听 25565 并持续写日志（真机复现过：列表已空、端口仍在监听，Server List Ping 还能连上）。
     *
     * @return true 表示确认已停（或本来就没在跑）；false 表示超时仍在跑
     */
    suspend fun stopAndWait(gracefulMs: Long = 12_000, forceMs: Long = 8_000): Boolean {
        if (!ConsoleSession.isConnected()) ConsoleSession.connect(attempts = 4)
        // 连不上 :server 进程 = 本来就没有服务器在跑
        if (!ConsoleSession.isConnected()) return true
        stop(force = false)
        if (awaitExit(gracefulMs)) return true
        stop(force = true)
        return awaitExit(forceMs)
    }

    /** 等 :server 进程断开连接（socket 关闭即代表进程收尾退出） */
    private suspend fun awaitExit(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
        ConsoleSession.state.first { it.status == "EXITED" }
    } != null
}
