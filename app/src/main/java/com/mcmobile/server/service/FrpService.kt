package com.mcmobile.server.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.mcmobile.server.MainActivity
import com.mcmobile.server.R
import com.mcmobile.server.core.frp.FrpStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class FrpState(
    val running: Boolean = false,
    val connected: Boolean = false,
    val message: String = "未启动",
    val address: String = "",
    val logs: List<String> = emptyList(),
)

/** Runs independently of the JVM process; stopping Minecraft does not stop the tunnel. */
class FrpService : Service() {
    companion object {
        const val STOP = "com.mcmobile.server.FRP_STOP"
        private const val CHANNEL = "frp_tunnel"
        private const val NOTIFICATION = 1002
        private val mutableState = MutableStateFlow(FrpState())
        val state = mutableState.asStateFlow()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()
    private var child: Process? = null
    private var job: Job? = null
    private var destroyed = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val configFile get() = File(filesDir, "frpc-runtime.json")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            mutableState.update { it.copy(running = false, connected = false, message = "已停止") }
            stopSelf()
            return START_NOT_STICKY
        }
        if (job != null) return START_NOT_STICKY
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "FRP 内网穿透", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = notification("正在连接节点")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(NOTIFICATION, notification)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mcs:frp").apply { acquire() }
        mutableState.value = FrpState(running = true, message = "正在连接节点…")
        job = scope.launch {
            try {
                val config = withContext(Dispatchers.IO) { FrpStore.load(this@FrpService).also { it.validate() } }
                mutableState.update { it.copy(address = config.address) }
                withContext(Dispatchers.IO) {
                    val process = synchronized(lock) {
                        check(!destroyed) { "已停止" }
                        val executable = File(applicationInfo.nativeLibraryDir, "libfrpc.so")
                        check(executable.canExecute()) { "此设备不支持内置 FRP（需要 ARM64），或原生程序未正确打包" }
                        configFile.writeText(config.clientJson())
                        ProcessBuilder(executable.absolutePath, "-c", configFile.absolutePath)
                            .directory(filesDir).redirectErrorStream(true).start().also { child = it }
                    }
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { raw ->
                            val line = if (config.token.isEmpty()) raw else raw.replace(config.token, "***")
                            withContext(Dispatchers.Main) { record(line.take(2000)) }
                        }
                    }
                    val code = process.waitFor()
                    withContext(Dispatchers.Main) {
                        mutableState.update { it.copy(running = false, connected = false, message = "FRP 已退出（$code），请检查日志") }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(running = false, connected = false, message = e.message ?: "FRP 启动失败") }
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun record(line: String) {
        mutableState.update { old ->
            val next = when {
                "start proxy success" in line -> old.copy(connected = true, message = "隧道已建立")
                "start error" in line -> old.copy(connected = false, message = "隧道注册失败，请检查远程端口、名称和节点权限")
                "login to server failed" in line || "reconnect" in line ||
                    "connect to server error" in line || "try to connect to server" in line ||
                    "heartbeat timeout" in line || "session shutdown" in line || "connection is closed" in line ->
                    old.copy(connected = false, message = "连接中断或登录失败，正在重试…")
                else -> old
            }
            next.copy(logs = (old.logs + line).takeLast(300))
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(state.value.message))
    }

    private fun notification(message: String): Notification {
        val stop = PendingIntent.getService(this, 20, Intent(this, FrpService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 21, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setContentTitle("FRP 内网穿透")
            .setContentText(message).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止穿透", stop).build()).build()
    }

    override fun onDestroy() {
        synchronized(lock) {
            destroyed = true
            child?.destroy()
            child?.destroyForcibly()
            child = null
            configFile.delete()
        }
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        mutableState.update {
            it.copy(running = false, connected = false, message = if (it.running) "已停止" else it.message)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
