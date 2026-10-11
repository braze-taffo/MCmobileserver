# 控制台阻塞的对抗审查与修复

日期：2026-10-11。目标：验证先前提出的日志刷盘、慢控制台和 UI 消费背压是否成立、是否有真实影响，修复有证据的路径。没有启动 Minecraft、接受 EULA、打开世界或执行手机性能跑分。

## 审查结论

两条项目阻塞路径成立，并已在真实 Android 管道/LocalSocket 上复现。它们有明确触发条件：大量输出、慢/停止读取的客户端，或者日志消费者落后。不能由此推断所有服务器、所有时刻都受影响，也不能把它们当成之前安兔兔包名单核翻倍的原因；原生 CPU 跑分不经过这条控制台路径。

反例也验证了：没有 SharedFlow 订阅者时，即使连续发送 20,000 条记录，旧 emit 不会因缓冲区而阻塞。有真正慢的订阅者、且缓冲用完后，旧路径才会等待。这符合 [SharedFlow 官方缓冲语义](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-shared-flow/)。

## 旧实现与固定负载复测

修改前保存了实际 debug `ConsoleBridgeServer` 的字节码到 `build/console-validation/old/bridge.jar`，SHA-256 为 `093036b430b5174cd9a70a82aeb248d419da23fa21e0d1d7059f08f66e331368`。新旧各自运行同一份 `ConsolePipeProbe.java`，核心不是模拟 socket 的 JVM 单元测试，而是 Android ART、`Os.pipe()`、真正的 LocalSocket 和项目控制台桥。

用户手机此时已断开 ADB。验证使用项目原有 AVD `mcmobileserver_validation_20261002` 的独立只读实例 `emulator-5580`，隐藏启动、禁用快照写入，不修改已有 AVD 数据。

| 验证项 | 旧实现 | 修复后 |
|---|---|---|
| 客户端停止读取；生产者经管道写 20,000 行，每行 1 KiB | 生产者停在 160 行、日志泵停在 93 行，连续观察无进展 | 20,000 行全部通过 |
| 同时广播状态 | 被同一把锁阻塞 | 可以完成 |
| 慢客户端仍连接时新客户端读取状态 | 旧全局锁耦合阻止正常回放 | 第一帧为当前状态 |
| 同时发送 `save-all flush` 合成命令与确认 | 非测试重点 | 实时确认送达，新客户端不受慢客户端影响 |
| 关闭后客户端读写线程 | 原实现没有独立发送线程 | 读写线程全部退出 |
| 写 1,000 行合成日志，append 阶段耗时 | 2769.99 ms | 8.96 ms |
| 写入后的实际文件行数 | 1,000 | 1,000 |

刷盘数据是这个 Android 模拟器上的日志微测试，append 计时不含关闭阶段；修复后正常关闭时仍同步一次。约 2.77 秒对约 9 毫秒不能外推成服务器 TPS 提升比例、ARM 手机的精确速度或日常静默负载收益。

原始输出保存于：

- `build/console-validation/old-results.txt`
- `build/console-validation/fixed-results.txt`
- `build/console-validation/session-results.txt`
- `build/console-validation/fixed-abrupt-results.txt`

## 已完成的修复

