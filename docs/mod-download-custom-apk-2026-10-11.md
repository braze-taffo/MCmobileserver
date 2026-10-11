# 模组下载与手机端自签名

## 实现

- 移除 CPU benchmark C/JNI、测试入口、临时包名与版本号 Gradle 属性；默认身份恢复 `com.mcmobile.server`，正式版本为 1.0.5 / 6。保留已验证的控制台背压修复及回归工具。
- 模组管理分为“已安装”和“下载”，采用 LazyColumn。本地名称/ID/文件名搜索、启用状态筛选、计数；仅本地变更后扫描 JAR，不因在线搜索和翻页重复扫描。下载进度最多每 100ms 更新一次。
- 在线搜索保留现有 Modrinth 实现，补齐项目类型、相关度/下载量/更新时间排序、准确总数分页；翻页沿用上次已提交的关键词和排序，不混入尚未提交的输入。直接安装兼容正式最新版，或从兼容版本对话框选择版本；固定版本依赖保持固定，其余必需依赖优先正式版。继续使用 SHA 校验、全量预下载和旧 JAR 回滚目录。
- 自签名入口位于设置页。仅处理当前应用自己的完整 APK，不执行下载的 APK 或外部重打包工具。重建二进制清单字符串池，改应用包名及本应用权限/provider authority，保留组件类、DEX 和资源 ID。`resources.arsc` 保持 STORED、4 字节对齐。Android Keystore RSA 2048 签名证书复用，apksig 8.7.3 生成 v2/v3 签名并验证，PackageManager 再校验包名，完成后可系统安装或 SAF 导出。
- 各应用的控制台 socket 使用 UID 隔离，避免不同包名安装副本连入彼此服务端。自定义包名副本不会推荐无法覆盖安装的官方签名更新。

## 参考与署名

少量参考本地 PCL2 的交互设计和公开接口用法，独立编写 Kotlin/Compose 实现，未移植 VB 源码、镜像服务、CurseForge 密钥或中文词典。

- PCL2 / 龙腾猫跃：[上游项目](https://github.com/Hex-Dragon/PCL2)，本地 `Modules/Resource/ResourceSearcher.vb:GetModrinthAddress`（项目类型、加载器、版本、分页），`Pages/PageDownload/Resource/PageResource.xaml.vb`（筛选与总数分页），`Modules/Resource/ResourceVersion.vb`（正式/测试版与依赖信息）。本应用为独立服务端工具，与 PCL 无关联。
- [Modrinth Search API](https://docs.modrinth.com/api/operations/searchprojects/)
- [Android Keystore KeyGenParameterSpec](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec)
- [Android apksig ApkSigner](https://android.googlesource.com/platform/tools/apksig/+/refs/heads/main/src/main/java/com/android/apksig/ApkSigner.java)

## 边界

新包名为独立应用，原应用实例不会自动迁移。版本号与源 APK 一致。清除生成应用的数据或卸载会丢失其 Keystore 密钥，保留 APK 不等于备份私钥。请从保留证书的原应用更新后重新生成。拆分 APK 暂不支持，生成失败不会替换上一次完整导出。

此前安装的 11.1.2 / 110010201 实验版版本号高于正式版 1.0.5 / 6；系统会拒绝直接覆盖降级。用户后续明确要求保持内部版本号、删除旧安装且不备份，已通过 ADB 卸载 `com.mcmobile.server` 后安装 1.0.5 / 6，核对版本成功，启动后显示通知权限弹窗。未卸载另一个安兔兔包名对照应用。

## 验证结果

- JVM 共 155 项：154 通过，0 失败 / 错误，1 项因未配置 runtimeSamples 跳过。新增回归覆盖 UTF-8 / UTF-16 清单、变长包名、组件类名保留、二次重打包、搜索筛选与总数分页。
- `testDebugUnitTest / assembleDebug / assembleDebugAndroidTest / assembleRelease / lintDebug` 通过；lint 0 错误、39 警告、2 提示。正式 ARM64 包通过 v2 签名验证，证书与原发行证书一致。
- Android 14 x86_64 只读模拟器：真实 Keystore 生成两次且指纹一致，清单版本/组件/provider 正确；真实 Modrinth FerriteCore 1.21.1 Fabric 搜索、版本选择、依赖预览、校验下载和安装成功，设备测试 2/2 通过。
- 原有 ART 控制台回归再次通过：旧回放不能确认新存档回复，阻塞 UI 在日志洪峰下不能阻塞回复。加入 UID 隔离后测试仍成立。
- R8 验证包保留正式版 DEX 和资源，仅追加已构建的 x86_64 JRE / native 库、用调试证书重签供模拟器运行。通过实际设置界面生成 `com.mcmobile.custom`，完成“授权安装 → FileProvider 分享 → 系统安装器安装”，副本启动正常、版本为 1.0.5、实例数据独立。此验证包不作为交付包。
- 正式交付包只含 ARM64；ZIP 中无 cpubenchmark 库。初次功能验证时真实手机未连接；随后应用户 ADB 请求在手机上完成卸载安装，功能体验留给用户验收。

交付：`release-out/mods-custom-apk-20261011/mcserver-1.0.5-arm64.apk`，SHA-256：`3983895e2f71f9539a9f430e0ed1c661b8dda9abd562fef9a4b59055ffc24c48`。
