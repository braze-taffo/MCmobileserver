# 性能释放代码审计与包名对照实验

日期：2026-10-11。审计对象是当前 `main` 工作目录（已有未提交的整包导入和管理改动），不是群友安装包的反编译结果。此次没有修改 JVM、日志、线程、存储或后台管理逻辑，避免影响包名对照。

## 代码结论

没有发现应用主动限制 CPU 核数、绑定小核、设置低线程优先级、设置 CPU 配额、关闭 JIT 或限制 JIT 编译层级的代码。这个结论不能证明 Android/厂商系统没有对进程限频或限制可用核心。

- `LaunchSpecBuilder.kt:90-96` 默认仅组合核心参数、自定义参数和 Xms/Xmx，不强制 GC 线程数或 ActiveProcessorCount。实例与导入包的自定义参数仍可能携带这些限制，应检查具体实例。`ServerInstance.kt:40-41` 的默认堆为 256/1024 MB，内存不足可能造成 GC 压力，但它不是 CPU 性能上限。
- `jni_launcher.c:347` 在独立 pthread 内创建 HotSpot，`jni_launcher.c:527-530` 使用默认线程属性。没有主动绑定核或调整优先级，也没有在 tick 路径加 sleep；`usleep` 仅在 JVM 初始化失败后等待日志排空。实际线程继承的 nice、亲和性和调度组仍需设备证据。
- ABI 直接选择设备对应的 ARM64/x86_64，ARM64 发行包不是通过 x86 模拟执行。对本地 Java 21/25 的 libjvm 字符串检查均发现 `OpenJDK 64-Bit Server VM`、`UseCompiler`、`TieredCompilation` 和编译器相关内容；这只是二进制静态证据，不能替代运行时 JIT/GC 参数验证。

## 已确认的实现风险

### 1. 每行日志同步刷盘

`ConsoleBridgeServer.kt:87` 每行执行 `FileDescriptor.sync()`，不是仅 flush 到系统缓存。磁盘延迟或大量日志会拖慢 `ServerForegroundService.kt:258` 的日志泵。

JVM 的 stdout/stderr 经 `Os.pipe()` 和 `dup2()` 接到这条日志泵（`ServerForegroundService.kt:246-250`）。如果读取速度跟不上，有限容量的管道会填满，继续输出的 JVM 线程就可能等待。服务端日志框架如果使用异步输出，影响程度还取决于其队列和背压策略。可能出现卡顿但 CPU 不高；尚未证明这是群友普遍现象的实际根因。

这段逐行 sync 在当前 HEAD 中也存在，不仅存在于未提交的管理改动。

### 2. 慢控制台客户端能阻塞日志泵和状态广播

`ConsoleBridgeServer.kt:84-88` 在同一把锁内刷盘、向全部 LocalSocket 同步发送日志，没有独立发送队列或慢客户端淘汰。历史回放（第 45-55 行）和状态广播（第 94-96 行）也使用这把锁。

`ConsoleSession.kt:55,126` 使用 8192 条缓存的 SharedFlow，`emit` 默认会在慢订阅者使缓存耗尽后等待。UI 消费暂停或处理不及时可能阻塞 socket 读取，再传导到服务端同步发送、日志泵和 JVM 输出。`AppViewModel.kt:72-73` 每行复制最多 3000 行的列表，日志密集时也会增加主线程工作。这些是有触发条件的背压风险，不能把所有低 CPU 现象直接归因于此。

### 3. 唤醒锁依赖管理服务

`ServerForegroundService` 已经是独立 `:server` 进程的前台服务，但本身不持有 PARTIAL_WAKE_LOCK。当前 UI 正常启动路径会启动 `ManagementService`（`AppViewModel.kt:167`），其第 33 行持有设备级 partial wake lock，因此不能说正常启动就完全没有唤醒锁。

管理服务发生错误或连续无法识别服务器后会停止（`ManagementService.kt:56,100-104`），并释放锁；服务器不一定同时停止。旧安装包是否包含这个未提交的新服务也没有核实。建议后续把服务器运行所需的唤醒锁生命周期放到服务器服务自身。锁保持 CPU 不进入挂起，不保证满频、不保证大核，也不等同于免除 Doze 或厂商省电限制。

## 包名和版本实验

手机原安兔兔身份经 ADB 读取为 `com.antutu.ABenchMark`，版本名 `11.1.2`、versionCode `110010201`。测试 APK 使用这些相同的身份字段，应用显示名称为“MC服务器（包名对照测试）”，仍由 MC 服务器项目的发行证书签名。没有仿造安兔兔签名、界面或代码。

