# Forge shim JAR 启动参数兼容修复

## 故障与根因

用户第二张截图显示 Java 21.0.1（arm64-v8a）已就绪，启动目标却为 `forge-1.21.11-61.2.0-shim.jar`，随后 JVM 退出 code=-102，初始化日志为 `Unrecognized option: -jar`。控制台的 Connection refused 是进程退出后无法连接其 socket 的结果。

检查[Forge 1.21.11 官方启动模板](https://github.com/MinecraftForge/MinecraftForge/blob/1.21.11/server_files/args.txt)，并下载[官方 61.2.0 安装器](https://maven.minecraftforge.net/net/minecraftforge/forge/1.21.11-61.2.0/forge-1.21.11-61.2.0-installer.jar)只读检查，确认 `data/unix_args.txt` 内容为：

```text
-Djava.net.preferIPv6Addresses=system -jar forge-1.21.11-61.2.0-shim.jar
```

原解析器只有 classpath、module-path 和直接主类的分支，未识别 `-jar`。因此它将 `-jar` 纳入 JNI_CreateJavaVM 的 VM options，将 JAR 文件名当成主类；初始化 VM 时立即失败。原始官方 1.0.5 DEX 的 Android 探针复现结果：

```text
classpathEntries=[]
mainClass=forge-1.21.11-61.2.0-shim.jar
unixJvmArgs=[-Djava.net.preferIPv6Addresses=system, -jar]
```

这张图的错误已定位。1.0.4 源码也缺少 `-jar` 分支，因此这里能确认的是 Forge shim 启动格式的兼容缺口，不能把它直接归因于某一项 1.0.5 新改动，也不能用它解释上一张 Fabric 的 JRE 准备失败。

## 修复

`LaunchArgfiles.parse()` 识别 `-jar`，读取目标 JAR 清单的 Main-Class 与 Class-Path，转换为嵌入式 JVM 所需的主类/classpath。JAR 路径相对实例 cwd；清单依赖相对 JAR 所在目录。保留其他 JVM 选项及 JAR 之后的程序参数；按 Java 启动语义覆盖之前的 `-cp`。路径沿用 ManagedFiles 的实例目录与符号链接约束。

原有 Forge/NeoForge 直接主类与模块路径格式继续通过现有测试。本轮未改用户实例数据或 Forge 文件；上轮加入的错误诊断仍保留。

## 验证

- `testDebugUnitTest / assembleDebug / assembleRelease` 成功。
- 162 项测试：161 通过、0 失败/错误、1 项未配置 runtimeSamples 跳过。
- 新增测试覆盖真实 Forge 参数格式、清单依赖、程序参数、带空格路径、先前 classpath 覆盖、缺少 JAR 操作数/清单主类、越界依赖，以及默认与手动选择 argfile 的完整 LaunchSpec。
- Android 14 x86_64 ART 上分别执行原始 1.0.5 与修复版的正式 R8 DEX，输入为真实官方参数文件和真实 shim JAR。前者复现错误解析；后者返回 `net.minecraftforge.bootstrap.shim.Main`、shim JAR 及清单的 11 项库依赖，VM options 仅保留 `-Djava.net.preferIPv6Addresses=system`。
- 官方安装器 SHA-256：`86744ab72e70da23ed4aa213068ebf15841ff2575522b406dffa66a93cf7dcce`。
- 官方 shim JAR SHA-256：`4ecdfbdc40b8f5d63b6618fa32c3f19d8332e4a541f84e6f5e978fb97313b94c`。
- 此验证证明启动描述修复，不代表真实 ARM64 手机上完整 Forge 世界启动或玩家联机已验收。

## 交付

`release-out/forge-shim-fix-20261011/mcserver-1.0.5-forge-shim-fix-arm64.apk`

包名 `com.mcmobile.server`，版本 1.0.5 / 6；使用原正式签名，v2 验证通过。

证书 SHA-256：`c3e81e050bc6930bc2d4091b06ff6147ac3eff69bd9fdc10ca2b94c79d8e2df5`。

APK SHA-256：`cb71921ed40552ed67316404575f9421542b6dcee2ecfdd7b3f29438c3fc8eb3`。对应 R8 mapping 同目录保存。

直接覆盖安装后重试原 Forge 实例，保留现有目录和配置。若出现后续错误，复制完整控制台日志继续定位。

## 后续实机反馈（2026-10-12）

同一修复 APK 已通过 ADB 覆盖安装到用户 ARM64 手机，保留原有两个实例。自动启动测试因用户要求稍后测试而暂停，没有把安装成功记作启动验收。用户随后自行复测，反馈已不再出现报错，并确认先前 JRE 准备失败与 Forge `-jar` 两类问题均未再次出现。这是用户实机反馈；JRE 原始异常仍未捕获，不能据此补写其未知根因。
