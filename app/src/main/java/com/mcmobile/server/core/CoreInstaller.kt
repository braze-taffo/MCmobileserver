package com.mcmobile.server.core

import android.content.Context
import com.mcmobile.server.data.InstanceRepository
import com.mcmobile.server.data.ServerInstance
import com.mcmobile.server.data.ServerType
import com.mcmobile.server.data.api.CoreApi
import com.mcmobile.server.service.ServerForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** 下载核心文件到实例目录（Vanilla/Paper/Fabric 完成即用；Forge/NeoForge 还需跑安装器） */
class CoreInstaller(private val context: Context, private val repo: InstanceRepository) {

    sealed interface Progress {
        data class Downloading(val message: String, val done: Long, val total: Long) : Progress
        data class Message(val message: String) : Progress
        data class Done(val installed: Boolean) : Progress
        data class Error(val message: String) : Progress
    }

    private val _progress = MutableStateFlow<Progress>(Progress.Message(""))
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private fun cacheDir(): File = File(context.filesDir, "api-cache")

    suspend fun install(instance: ServerInstance): Boolean = withContext(Dispatchers.IO) {
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
                    CoreApi.download(url, File(dir, "server.jar"), null) { d, t ->
                        _progress.value = Progress.Downloading("原版服务端", d, t)
                    }
                    true
                }

                ServerType.PAPER -> {
                    val dl = CoreApi.paperDownload(instance.mcVersion)
                    _progress.value = Progress.Message("下载 Paper ${instance.mcVersion} build ${dl.build} …")
                    CoreApi.download(dl.url, File(dir, "server.jar"), dl.sha256) { d, t ->
                        _progress.value = Progress.Downloading("Paper 核心", d, t)
                    }
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
                    val loader = CoreApi.fabricLoaderVersions().first()
                    _progress.value = Progress.Message("下载 Fabric loader $loader …")
                    CoreApi.download(
                        CoreApi.fabricServerJarUrl(instance.mcVersion, loader),
                        File(dir, "fabric-server-launch.jar"),
                    ) { d, t ->
                        _progress.value = Progress.Downloading("Fabric 启动器", d, t)
                    }
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

    /** 从 SAF 导入的核心 jar 复制进实例目录，尽量识别类型与版本 */
    suspend fun importJar(instance: ServerInstance, source: File): Boolean = withContext(Dispatchers.IO) {
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

    /** SAF 导入的安装器 jar（Forge/NeoForge） */
    suspend fun importInstaller(instance: ServerInstance, source: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val target = File(repo.instanceDir(instance), "installer.jar")
            source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            false
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
}
