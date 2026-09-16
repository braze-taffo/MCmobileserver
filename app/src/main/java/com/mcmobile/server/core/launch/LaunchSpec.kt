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
)
