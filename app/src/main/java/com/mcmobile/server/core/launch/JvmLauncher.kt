package com.mcmobile.server.core.launch

/**
 * libjvmlauncher 的 Kotlin 绑定。
 * 在调用 nativeStart 之前，调用方必须已完成：
 *  1. JRE 解压（javaHome 指向解压目录）
 *  2. pipe()+dup2() 接管 stdin/stdout/stderr
 *  3. Os.chdir() 进入服务器实例目录
 */
object JvmLauncher {
    init {
        System.loadLibrary("jvmlauncher")
    }

    /**
     * 启动 JVM 并运行 [mainClass] 的 main(String[])。
     * 立即返回；结束事件通过 [JvmLauncherCallback.onJvmExited] 回调。
     *
     * @param libjvmPath nativeLibraryDir 下的 libjvm.so 绝对路径
     * @param javaHome 解压后的 JRE 根目录（含 lib/modules）
     * @param mainClass 完整类名，如 "net.minecraft.bundler.Main"
     * @param jvmOpts JVM 选项，需包含 -Djava.home、-Djava.class.path、-Xmx 等
     * @param args 传给 main 的程序参数
     * @return 0 表示线程创建成功；负数表示启动失败
     */
    external fun nativeStart(
        libjvmPath: String,
        javaHome: String,
        mainClass: String,
        jvmOpts: Array<String>,
        args: Array<String>,
    ): Int

    /** 切换进程工作目录（android.system.Os 未暴露 chdir）。0 成功。 */
    external fun nativeChdir(path: String): Int
}
