# Deploy 契约副本

本目录为 PosEdMCP 编译 AIDL 客户端和 MCP 参数 schema 保留的源码副本。
维护源位于独立 Deploy 仓库 `deploy-api/`，本机为 `E:/deploy/deploy-api`。
PosEdMCP 不依赖这个外部路径，独立构建仅使用本目录。

当前基线为 Deploy 0.4.0 / `status.apiVersion=2`。变更时先在 Deploy 仓库修改，
再同步 `build.gradle.kts`、`src/main/aidl/dev/deploy/api/IDeploy.aidl` 和
`src/main/java/dev/deploy/api/DeployContract.java`，分别构建两端。
不要只修改这里的协议或 schema。

v2 将 `assemble` / `analyzeBuffer` 追加在末尾，保持既有 AIDL transaction ID。
新方法要求 v2 APK，没有自动降级；破坏签名或顺序的修改必须同时更新两款应用。
`prepareLibrary` 直接返回 PFD、经 `out Bundle` 返回元数据，保留 Binder 的关闭语义。
`analyzeBuffer` 接收已写完、去除路径的 1–4096 字节只读文件快照，接收端负责关闭；
不接受管道。接口只分析/生成字节，不执行目标代码。
ConfirmationGate 和目标进程内存读写/加载仍由 PosEdMCP 维护。

在本仓库核对源码：

```powershell
git diff --no-index -- deploy-api/build.gradle.kts ../deploy/deploy-api/build.gradle.kts
git diff --no-index -- deploy-api/src ../deploy/deploy-api/src
```
