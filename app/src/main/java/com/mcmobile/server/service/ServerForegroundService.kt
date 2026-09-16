package com.mcmobile.server.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.system.Os
import com.mcmobile.server.R
import com.mcmobile.server.core.console.ConsoleBridgeServer
import com.mcmobile.server.core.jre.JreManager
import com.mcmobile.server.core.jre.JreStatus
import com.mcmobile.server.core.launch.JvmLauncher
import com.mcmobile.server.core.launch.JvmLauncherCallback
import com.mcmobile.server.core.launch.LaunchSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStream

/**
 * 独立 :server 进程：接管 stdin/stdout/stderr → JNI 启动 HotSpot JVM → 运行服务器主类。
 *
 * 终止有两条路径，都视为正常：
 *  1. 主类返回/抛异常 → JvmLauncherCallback.onJvmExited → 本进程收尾退出
 *  2. 服务器自行 System.exit()（MC 输入 stop 后即如此）→ HotSpot 直接结束本进程，
 *     UI 侧以 Socket 断开 + 日志末尾作为终止信号
 */
class ServerForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.mcmobile.server.action.START"
        const val ACTION_STOP_GRACEFUL = "com.mcmobile.server.action.STOP_GRACEFUL"
        const val ACTION_STOP_FORCE = "com.mcmobile.server.action.STOP_FORCE"
        const val EXTRA_SPEC_JSON = "spec"
        const val SOCKET_NAME = "mcs.console"
        private const val CHANNEL_ID = "mcs_running"
        private const val NOTIF_ID = 1001

        fun start(context: Context, spec: LaunchSpec) {
            val intent = Intent(context, ServerForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SPEC_JSON, Json.encodeToString(spec))
            }
            context.startForegroundService(intent)
        }

        fun requestStop(context: Context, force: Boolean) {
            context.startService(
                Intent(context, ServerForegroundService::class.java).apply {
                    action = if (force) ACTION_STOP_FORCE else ACTION_STOP_GRACEFUL
                },
            )
        }
    }

    @Serializable
    data class StateMsg(
        val status: String,
        val name: String = "",
        val exitCode: Int? = null,
        val error: String? = null,
    )

    enum class RunStatus { IDLE, STARTING, RUNNING, STOPPING, STOPPED, CRASHED, FAILED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { encodeDefaults = true }

    private var bridge: ConsoleBridgeServer? = null
    private var stdinWriter: OutputStream? = null
    private var logPump: Job? = null
    private var spec: LaunchSpec? = null

    @Volatile
    private var status: RunStatus = RunStatus.IDLE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP_GRACEFUL -> {
                if (status == RunStatus.RUNNING) {
                    setStatus(RunStatus.STOPPING)
                    writeStdin("stop")
                }
            }
            ACTION_STOP_FORCE -> scope.launch {
                if (status == RunStatus.RUNNING || status == RunStatus.STARTING) {
                    bridge?.appendLine("[MC服务器] 强制结束进程")
                    shutdownNow(137)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (status == RunStatus.RUNNING || status == RunStatus.STARTING) return
        val specJson = intent.getStringExtra(EXTRA_SPEC_JSON) ?: return
        val s = try {
            json.decodeFromString<LaunchSpec>(specJson)
        } catch (t: Throwable) {
            return
        }
        spec = s
        startForegroundCompat(buildNotification(s.name))
        scope.launch { runServer(s) }
    }

    private suspend fun runServer(s: LaunchSpec) = withContext(Dispatchers.IO) {
        val logsDir = File(s.workDir, "logs").apply { mkdirs() }
        val logFile = File(logsDir, "run-latest.log").apply { delete() }

        val b = ConsoleBridgeServer(
            socketName = SOCKET_NAME,
            logFile = logFile,
            onCommand = { writeStdin(it) },
            onControl = { control ->
                when (control) {
                    "STOP" -> {
                        setStatus(RunStatus.STOPPING)
                        writeStdin("stop")
                    }
                    "KILL" -> {
                        bridge?.appendLine("[MC服务器] 强制结束进程")
                        scope.launch { shutdownNow(137) }
                    }
                }
            },
        )
        bridge = b
        b.start()

        try {
            setStatus(RunStatus.STARTING)
            b.appendLine("[MC服务器] 正在准备内嵌 Java 运行时…")

            JreManager.ensureExtracted(this@ServerForegroundService, s.javaMajor)
            val jre = JreManager.state.value
            if (jre.status != JreStatus.READY) {
                throw IllegalStateException("JRE 准备失败: ${jre.error}")
            }
            b.appendLine("[MC服务器] Java ${jre.javaVersion} 就绪（${jre.abi}），启动 ${s.mainClass}")

            check(JvmLauncher.nativeChdir(s.workDir) == 0) { "无法进入实例目录: ${s.workDir}" }

            // 接管本进程标准流：fd0=命令入口，fd1/fd2=日志出口（JVM 初始化前完成）
            val (stdinR, stdinW) = Os.pipe()
            val (outR, outW) = Os.pipe()
            Os.dup2(outW, 1)
            Os.dup2(outW, 2)
            Os.dup2(stdinR, 0)
            Os.close(stdinR)
            Os.close(outW)
            stdinWriter = FileOutputStream(stdinW).buffered()

            logPump = scope.launch(Dispatchers.IO) {
                try {
                    BufferedReader(InputStreamReader(FileInputStream(outR), Charsets.UTF_8)).useLines { seq ->
                        for (line in seq) bridge?.appendLine(line)
                    }
                } catch (_: Exception) {
                }
            }

            JvmLauncherCallback.handler = { code, hadException ->
                scope.launch { onJvmExited(code, hadException) }
            }

            val libjvm = s.libjvmPath
                ?: File(applicationInfo.nativeLibraryDir, "libjvm${s.javaMajor}.so").absolutePath
            val opts = buildList {
                add("-Djava.home=${s.javaHome}")
                add("-Djava.class.path=${s.classpath}")
                add("-Djava.io.tmpdir=${cacheDir.absolutePath}")
                add("-Duser.home=${s.workDir}")
                add("-Duser.dir=${s.workDir}")
                addAll(s.jvmOpts)
            }

            setStatus(RunStatus.RUNNING)
            val rc = JvmLauncher.nativeStart(
                libjvmPath = libjvm,
                javaHome = s.javaHome,
                mainClass = s.mainClass,
                jvmOpts = opts.toTypedArray(),
                args = s.args.toTypedArray(),
            )
            if (rc != 0) throw IllegalStateException("JVM 启动失败（rc=$rc）")
        } catch (t: Throwable) {
            b.appendLine("[MC服务器] 启动错误: ${t.message}")
            setStatus(RunStatus.FAILED, error = t.message)
            delay(1500)
            shutdownNow(1)
        }
    }

    /** 回调路径：主类正常返回或抛异常（服务器未自行 System.exit 时） */
    private suspend fun onJvmExited(code: Int, hadException: Boolean) {
        setStatus(if (hadException || code != 0) RunStatus.CRASHED else RunStatus.STOPPED, exit = code)
        bridge?.appendLine("[MC服务器] JVM 已退出（code=$code exception=$hadException）")
        delay(600)
        shutdownNow(code)
    }

    private fun writeStdin(line: String) {
        try {
            stdinWriter?.let {
                it.write((line + "\n").toByteArray(Charsets.UTF_8))
                it.flush()
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun shutdownNow(code: Int) {
        runCatching { stdinWriter?.close() }
        bridge?.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        kotlinx.coroutines.delay(200)
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun setStatus(newStatus: RunStatus, exit: Int? = null, error: String? = null) {
        status = newStatus
        bridge?.broadcastState(
            json.encodeToString(
                StateMsg(
                    status = newStatus.name,
                    name = spec?.name ?: "",
                    exitCode = exit,
                    error = error,
                ),
            ),
        )
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(name: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ServerForegroundService::class.java).apply { action = ACTION_STOP_GRACEFUL },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title_running))
            .setContentText(name)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止", stopIntent).build())
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_running),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }
}
