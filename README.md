# MC Mobile Server

在 Android 手机 / 平板上运行 **Minecraft Java 版服务器**的应用。内嵌 Android 原生编译的 OpenJDK 运行时（21 与 25），支持 Vanilla / Paper / Fabric / Forge / **NeoForge** 五类服务端核心，提供下载、导入、启动、实时控制台、server.properties 编辑与文件管理。

## 运行原理（无任何模拟层）

```
┌─ UI 进程（默认）────────────┐      ┌─ :server 进程 ──────────────────────┐
│ Compose 界面 / 下载器 / 实例管理 │      │ 前台服务（specialUse，崩溃互相隔离）   │
│         │ LocalSocket       │◄────►│ pipe()+dup2() 接管 stdin/stdout/stderr │
└─────────┴───────────────────┘      │   ↓                                  │
                                     │ dlopen(nativeLibraryDir/libjvmXX.so)  │
                                     │   ↓ JNI_CreateJavaVM                  │
                                     │   ↓ FindClass → main(String[])  ← 普通主类
                                     │ HotSpot JVM，JIT 编译为 ARM64 机器码    │
                                     └──────────────────────────────────────┘
```

- 内嵌 JRE 来自 [AngelAuraMC/mobile OpenJDK 构建](https://github.com/AngelAuraMC/angelauramc-openjdk-build)（PojavLauncher 系，NDK 原生编译、bionic libc），**不是 x86 模拟器**，性能为原生 JVM 水平。
- `libjvm<-major>.so` 打包进 APK 的 `jniLibs`（nativeLibraryDir 天然可执行，任何 targetSdk 合法），其余文件在首次启动时从 assets 解压到应用数据目录。
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
6. 实例文件页可导入 mod（放入 `mods/`）；配置页可编辑 server.properties。

## 已知限制

- targetSdk 35 + FGS specialUse，侧载安装；未适配 Play 上架流程。
- 内存建议设备 ≥4GB；运行时建议插电并关闭电池优化（厂商省电策略可能杀后台）。
- 带原生库（.so）的 mod 未验证，v1 以纯 Java 服务端为主。
- 同一时间只运行一个实例（v1 设计）。
- 服务器停止时 MC 自身会 `System.exit()`，`:server` 进程随之结束属正常现象，UI 会自动识别。

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
