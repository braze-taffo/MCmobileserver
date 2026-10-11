# 官方 1.0.5 JRE 启动失败诊断

用户反馈：1.0.4 没有问题，更新官方 1.0.5 后，Fabric 1.21.11 在控制台显示“JRE 准备失败:”，详情为空。手机暂时无法连接 ADB。

## 确认的结论

- 失败检查位于 `ServerForegroundService.runServer()` 调用 `JreManager.ensureExtracted()` 之后、JNI 启动 JVM 之前。尚未进入 Minecraft/Fabric 主类。
- 原始官方 1.0.5 APK SHA-256：`3983895e2f71f9539a9f430e0ed1c661b8dda9abd562fef9a4b59055ffc24c48`。
- 比较原始 1.0.4 与 1.0.5 APK，四个运行时条目的内容 SHA-256 完全相同：

| 条目 | SHA-256 |
| --- | --- |
| assets/jre/arm64-v8a/21.zip | b46febd0f1f3c219c3d0bf81199b968a8ab15554dc089ed94a8b10092f9cebd5 |
| assets/jre/arm64-v8a/25.zip | 0a728983d858caf9d31bc8e55890c7f31531174caab05cedcd9f07852d09fb9c |
| lib/arm64-v8a/libjvm21.so | df8665c98bc26493cc529896e2f493ed0a78500fe527667cd35f804b3abb749c |
| lib/arm64-v8a/libjvm25.so | 5e1edd62f3162e12191f31730bf48cd8f256493ad4d4caef4b4dc398e8510818 |

- 已有错误处理会丢弃 Throwable，只保留 message；`message ?: toString()` 无法处理空字符串，且没有保存原始堆栈。这是已确认的诊断缺陷，不能据此认定具体启动回归原因。
- 最近 JRE 改动包括跨进程文件锁、Java 8/17 支持和运行时路径标记读取。尚未确认哪项在手机上触发失败。

## 复现与验证边界

- Android 14 x86_64 模拟器，用原始 ARM64 1.0.5 APK 的正式 DEX 执行准备方法，并将探针的 ABI 选择设为 ARM64：Java 21/25 新解压均为 READY；保留目录后再次准备也为 READY。只验证解压与文件准备，未执行 ARM64 原生 JVM。
- 原始正式 DEX 加入既有 x86_64 运行时及原生库、以调试证书重签的验证包：真实服务完整启动路径执行 `RuntimeSmoke` 成功，输出 `MCS_SMOKE OK`，退出 code=0。
- 以上均不代替真实 ARM64 手机的 1.0.4 → 1.0.5 覆盖安装验收，不能宣称已排除设备条件相关的回归。

## 本次诊断改动

- 记录准备阶段：文件锁/目录检查、读取压缩包、解压、切换目录、补齐原生库，以及 Java 8/17 下载安装。
- 异常摘要始终包含类型；null、空字符串及纯空白消息均使用明确兜底。
- JRE 状态保存原始异常，服务抛出错误时保留原因链；控制台、`logs/run-latest.log` 和 Logcat 保留完整堆栈。
- 协程取消继续向上传播，避免作为准备失败吞掉。
- 未改动运行时版本或启动算法，也未清理手机数据。这是诊断包，具体回归仍待手机复现信息。

## 诊断包验证

- `testDebugUnitTest / assembleDebug / assembleRelease` 构建成功。
- 157 项 JVM 测试：156 通过、0 失败/错误、1 项未配置 runtimeSamples 跳过。
- 诊断包正式 DEX 的 x86_64 服务探针正常执行 Java 主类；故意将探针运行时路径指向普通文件的子路径后，确认控制台包含“补齐运行时原生库”、`java.io.FileNotFoundException`、`ENOTDIR` 和完整 `Caused by` 原因链。该故障为人工测试，不能作为用户问题的根因。
- 发行包身份仍为 `com.mcmobile.server`、1.0.5 / 6；使用原正式证书，v2 签名验证通过。
- 证书 SHA-256：`c3e81e050bc6930bc2d4091b06ff6147ac3eff69bd9fdc10ca2b94c79d8e2df5`。
- 交付：`release-out/jre-diagnostic-20261011/mcserver-1.0.5-jre-diagnostic-arm64.apk`。
- APK SHA-256：`95db12eeabb7902ec184d3dcc5de99ddfc48bbdc08de2bbfd27fd3a91020486f`。对应 R8 mapping 同目录保存。

手机后续操作：直接覆盖安装诊断包，启动相同实例，点击控制台复制日志并反馈。保留现有运行时目录以避免抹掉触发条件。
