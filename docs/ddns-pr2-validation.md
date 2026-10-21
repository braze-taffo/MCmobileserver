# PR #2 动态域名审查与凭据并发修复

验证日期：2026-10-21。

审查对象为 PR #2 的 `cf0f01f5c47383230843be34c8650a48d6a43e91`，并入当前主线
`d90058f0c1e06bee3a8e701db0b1d19ffd5fd8ed` 后验证，保留主线的 v1.0.7 元数据修复。

## 问题与修复

后台读取配置期间，界面可能通过另一个 `DdnsStore` 实例保存新配置。原实现分开读取
`config` 和 `secrets`，即使保存是原子的，也可能将旧服务商与新服务商的 Token 组合，
通过校验后发给错误的服务商。

`Kv.snapshot()` 约定返回独立、原子的全部值快照；Android 实现通过一次
`SharedPreferences.getAll()` 读取。`DdnsStore.load()` 从这一快照取得配置、密文和
上次更新信息，避免读到不同版本的数据。原有 AES-GCM 加密及旧数据迁移保持有效。

## 回归证据

新增 `DdnsStoreSnapshotTest`，使用原子存储、两个独立 Store、线程与闩锁控制保存时机，
通过本地 MockWebServer 检查实际请求中的 Token：

- DuckDNS 配置读取期间保存 Cloudflare 配置，DuckDNS 请求仍只能携带 DuckDNS Token。
- Cloudflare 配置读取期间保存 DuckDNS 配置，查询及更新请求仍只能携带 Cloudflare Token。
- 并发读取结束后再次加载，可以取得完整的新配置。

测试使用虚构凭据和本地 HTTP 服务，不访问真实 DNS 服务商。

同一组测试在修复前为 **2 项失败**，修复后为 **2 项通过**。与凭据加密、损坏恢复和
旧数据迁移测试合并运行时，**21 项通过，0 项失败或跳过**。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.mcmobile.server.core.ddns.DdnsStoreSnapshotTest --tests com.mcmobile.server.core.ddns.DdnsSecretBoxTest --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

完整验证为 `BUILD SUCCESSFUL`：286 项测试中 285 项通过、1 项跳过，0 项失败或错误；
Lint 为 0 项错误、40 项警告、2 项提示；Debug APK 构建成功。`git diff --check` 通过。

本次未执行真机或模拟器验收、真实 DNS 更新或玩家连接测试。
