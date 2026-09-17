# MC Mobile Server

## 开源协议

本项目自身代码采用 [GNU Affero General Public License v3.0](LICENSE)（SPDX：`AGPL-3.0-only`）。分发受本协议覆盖的程序时，应按协议提供相应源码；修改后的程序通过网络与用户交互时，也应按协议向这些用户提供相应源码获取方式。具体权利与义务以许可证全文为准。

OpenJDK、FRP 及其他第三方组件保留各自的版权、许可证与分发要求，不因本项目采用 AGPL-3.0 而改变。

在 Android 手机 / 平板上运行 **Minecraft Java 版服务器**的应用。内嵌 Android 原生编译的 OpenJDK 运行时（21 与 25），支持 Vanilla / Paper / Fabric / Forge / **NeoForge** 五类服务端核心，提供下载、导入、启动、实时控制台、server.properties 编辑与文件管理。

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
- MC 1.17–1.21.x 使用 Java 21；MC 26.x（2026 日期式版本）使用 Java 25，应用按版本自动选择。

## 支持的服务端

| 类型 | 来源 | 说明 |
|---|---|---|
| Vanilla | piston-meta（Mojang 官方） | 1.17+ release |
| Paper | fill.papermc.io v3 | build 自动选最新 |
| Fabric | meta.fabricmc.net | 自动附带原版 server.jar |
| Forge | maven.minecraftforge.net | 1.18+，安装器在设备上执行 |
| NeoForge | maven.neoforged.net | 1.20.1+ 全部（含 MC 26.x） |

也支持从本地导入任意核心 jar / Forge/NeoForge 安装器 jar。

## 构建

要求：JDK 17+、Android SDK（platform 36、build-tools、NDK 27.x、CMake 3.22.1）。

```bash
./gradlew assembleDebug     # 双 ABI（arm64-v8a + x86_64），用于真机与模拟器
./gradlew assembleRelease   # 仅 arm64-v8a，需 keystore.properties（缺失时回退 debug 签名）
./gradlew testDebugUnitTest # 单元测试
```

首次构建会自动执行 `prepareJreAssets`：下载两个 OpenJDK 构建产物（tar.xz，SHA-256 已锁定），拆分为 `jniLibs/libjvm<major>.so` 与 `assets/jre/<abi>/<major>.zip`。产物在 `build/jre/` 缓存。

> 本仓库路径含中文（`D:\ai\项目\...`）时：AGP 需要 `android.overridePathCheck=true`（已配置）；单元测试需要 classpath ASCII 复制 workaround（已在 `app/build.gradle.kts` 内处理）。

## 使用

1. 安装 APK，首次进入会请求通知权限（前台服务保活需要）。
2. 主页 → 新建服务器 → 选类型 → 选版本（或导入 jar）→ 设置内存（默认设备内存 30%）。
3. 首次启动会弹出 Mojang EULA 确认。
4. 控制台实时收发：输入 `list`、`say hi`、`stop` 等；工具栏支持优雅停止与强制结束。
5. Forge/NeoForge 创建后自动在设备上跑安装器（需联网拉依赖），完成后即可启动。
6. 实例文件页支持多选导入文件；点击「导入 Mod / ZIP」可批量导入 JAR 或 ZIP，压缩包中各层文件夹内的 JAR 会自动放入 `mods/`（其他文件忽略，同名文件跳过）。在 `mods/` 内直接导入 ZIP 也会自动解压。文件和文件夹右侧的删除按钮会弹出确认框，确认后永久删除；建议先停止服务器。配置页可编辑 server.properties。

## FRP 内网穿透

主页右上角「内网穿透」可配置并启动标准 FRP TCP 隧道。内置官方
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
