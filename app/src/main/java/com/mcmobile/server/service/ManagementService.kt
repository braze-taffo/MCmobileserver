package com.mcmobile.server.service

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import com.mcmobile.server.R
import com.mcmobile.server.core.ServerController
import com.mcmobile.server.core.console.ConsoleSession
import com.mcmobile.server.core.ddns.DdnsRuntime
import com.mcmobile.server.core.manage.*
import com.mcmobile.server.data.*
import kotlinx.coroutines.*
import java.io.File

/** Scheduler follows an already-running server, independently of UI composition. */
class ManagementService : Service() {
    companion object {
        fun start(context: Context) { context.startForegroundService(Intent(context, ManagementService::class.java)) }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val next = hashMapOf<String, Long>()
    private val definitions = hashMapOf<String, ServerTask>()
    private var started = false
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("mcs_manage", "服务器管理任务", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, "mcs_manage").setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("服务器管理正在运行").setContentText("运行期间执行已启用的备份、重启、命令和动态域名").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1003, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1003, notification)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mcs:management").apply { acquire() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) {
            started = true
            scope.launch { runLoop() }
            DdnsRuntime.run(applicationContext, scope)
        }
        return START_NOT_STICKY
    }
    private fun log(root: File?, message: String) {
        val file = if (root != null && root.isDirectory) File(root, "logs/management.log") else File(filesDir, "management.log")
        file.parentFile!!.mkdirs()
        if (file.length() > 2 * 1048576) com.mcmobile.server.core.files.ManagedFiles.replace(file, File(file.parentFile, "management.previous.log"))
        file.appendText("${java.time.Instant.now()} $message\n")
    }
    private suspend fun runLoop() {
        val repo = InstanceRepository.get(this)
        repo.load()
        val store = TaskStore(File(filesDir, "server-tasks.json"))
        val controller = ServerController(this, repo)
        try {
            var misses = 0
            while (scope.isActive) {
                val active = ConsoleSession.activeServer(attempts = 1)
                if (active == null) {
                    next.clear(); definitions.clear()
                    if (++misses > 20) break
                    delay(3000); continue
                }
                misses = 0
                val instance = active.instanceId?.let(repo::byId)
                if (instance == null || active.status != "RUNNING") { delay(3000); continue }
                val root = repo.instanceDir(instance)
                val tasks = try { store.load().filter { it.instanceId == instance.id && it.enabled } }
                catch (e: Exception) { log(root, "任务清单读取失败：${e.message}"); delay(30_000); continue }
                next.keys.retainAll(tasks.map { it.id }.toSet())
                val now = SystemClock.elapsedRealtime()
                for (task in tasks) {
                    if (definitions[task.id] != task) { TaskStore.validate(task); definitions[task.id] = task; next[task.id] = now + task.intervalMinutes * 60_000L }
                    val due = next.getOrPut(task.id) { now + task.intervalMinutes * 60_000L }
                    if (now < due) continue
                    next[task.id] = now + task.intervalMinutes * 60_000L
                    try {
                        require(ConsoleSession.state.value.instanceId == instance.id && ConsoleSession.state.value.serverStatus == "RUNNING") { "服务器已停止或尚未就绪" }
                        when (task.action) {
                            "COMMAND" -> check(ConsoleSession.sendCommand(task.command)) { "命令发送失败" }
                            "BACKUP_WORLD", "BACKUP_FULL" -> {
                                val destination = Backups.directory(filesDir, instance, root)
                                val archive = LiveBackup.create(instance, root, destination, task.action == "BACKUP_FULL")
                                log(root, "备份已创建：${archive.name}")
                            }
                            "RESTART" -> {
                                check(controller.stopAndWait(gracefulMs = 60_000, forceOnTimeout = false)) { "停止超时，取消重启" }
                                repo.load()
                                val current = repo.byId(instance.id) ?: error("服务器已从列表移除")
                                when (val result = controller.start(current)) {
                                    is ServerController.StartResult.Started -> { next.clear(); definitions.clear(); ConsoleSession.connect(); misses = 0 }
                                    is ServerController.StartResult.Error -> error(result.message)
                                    else -> error("EULA 未接受，取消定时重启")
                                }
                            }
                        }
                        log(root, "任务「${task.name}」执行完成${if (task.action == "COMMAND") "（命令已发送，执行结果见服务端日志）" else ""}")
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { log(root, "任务「${task.name}」失败：${e.message}") }
                }
                delay(1000)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { log(null, "管理服务失败：${e.message}") }
        finally { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }
    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
