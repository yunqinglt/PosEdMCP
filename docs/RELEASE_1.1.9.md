# PosEdMCP 1.1.9

| 版本 | 标签 | 安装包 |
| --- | --- | --- |
| 普通版 | `v1.1.9` | `PosEdMCP-1.1.9.apk` |
| rootless | `v1.1.9-rootless` | `PosEdMCP-1.1.9-rootless.apk` |

两版 versionCode 均为 24，沿用 1.1.8 的包名和签名，可覆盖升级，但不能并排安装。各发布附 SHA256SUMS。普通版还附未改动的 Magisk guard 1.0，按需安装。

- 主页可切换局域网访问，默认仅监听本机。切换保留认证令牌和内部进程桥。局域网模式使用 HTTP，仅在可信网络启用。见[局域网访问说明](COMPATIBILITY_AND_LAN.md)。
- 扩展 Java 隐藏默认关闭，仅对填写完整包名的作用域应用生效。覆盖选定堆栈、类与资源、PATH、Java 属性、proc 文件及明确的挂载查询。Runtime.exec 的六个重载共用父进程 stdout 过滤入口。
- 原生文件策略与原生 mount 策略各自默认关闭，保留已知加载冲突的兼容排除。见[环境与挂载策略](ENVIRONMENT_CONCEALMENT.md)。
- rootless 标题新增 Rootless 标签，继续使用 libsu，健康查询报告实际权限状态。无 root 时普通 shell 可用，root 专属功能仍需要 root。

两版构建和 lint 通过。主机通过 260 项策略断言、19 项传输断言及钩子脚本回归；Android 16 / LSPosed 上验证了 PATH、Java 属性、六种 Runtime.exec 调用和原生 mount 的命中。LSPatch 完整注入链及跨设备 LAN 连通性未验证。

隐藏仍属实验功能。原生直接读取 maps 等入口仍能观察到模块映射；中国移动与运动世界的启动问题尚未解决。详情见 [DEX 调查](DEX_COMPATIBILITY_1.1.9.md)和 [root 复查](ROOT_AUDIT_1.1.9.md)。