1. 移除 `appendLine` 的逐行 `FileDescriptor.sync()`。仍逐行直接向 FileOutputStream 写入，未增加应用层磁盘缓冲或异步落盘队列；正常关闭时同步一次。已在 Android 上验证不执行 `bridge.close()` 而直接 `Runtime.halt(0)` 后，已写的 1,000 行仍全部保留。进程退出与意外断电是不同条件；最后尚在系统缓存中的日志不承诺抵抗断电。本次没有更改世界存档的 flush 策略。
2. 每个客户端使用独立发送线程和有界队列。日志泵、状态更新及历史快照不再在全局锁内执行 socket 写操作。文件 IO 使用独立锁，不能占住客户端状态锁。
3. 发送缓冲按字节及条数限制，历史回放也限条数和字节；慢客户端会省略旧显示日志并收到提示，保留最新状态并优先发送存档确认。正常磁盘写入时，省略的显示记录仍保存在 `logs/run-latest.log`。客户端数量也有限制，避免无线程/队列上限。极端确认消息洪流仍可能超过有界队列；未收到确认时备份失败，而不会凭缺失日志判定存档成功。
4. UI 的 SharedFlow 使用 DROP_OLDEST，socket 读取不再等待显示消费者；实时存档确认在进入显示流之前直接完成专用等待器。没有把备份继续留在可丢弃的显示流里。
5. 状态先于历史回放发送；历史采用 `H` 帧，仅供显示，不能完成新存档请求。等待器注册后才发送命令，超时、取消或断开连接会清理。确认按连接对象绑定，旧连接的消息/断开事件不能误确认或取消新连接的请求。
6. 关闭客户端先关闭队列，再 shutdown 输入/输出，唤醒阻塞的 native 读写；真实 Android 测试确认无遗留客户端线程。这依据 [AOSP LocalSocket 实现](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/LocalSocketImpl.java) 的独立读写锁与 shutdown 路径，并非假定 Thread.interrupt 能停止 native socket IO。

保留了日志文件的原有 `L\t...` 格式，命令 `C\t...` 与 STOP/KILL 指令保持原语义。旧的历史显示改为私有协议 `H` 帧，两端同步更新。

## 回归与验证边界

- 7 项针对性测试：旧 SharedFlow 的成立条件/反例、队列溢出及状态/确认优先、字节上限和超长行、阻塞传输与关闭唤醒、慢 UI 不影响确认、历史不能假确认、取消/断开清理、旧连接隔离。
- Android 完整 Session 探针调用当前项目的 `ConsoleSession`，暂停显示订阅者并灌入 20,000 条日志，确认实时存档回复可送达，历史旧回复不能冒充新回复；状态仍为 RUNNING。
- 全量 JVM 测试 152 项：151 通过，0 失败、0 错误，1 项 `RuntimeDownloadTest` 真实运行时样本审计因未指定样本路径而跳过。
- `assembleDebug`（ARM64/x86_64）、`assembleRelease`、`lintDebug` 成功；lint 为 0 errors、34 warnings、2 hints。发行关键 lint 同样通过。
- ARM64 release APK v2 签名通过，证书与项目既有发行证书一致。未推送、发布或更新手机应用；手机不在 ADB 设备列表中。生成的是普通原包名 `1.0.4 / 5` 构建，不是此前 `11.1.2 / 110010201` 的临时比较包；后者在手机上有较高版本号，不能直接当成一次普通覆盖降级安装。
- 已保存可审核安装包 `release-out/console-fix-20261011/mcserver-console-fix-1.0.4-arm64.apk`，SHA-256 `89aeaab55d8545114d34868f405f7ef9eeb4fb3850fc4c271cd640c65790577f`。测试完成后关闭了本任务启动的只读模拟器。
- 没有 Minecraft TPS/MSPT 对照、真实模组日志量统计或手机长时后台接受证据。当前结论是阻塞机制的真实复现与修复，不能声称已消除所有服务器性能瓶颈。

## 复现命令

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug --console=plain
# old 需要此次保存的修改前 bridge.jar；fixed 从当前 debug 字节码打包
.\tools\console-validation\Run-Probe.ps1 -Mode old -Device emulator-5580
.\tools\console-validation\Run-Probe.ps1 -Mode fixed -Device emulator-5580
.\tools\console-validation\Run-Probe.ps1 -Mode fixed -Device emulator-5580 -Abrupt
.\tools\console-validation\Run-Session-Probe.ps1 -Device emulator-5580
```

探针入口在测试源码目录，不会打包到 debug/release APK；只操作自己创建的合成日志和抽象 socket。
