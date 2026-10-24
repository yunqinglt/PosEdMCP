# PosEdMCP 1.1.8

本次提供普通版与 rootless 版，两者同包名、同签名，不能并排安装。versionCode 均为 22，可覆盖先前的开发构建。

| 构建 | 分支 / 标签 | 安装包 |
| --- | --- | --- |
| 普通版 | master / v1.1.8-stable | PosEdMCP-1.1.8.apk |
| rootless 版 | rootless / v1.1.8-rootless | PosEdMCP-1.1.8-rootless.apk |

旧 v1.1.8 标签对应撤回前的源码，保留历史；本次普通版使用 v1.1.8-stable。两份 Release 各自提供安装包及 SHA256SUMS，guard 模块仍为未改动的 1.0，仅用于有 Magisk 的设备。

## 共同变更

- MCP 服务版本号跟随应用构建版本，健康查询与客户端握手不再报告旧版本。
- 分离 bootstrap 凭据/配置进程，提前恢复作用域策略；增加诊断日志 ZIP 导出。
- 包查询隐藏覆盖选定的 PosEdMCP、Display、Deploy 及常见框架管理器，保留目标自身、真实 PackageManager 和原权限。
- 补充 XposedBridge.jar 的 maps 过滤，以及堆栈类名、方法名、文件名和字符串表示；保留行号与真实 JNI 位。
- 保留 DexPathList 反射字段，只过滤读出的模块路径副本，不修改原缓存。
- 精确框架文件规则统一 Java 元数据/打开/目录列表及 native 的不存在语义。
- libc getaddrinfo 广告域名规则默认关闭，不包含预置名单。广告 SDK 策略页面与策略导出仍为后续计划。

隐藏策略仍属实验功能，可在「更多信息 → 特殊设置」关闭。已知边界及控制例见 [CONCEALMENT.md](CONCEALMENT.md)，不承诺所有第三方应用或整个检测器通过。

## rootless 版

使用官方 libsu core 6.0.0。新增 shell_exec，确认后以实际获得的 root 或应用 UID 执行；root_shell_exec 和原 root 专属操作继续要求 root。
/health、module_status 和 device_info 报告已验证的权限快照，健康查询本身不执行 shell 或请求 root。
每条命令独立会话，保留二进制输出、stderr/退出码、有界收集和超时隔离。

LSPatch 不授予 system/root 权限，也不注入 system_server。无 root 回退已用真实 libsu 独立 APK 验证，当前 LSPosed 设备的结果不能代表 LSPatch 完整注入链已通过。详情见 [rootless 分支说明](https://github.com/yunqinglt/PosEdMCP/blob/v1.1.8-rootless/docs/ROOTLESS.md)。

## 验证

- 148 项主机运行时断言及现有钩子脚本回归通过。
- 两种构建的 assembleDebug / lintDebug 完成，lint 0 error；私有框架入口仍有预期警告。
- Android 16 / LSPosed 1.10.2 开关对照：Dex 视图为 1/2 项，原缓存恒为 2；新增 dex / Java 文件钩子为 3/16 个且安装失败均为 0；普通文件与自身调用控制例正常。
- 真实 libsu 的无 su 进程环境通过 18 项断言，覆盖权限状态、root 专属拒绝、文本/二进制、输出上限、超时、并发与不可用 shell。
- APK 沿用仓库发布脚本的 debug 签名方式。设备测试不需要重启，LSPatch 完整链路及 API 102 原生通道仍需对应设备验证。