仅显式指定构建属性时生效，正常构建仍为 `com.mcmobile.server`、`1.0.4 / 5`：

```powershell
# 同版本原包名对照
.\gradlew.bat :app:assembleRelease -PantutuVersionProbe=true --console=plain
# 安兔兔包名和版本测试
.\gradlew.bat :app:assembleRelease -PantutuPackageProbe=true --console=plain
```

两个 APK 保存于 `release-out/performance-probe-20261011/`。只安装测试 APK，保留手机上的原版 MC 服务器。用户已明确授权卸载原安兔兔并安装测试包，随后要求不保留安兔兔备份。

## 用户测试要点与判断边界

- 两个应用有独立数据目录，新测试包需要自行导入或配置同一份服务端。使用相同 Java、堆参数、模组、世界和服务端配置。先停止另一个版本，再启动本次版本；两者共用固定控制台 socket 名称和服务器端口，不支持同时运行比较。
- 当前手机上的旧 MC 服务器为 `1.0.4 / 5`，不是此次同源构建的控制 APK。严格对照应使用目录里的同源控制 APK，避免把之前未提交的新功能或版本字段变化误判为包名效应。本次未擅自更新手机上的原版 MC 服务器。
- 每次保持相近初始温度、电量、充电状态、系统性能模式、电池限制、亮屏/后台条件和实际负载；预热 JIT、避免把首次解压/首次生成世界与后续已缓存运行直接相比。
- 记录 TPS/MSPT、进程 CPU 和线程状态，温度仅作辅助。当前管理面板的 CPU 为进程累计 CPU 时间/墙钟时间，单核为 100%，多核可超过 100%，不是整机 CPU 百分比。
- 如果同源对照重复出现明显改善，支持系统对应用身份区别处理的假设；不能仅凭包名实验证明具体的白名单实现。签名、进程名、系统应用识别和省电设置等也可能参与判断。没有改善也不能排除使用签名或其他身份字段识别的策略。

## 参考依据

- [Android：前台服务与设备保持唤醒](https://developer.android.com/develop/background-work/background-tasks/awake)
- [AOSP：cgroup 和可被厂商覆盖的 task profiles](https://source.android.com/docs/core/perf/cgroups)
- [spark：TPS 与 MSPT](https://spark.lucko.me/docs/guides/TPS-and-MSPT)（20 TPS 对应平均 tick 不超过 50 ms；低于预算时没有必要强行增加 CPU 占用）

此次证据为代码审计、APK 构建及安装身份检查；真实性能和手机系统调度的验收由用户执行。

## 完成记录

- 同版本原包名对照和测试包均通过 `assembleRelease` 及其发行关键 lint 检查，APK v2 签名验证成功。两者签名证书 SHA-256 均为 `c3e81e050bc6930bc2d4091b06ff6147ac3eff69bd9fdc10ca2b94c79d8e2df5`。
- APK 共比较 220 个文件条目，218 个完全相同。所有 DEX、原生库、assets 的 SHA-256 一致。差异仅为 `AndroidManifest.xml`、`resources.arsc`。对资源表内容去除包名和 string-pool 索引差异后，仅剩测试包未使用的旧 `app_name` 被资源压缩移除，其他资源值一致。
- 对照 APK：`release-out/performance-probe-20261011/mcserver-control-11.1.2-arm64.apk`，SHA-256 `f6b918e377a1b3f94f815b5412958c30077e443d339e29788e376f411c646e6c`。
- 测试 APK：`release-out/performance-probe-20261011/mcserver-antutu-11.1.2-arm64.apk`，SHA-256 `6253e60392204ea0867713c2e170253c377a5421591fdf8453d76aff21b38b46`。
- 用户手机（ADB 型号 `2602BRT18C`、Android 16）卸载原安兔兔返回 `Success`，安装测试包返回 `Success`。包管理器回读版本为 `11.1.2 / 110010201`，启动入口解析为 `com.antutu.ABenchMark/com.mcmobile.server.MainActivity`。手机原 MC 服务器保留，未安装同版本控制 APK。
- 测试包安装后处于 `stopped=true, notLaunched=true`，没有启动应用、服务器、世界或压力测试。性能验收留给用户。
- 用户要求不保留备份后，只删除了本任务刚创建的原安兔兔 APK 备份；没有保存原安兔兔数据或其他备份。
- 可用 `release-out/performance-probe-20261011/compare-apks.ps1` 重复对照 APK 文件哈希，结果为同目录 `apk-comparison.json`；资源值差异保存于 `resource-differences.json`。
