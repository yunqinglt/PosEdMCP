# rootless 1.1.8

发布分支 `rootless`，应用版本 `1.1.8-rootless`（versionCode 22），标签 `v1.1.8-rootless`。shell 后端的无 root 回退已经过独立真机回归，LSPatch 完整注入链仍待对应设备验证。
它与普通构建使用相同包名和签名，安装时会覆盖当前版本，设置保留，不能并排安装。

## shell 与权限

后端为官方 [libsu core 6.0.0](https://github.com/topjohnwu/libsu/tree/6.0.0)，通过 JitPack 解析
`com.github.topjohnwu.libsu:core:6.0.0`。libsu 在 root 不可用/未获准时回退到普通 `sh`，
不提供 adb、system 或 root 提权能力。用户启动 MCP 服务时后台初始化，健康查询不请求授权。

- 新增 `shell_exec`：沿用 shell 确认流程，允许以已获得的 root 或应用 UID 执行，返回实际 `root`、`uid` 和 `backend`。
- `root_shell_exec`、截图的 root 后备路径、冻结与其他旧 root 操作继续要求 root，无权限时明确失败，不能静默降级执行。
- 每条命令使用独立 libsu 会话，超时不关闭其他请求的会话。原始管道保留二进制字节，stdout/stderr 分开有界收集，stdin 保持非交互 EOF。
- 冻结 watchdog 通过同一后端启动且仍要求 root。关闭超时会话不保证回收命令自行脱离的所有子进程。

`GET /health` 的 `permissions` 包含：

| 字段 | 含义 |
| --- | --- |
| rootGranted | 最近一次实际 shell 验证是否为 root；未验证为 null |
| shellAvailable | 是否成功获得 shell；未验证为 null |
| mode | unknown / unavailable / app / root |
| uid | 已验证的 shell UID；未知为 null |
| checkedAt | 验证时间，服务启动或执行命令时刷新 |
| systemBridgeConnected | 当前是否连接 system_server 的模块桥 |
| accessibilityConnected | 当前无障碍服务是否连接 |

这些字段是可追溯的权限快照，不把历史设备 root 状态冒充刚刚获得授权。
健康查询不执行新 shell，不弹 root 授权框，不包含 token、命令输出、日志或 peer 应用列表。
`module_status.shell`、`device_info.root` 也报告同一权限信息。

## LSPatch 边界

[LSPatch](https://github.com/LSPosed/LSPatch) 将模块注入被修补应用，不会给 PosEdMCP 注入 system_server 或授予系统权限。
本分支保留作用域应用桥、普通 shell 和已启用的无障碍路径；需要 system/root 的功能按实际能力返回不可用。
已有服务/凭据通道保留，LSPatch 的框架原生 hook 通道以及完整注入链仍需要对应设备验证。
当前连接设备为 Android 16 / LSPosed 1.10.2，不能以其结果宣称完成 LSPatch 真机验收。

## 回归

`tests/rootless-runtime-fixture` 使用实际 libsu AAR 和生产 RootShell，进程内 PATH 不含 su，
不更改系统 PATH 或任何现有 root 授权。覆盖普通 shell、权限状态、拒绝 root 专属命令、
stdout/stderr/退出码、引用和 UTF-8、NUL/高位字节、输出上限、超时、并发和不可用 shell。
测试 APK 不加入 Xposed 作用域，不包含 MCP 服务或认证凭据。
