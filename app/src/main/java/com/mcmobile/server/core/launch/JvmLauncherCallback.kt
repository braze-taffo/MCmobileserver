package com.mcmobile.server.core.launch

/**
 * JVM 退出回调。native 层在主类的 main 返回（或抛异常）后，
 * 附着回 ART 虚拟机调用这里。实现方应负责停服务、刷新日志并结束进程。
 */
object JvmLauncherCallback {
    @JvmStatic
    var handler: ((exitCode: Int, hadException: Boolean) -> Unit)? = null

    @JvmStatic
    fun onJvmExited(exitCode: Int, hadException: Boolean) {
        handler?.invoke(exitCode, hadException)
    }
}
