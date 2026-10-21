# MC Mobile Server

当前正式版本：[v1.0.7](https://github.com/braze-taffo/MCmobileserver/releases/tag/v1.0.7)。修复本地模组与插件列表的中文名称和描述显示，完善 Forge/NeoForge 多行描述及元数据回退。更新内容见 [发行说明](docs/releases/v1.0.7.md)。

## 开源协议

本项目自身代码采用 [GNU Affero General Public License v3.0](LICENSE)（SPDX：`AGPL-3.0-only`）。分发受本协议覆盖的程序时，应按协议提供相应源码；修改后的程序通过网络与用户交互时，也应按协议向这些用户提供相应源码获取方式。具体权利与义务以许可证全文为准。

OpenJDK、FRP 及其他第三方组件保留各自的版权、许可证与分发要求，不因本项目采用 AGPL-3.0 而改变。

在 Android 手机 / 平板上运行 **Minecraft Java 版服务器**的应用。内置 Android 原生 OpenJDK 21/25，Java 8/17 首次使用按需下载并校验，之后可以离线使用。支持 Vanilla / Paper / **Folia** / Fabric / Forge / **NeoForge**，提供完整服务端包导入、独立管理主页、文件管理、玩家权限、模组/插件、世界备份、日志和定时任务。

## 运行原理（无任何模拟层）

```
┌─ UI 进程（默认）────────────┐      ┌─ :server 进程 ──────────────────────┐
│ Compose 界面 / 下载器 / 实例管理 │      │ 前台服务（specialUse，崩溃互相隔离）   │
│         │ LocalSocket       │◄────►│ pipe()+dup2() 接管 stdin/stdout/stderr │
└─────────┴───────────────────┘      │   ↓                                  │
                                     │ dlopen(<javaHome>/lib/server/libjvm.so)│
                                     │   ↓ JNI_CreateJavaVM                  │
                                     │   ↓ FindClass → main(String[])  ← 普通主类
                                     │ HotSpot JVM，JIT 编译为 ARM64 机器码    │
                                     └──────────────────────────────────────┘
```

- 内嵌 JRE 来自 [AngelAuraMC/mobile OpenJDK 构建](https://github.com/AngelAuraMC/angelauramc-openjdk-build)（PojavLauncher 系，NDK 原生编译、bionic libc），**不是 x86 模拟器**，性能为原生 JVM 水平。
- `libjvm<-major>.so` 打包进 APK 的 `jniLibs`（原生库由 APK 提供），其余文件在首次启动时从 assets 解压到应用数据目录；解压时会把 libjvm **落一份到 `<javaHome>/lib/server/libjvm.so`** 并从那里加载——HotSpot 不认 `-Djava.home=`，它用 libjvm 的实际加载路径反推 `java.home`（`os::init_2()`），路径不对会直接 `Failed setting boot class path.` 退出。
- 启动器还需两件事才跑得起来：创建 VM 前 `mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, 0)` 关掉 bionic 堆指针标记（Android 12+ 默认开启，会和 HotSpot 自己的顶字节用法冲突，表现为第一次 GC 后 SIGABRT），以及 main 返回后调 `DestroyJavaVM` 等非守护线程结束（vanilla 的 bundler 把服务器主类放在独立线程里跑，main 会立刻返回）。
- 服务器作为**普通 Java 主类**通过 JNI 调用运行，不需要 `java` 启动器二进制，也从不 exec 数据目录中的文件。
- 默认按 MC 版本选择：1.16 及以前 Java 8，1.17–1.20.4 Java 17，1.20.5–1.21.x Java 21，26.x Java 25。核心 JAR 声明的 Java 要求优先，导入预览和启动设置都可以手动修改。Java 8 的 `lib/<arch>/server` 与现代模块化运行时分别适配。

## 支持的服务端

| 类型 | 来源 | 说明 |
|---|---|---|
| Vanilla | piston-meta（Mojang 官方） | 1.17+ release |
| Paper | fill.papermc.io v3 | build 自动选最新 |
| Folia | fill.papermc.io v3 | 区域化多线程的 Paper 分支 |
| Fabric | meta.fabricmc.net | 自动附带原版 server.jar |
| Forge | maven.minecraftforge.net | 1.18+，安装器在设备上执行 |
| NeoForge | maven.neoforged.net | 1.20.1+ 全部（含 MC 26.x） |

也支持从本地导入任意核心 jar / Forge/NeoForge 安装器 jar。导入时优先读 jar 内部
（`version.json` / `install_profile.json` / `install.properties`）拿到 MC 版本与 Java 要求，
读不出来才退回文件名解析，识别结果可在创建前手动修正。

## 完整服务端导入与管理

服务器列表的「导入整包」支持 ZIP 和已解压目录。ZIP 保留原始目录结构，默认存到共享的 `Documents/MCServers`，可以改为内部存储；已解压目录默认直接接管，也可以复制为独立副本。接管不会清空原目录。导入预览显示版本、核心类型、Java、实际根目录、启动 JAR 或参数文件，确认后才登记实例；不会创建空服再替换模组。

只导入已安装完整、可直接运行的包。识别 JAR manifest、Forge/NeoForge 参数文件及简单脚本里的字面 Java 命令；不执行 shell/bat 脚本，也不展开变量或运行安装器。复杂脚本需要在启动设置中手动配置主类、classpath 和参数。缺少依赖、安装器包或不明确的入口会显示原因并允许重新选择。

点击服务器进入独立管理主页：

- **运行状态**：启动、停止、重启，真实进程 PSS、CPU、运行时间及存储剩余空间。
- **文件管理**：内部与共享目录使用同一套浏览器，支持多选、搜索、排序、新建、重命名、复制/移动、删除、导入/导出、压缩/解压和 UTF-8 文本编辑。导入支持同名跳过、覆盖或保留两份；文本限 2 MB，并检查编辑期间的外部修改。
- **玩家与权限**：在线查询、OP、白名单、封禁和踢出。停服时修改列表文件；正版模式下未知 UUID 需要明确填写，不生成错误的离线 UUID。
- **模组与插件**：独立“已安装 / 下载”页，按名称、ID、文件名与启用状态筛选；Modrinth 在线搜索按相关度、下载量或更新时间排序，自动匹配 MC 版本、加载器与服务端支持。可直接安装兼容正式最新版或选择版本，预览必需依赖后校验下载；旧 JAR 保留在备份目录。长列表按需渲染。
- **自签名与自定义包名**：设置页可在手机本地修改当前完整 APK 的包名，以 Android Keystore 中的本地证书重新签名、验证，随后安装或导出。版本号保持不变；新包名使用独立数据，可与原应用共存。证书仅保存在生成应用的本机密钥库，清除数据或卸载会丢失；官方更新后须从原应用重新生成，不能用官方签名覆盖自签名版本。

模组页面少量参考 [PCL2 / 龙腾猫跃](https://github.com/Hex-Dragon/PCL2) 的筛选和版本展示设计，独立实现；本应用与 PCL 无关联。实现与验证说明见 [模组下载与自签名](docs/mod-download-custom-apk-2026-10-11.md)。
- **世界与备份**：世界导入/切换、世界或整服 ZIP 备份、导出及恢复范围预览。整服恢复替换整个文件目录，世界恢复只替换所选存档；实例登记和当前启动设置保留。恢复失败保留回滚文件。
- **启动、配置与日志**：Java、最小/最大内存、JAR/参数文件/主类入口、JVM 和程序参数；配置编辑、日志搜索、错误筛选和完整日志导出。
- **定时任务**：仅在服务器运行期间按分钟间隔备份、重启或执行命令；停服期间不执行或补跑。任务记录保存在 `logs/management.log`，重启停止超时会取消，不强杀存档进程。

运行中可以编辑配置，并提示重载/重启方式；核心、模组/插件、世界替换和目录迁移要求先停服。运行中备份会等待 `save-off` 与 `save-all` 确认，结束后恢复保存；未收到确认会报错而不会声称备份成功。管理主页可以迁移整个实例目录，先复制、持久化新位置，之后才移除原目录；备份保留在原备份位置。

本次实现及验收边界见 [验证记录](docs/server-management-validation.md)。

## 构建

要求：JDK 17+、Android SDK（platform 36、build-tools、NDK 27.x、CMake 3.22.1）。

```bash
./gradlew assembleDebug     # 双 ABI（arm64-v8a + x86_64），用于真机与模拟器
./gradlew assembleRelease   # 仅 arm64-v8a，R8 + 资源压缩；必须提供独立发行签名
./gradlew testDebugUnitTest # 单元测试
```

首次构建会自动执行 `prepareJreAssets`：下载两个 OpenJDK 构建产物（tar.xz，SHA-256 已锁定），拆分为 `jniLibs/libjvm<major>.so` 与 `assets/jre/<abi>/<major>.zip`。产物在 `build/jre/` 缓存。

发行构建读取本地 `release-signing.properties`（格式见 `release-signing.properties.example`），缺失时构建失败，不回退到调试签名。专用发行密钥位于 `keystore/distribution/`；密钥与凭据均忽略提交，请离线备份，后续更新继续使用同一把密钥。旧 `keystore.properties` 保留，但不再用于发行构建。新签名无法直接覆盖使用不同签名安装的旧版本。

发行版启用 R8 代码压缩、优化、混淆与资源裁剪；JNI 入口和原生退出回调的保留规则在 `app/proguard-rules.pro`。构建后请保存 `app/build/outputs/mapping/release/mapping.txt`，用于还原崩溃堆栈。R8 不压缩内嵌 OpenJDK 运行时本体。

> 本仓库路径含中文（`D:\ai\项目\...`）时：AGP 需要 `android.overridePathCheck=true`（已配置）；单元测试需要 classpath ASCII 复制 workaround（已在 `app/build.gradle.kts` 内处理）。

## 使用

1. 安装 APK，首次进入会请求通知权限（前台服务保活需要）。
2. 首页 → 服务器管理：新建服务器选择核心/版本，完整包使用「导入整包」。点击实例的「管理面板」进入各功能，启动设置可随时修改，下次启动生效。
3. 首次启动会弹出 Mojang EULA 确认。
4. 控制台实时收发：输入 `list`、`say hi`、`stop` 等；工具栏支持优雅停止与强制结束。
5. Forge/NeoForge 创建后自动在设备上跑安装器（需联网拉依赖），完成后即可启动。
6. 在文件管理中切换到目标目录后导入文件，按需选择同名处理方式；ZIP 点「解压」并选择目标目录，保留包内结构。多选后可以复制/移动、导出、压缩或删除；文本文件可以直接编辑。核心、模组和世界修改前需停服。

## 实例存放位置

新建服务器时在配置页选择存放位置，之后可以在首页卡片上看到实例具体在哪个目录。

**应用内部存储**（默认）：无需任何权限，读写最快，随应用卸载一起删除。文件只能通过应用内的文件页访问。

**用户可访问目录**：用系统文件夹选择器指定（例如 `Documents/MCServers`），实测可放在内置共享存储或 SD 卡上。文件管理器、电脑和 adb 都能直接读写实例文件（改 `server.properties`、导入整合包、备份地图都不用经过应用）。

选它之前需要知道这几件事：

- 需要「所有文件访问权限」。首次选择目录时会跳到系统设置页，打开该开关后返回即可继续；设置页的「存储位置」卡片可随时查看授权状态和已使用的外部目录。
- 共享存储是 FUSE 挂载，**读写明显慢于应用内部存储**（实测创建 500 个小文件：内部约 0.02 秒，共享存储 0.7–12 秒），启动、保存世界和插件读写配置都会变慢。
- 不支持符号链接和硬链接，文件名不能包含 `:`；选 SD 卡时还有 exFAT 的单文件 4GB 上限。
- 不要用其他应用或电脑移动、改名正在使用的实例目录，否则启动时会报「实例目录不存在」。
- 删除外部实例时会问一次：只从列表移除（文件全部保留）还是连同地图、配置、日志一起删除。
- 目录里会写入一个 `.mcs-instance.json` 标记文件，记录实例 ID、名称、类型和 MC 版本，便于日后辨认目录归属，请不要删除它。

## 首页、作者与应用更新

首页集中提供服务器管理、内网穿透、控制台、设置与开源项目入口。原服务器列表位于「服务器管理」二级页面。
作者：迷迭香のねこ；[Bilibili](https://space.bilibili.com/499259948)；[QQ 交流群 1121454408](https://qm.qq.com/q/QfgpHHPe4m)。
加群按钮优先唤起 QQ 群资料页，无法处理时打开官方分享链接；也可以复制群号自行搜索。

默认每次应用进入前台时检查 GitHub 正式发行版本，旋转屏幕、切换应用内页面不会重复检查。在设置中可关闭自动检查，或随时手动检查。
自动检查不会阻塞启动，也不会因网络错误弹窗；发现新版后显示更新说明，用户点击后通过浏览器下载 APK。不会自动安装。
当前版本从安装包元数据读取。检查源为 `braze-taffo/MCmobileserver` 的 `/releases/latest`，不推送草稿、预发行或低于当前版本的发行。

发布约定：标签使用 `vX.Y.Z`，APK 附件使用 `mcmobileserver-X.Y.Z-arm64-v8a.apk`（当前 release 构建）或 `mcmobileserver-X.Y.Z-universal.apk`（包含所需 ABI 的通用包）；若发布 x86_64 专用包则后缀为 `-x86_64.apk`。
版本对应的 `versionName` 必须同步，`versionCode` 必须递增，并保持同一签名。源代码 ZIP、调试 APK 和其他架构附件不会作为更新包推送。
尚未发布 Release 时显示「暂无正式发行版」，新版没有适配的 APK 时单独说明。

## FRP 内网穿透

首页「内网穿透」可配置并启动标准 FRP TCP 隧道。内置官方
[frpc 0.71.0](https://github.com/fatedier/frp/releases/tag/v0.71.0) Android ARM64 客户端，
构建时自动下载并校验 SHA-256，Apache-2.0 许可证随 APK 打包。

1. 准备自建或服务商提供的 frps 节点，取得节点地址、控制端口、Token 和可用的远程端口。
2. 启动手机上的 Minecraft 服务器，本地端口填写实例 `server.properties` 的 `server-port`（默认 25565）；`server-ip` 保持留空以接受本地回环连接。
3. 填写 FRP 配置并启动穿透。隧道名称在同一节点上必须唯一；节点要求 `user` 前缀时填写对应值。
4. 显示「隧道已建立」后复制公网联机地址给玩家。节点防火墙和安全组需要开放远程 TCP 端口。
5. 隧道独立于 MC 服务器运行，结束联机后在页面或通知中「停止穿透」。退出页面不会停止；应用进程被系统终止后需要手动重启穿透。

支持 Token 认证、TLS、断线重连、最近 300 行日志和独立前台通知。
Token 存在应用私有存储中，禁用备份；界面掩码显示，日志替换 Token。
运行配置在停止时删除。仅 ARM64 设备支持穿透；x86_64 调试构建仍可使用其他功能。
本功能不提供公共节点，不支持需要魔改客户端的服务商、OIDC 或自定义证书配置。
隧道注册成功不代表 MC 已就绪或公网防火墙已放行。

自建节点的最小 `frps.toml` 示例（节点服务端需自行部署）：

```toml
bindPort = 7000
auth.method = "token"
auth.token = "替换为你自己的随机长密码"
allowPorts = [{ single = 25565 }]
```

在有公网 IP 的节点运行 `frps -c frps.toml`，开放 TCP 7000 和 25565；手机填写相同 Token，远程端口 25565。

## 已知限制

- targetSdk 35 + FGS specialUse，侧载安装；未适配 Play 上架流程。
- 内存建议设备 ≥4GB；运行时建议插电并关闭电池优化（厂商省电策略可能杀后台）。
- 带原生库（.so）的 mod 未验证，v1 以纯 Java 服务端为主。NeoForge 的硬件探测（oshi/JNA）在 Android 上加载原生库失败，会打一条警告后继续，不影响启动。
- 同一时间只运行一个实例（v1 设计）。
- 服务器停止时 MC 自身会 `System.exit()`，`:server` 进程随之结束属正常现象，UI 会自动识别。
- Forge/NeoForge 安装器需要联网拉几百 MB 依赖，设备网络不稳时会报 “These libraries failed to download. Try again.” —— 重跑安装器即可续传，已下好的库会跳过。
- JVM 初始化失败时 HotSpot 会直接 `exit(1)`，管道里最后几行会随进程丢失，所以初始化期的 stdout/stderr 会额外落盘到 `logs/jvm-init.log`，UI 会把该文件内容补进控制台。

## 目录结构

```
app/src/main/cpp/            libjvmlauncher（C：dlopen+JNI_CreateJavaVM+调 main+chdir）
app/src/main/java/com/mcmobile/server/
  core/jre/                  JRE 解压管理（双运行时）
  core/launch/               LaunchSpec / Manifest 读取 / unix_args 解析
  core/console/              LocalSocket 控制台桥（服务端 + UI 会话）
  core/props/                server.properties 解析
  core/                      EULA / 版本兼容 / 核心下载安装编排
  data/                      实例模型与存储、五类型元数据 API
  service/                   :server 前台服务（进程隔离）
  ui/                        Compose 界面
```
