package com.mcmobile.server.core.launch

import kotlinx.serialization.Serializable

/**
 * 一次 JVM 启动的完整描述，由 UI 进程构造、序列化后经 Intent 传给 :server 进程。
 */
@Serializable
data class LaunchSpec(
    val instanceId: String,
    val name: String,
    /** 服务器实例目录（JVM 的 cwd） */
    val workDir: String,
    /** 解压后的 JRE 根目录（含 lib/modules） */
    val javaHome: String,
    /** classpath，冒号分隔 */
    val classpath: String,
    /** 要运行的普通 Java 主类 */
    val mainClass: String,
    /** 额外 JVM 选项（-Xmx 等；-Djava.home/-Djava.class.path 由服务自动加） */
    val jvmOpts: List<String> = emptyList(),
    /** main(String[]) 的参数 */
    val args: List<String> = emptyList(),
    /** Java 大版本（21/25），决定加载哪个 libjvm<major>.so */
    val javaMajor: Int = 21,
    /** libjvm.so 绝对路径，留空则用 nativeLibraryDir/libjvm<major>.so */
    val libjvmPath: String? = null,
    /**
     * 主类 main 返回后是否等非守护线程结束（DestroyJavaVM 语义）才算运行结束。
     *
     * 服务器要为 true：vanilla 的 bundler 把 `net.minecraft.server.Main` 放在独立线程里跑，
     * 自己的 main 会立刻返回，不等就会把刚起来的服务器杀掉。
     * 安装器这类"跑完即止"的工具类必须是 false：`SimpleInstaller` 跑完后会残留一个
     * 停住不动的非守护线程（Thread-0），DestroyJavaVM 会永远等它，进程不退、App 收不到
     * 结束事件，界面就一直卡在"安装中"。
     */
    val waitForNonDaemonThreads: Boolean = true,
)
