# 1.1.9：启动兼容性与局域网访问

2026-10-26，本地未发布版本。本文记录 versionCode 23 开发阶段的兼容性收紧与网络验证；
后续扩展 Java 规则及最终 versionCode 24 的 1.1.9 / 1.1.9-rootless 见 [DEX 调查](DEX_COMPATIBILITY_1.1.9.md)。不代表已发布的 1.1.8 行为。

## 启动退出调查

设备：Android 16、LSPosed 1.10.2。中国移动 `com.greenpoint.android.mc10086.activity` 12.5.5、
运动世界 `com.zjwh.android_wh_physicalfitness` 7.3.93 均在启动退出。用户确认移出 PosEdMCP 作用域后仍退出；
这一项是用户反馈，不能据此认定所有退出都由本模块造成，也不能认定环境已经排除其他影响。

中国移动开启旧隐藏策略时，崩溃栈位于 `libposednative.so` 的 `filtered_open` 原函数调用，
出现跳入不可执行 CFI 映射及递归栈。这证明该原生入口存在兼容风险，尚未定位原函数跳板失效的底层原因。
关闭旧隐藏策略后仍有不同的 native 故障；运动世界关闭后记录为 `EXIT_SELF`、status 28，未捕获可定位的 Java 异常。
退出码本身不能证明某一种检测机制。

本轮收紧：

- 原生文件隐藏改为独立实验开关，默认关闭；旧配置未保存该字段时也关闭。
  中国移动精确包名自动跳过该入口，即使手动开启。`scope_status.builtins.files` 区分请求、启用与兼容原因。
- 关闭原生规则会清空过滤名单，但已安装的 libc 跳板不会动态卸载。关闭并重新打开目标应用，才能确保该进程没有安装这些钩子。
- Dex 视图仅拦截 `Field.get`；不再安装 `Class.getDeclaredField/getDeclaredFields` 元数据钩子。
  保留字段、返回组件类型与原缓存，未知结构沿用原结果。
- maps 使用明确文件名及路径边界，包含 `/memfd:XposedBridge.jar (deleted)` 的精确形式。普通 `liblspd-helper.so`、`libxposed-client.so`、
  `lsposed-notes.txt`、`XposedBridge.jar.backup` 不因包含关键词而被过滤。

这些是降低已知冲突与误过滤的措施，不能宣称两个应用已恢复，也不能宣称所有检测器通过。
进一步增加全局钩子不保证稳定性或隐藏性；后续应按应用、按入口验证开关对照和正常业务，再增加实验规则。
原生 inline SVC、直接 Binder transact、Unsafe、APK 扫描及其他 maps 读取通道仍不在当前保证范围。

## 局域网访问

主页展开服务信息，在 MCP 地址处点击「切换到局域网访问」。默认仍监听 `127.0.0.1`；
开启后监听所有 IPv4 接口 `0.0.0.0`，页面列出可连接的实际 IP 地址和回环地址。
客户端使用实际设备 IP，不能把 `0.0.0.0` 当作连接地址。

此模式使用 HTTP，应仅在可信网络开启。`/health` 继续提供不含令牌的公开健康查询；
MCP 请求继续需要 Bearer 令牌，敏感工具沿用原确认流程。兼容 SSE 的 URL token 仅接受 GET，不能授权 POST。
不添加浏览器 CORS 权限。rootless 权限健康快照保持原行为。

切换仅重绑 HTTP 监听，不重启模块桥接、不轮换令牌，不重启设备；现有 HTTP/SSE 连接会断开。
绑定或保存失败时尝试恢复原监听和设置。点击「切换到仅本机访问」撤销网络监听，最后的模式会保存。
模块内部桥接仍只监听回环地址。

## 验证

- 主机策略回归 164 项断言、原 Lua 脚本回归通过。
- 网络回归使用真实 TCP，覆盖默认回环隔离、通配监听、反复切换、端口占用、半包连接及 SSE 关闭；当前主机 19 项断言通过。
- 普通版与 rootless 版分别通过 assembleDebug、lintDebug，lint 均为 0 error；安装了同签名的 1.1.9-rootless-dev。
- 设备上的策略开关对照通过 18 项检查：Dex 字段保留、视图开 1/关 2、缓存恒为 2；
  仅 Field.get 的 1 个 Dex 钩子、16 个 Java 文件钩子及 87 个 PM 钩子安装均无失败。
  Java reader 移除实际 memfd 桥接控制行，native 读取仍可见；关闭后 Java 读回。普通 Reader、原 PM、真实 JNI 和自身 native 文件访问保留。
  旧策略配置默认不请求、不启用 native 文件钩子。
- 设备网络 17 项检查通过：实际健康与 Bearer 请求、缺失/错误令牌拒绝、POST URL token 拒绝、GET SSE、
  通配监听与仅回环切换、旧 SSE 断开、服务进程/uptime/令牌保留。内部桥接始终只在回环。
  当前电脑没有直接连通设备的局域网地址，跨设备局域网端到端连通尚未验证；不能把 ADB 转发请求等同于 LAN 客户端验证。
- 最终构建复测两个应用仍退出。中国移动仍为 0x88c/0x79c 的 native 故障，本次日志没有旧 filtered_open 或 libposednative 崩溃帧；
  也没有捕获它安装 Java 策略的日志，不能单凭这次比较宣称已修复原生跳板。运动世界 Java 策略安装成功后仍为 EXIT_SELF 28。
  主机及夹具结果不能替代第三方应用正常业务与 LSPatch 全链路验证。

原始设备日志仅保存在忽略的本地 `.tools/runtime-device`，不纳入仓库或发布资产。
