# 1.1.9：DEX 调查与扩展 Java 视图

2026-10-26。本轮版本号为 `1.1.9` / `1.1.9-rootless`，versionCode 24。
仅本地开发及装机验证，未提交、推送或发布；版本号不等同于所有第三方应用兼容。

后续已同步核对普通版，加入 Rootless 标识并用 root 复查原生映射及多模块环境；
最新装机与调查结果见 [root 复查](ROOT_AUDIT_1.1.9.md)。下文装机状态为该阶段的历史记录。

## 从目标 APK 得到的证据

通过设备上 PosEdMCP 的 `apk_list`、`dex_search`、`dex_classes` 和 `smali_disassemble` 读取实际安装版本，
没有修改目标 APK、签名或数据，也没有拦截目标的退出指令。

| 应用 | 可见 DEX 与观察 | 结论边界 |
| --- | --- | --- |
| 中国移动 12.5.5 | `classes.dex` 21,820 字节、7 个 `com.secneo.apkwrapper` 壳类。H.a(int) 读取 `/proc/self/status` 并解析 `TracerPid`，另有 `/proc/self/exe`、反射和原生加载入口。 | 主要业务及原生保护逻辑不在可见 DEX，未证明哪一项导致当前退出。ADB 连接本身不等于 TracerPid 非零，未伪造该字段。 |
| 运动世界 7.3.93 | `classes.dex` 71,061,232 字节、30 个可见类；29 个属于 `com.netease.nis.wrapper`。MyJni 有异常 getStackTrace/Arrays.toString、原生 getEnvInfo 和反射调用。 | 大文件大小不代表业务代码已解密；可见内容仍主要是壳。 |

另外反编译了运动世界字符串解码器 `a.auu.a.c`，验证其 Base64 后按 7 字节循环 XOR 的规则。
本地解码确认 MyJni.b 读取 `/proc/self/maps`，NEDialog 包含 Xposed 环境提示。
但 MyJni.b 的可见逻辑主要读取 app_process/ELF 架构，不能仅据提示文字断定当前退出来自 Xposed 检测。
可见的 NEDialog.exitApp 使用 exit(0)，不能据此解释先前的原生 EXIT_SELF 28。

完整 smali、解码结果及原始日志仅保留在被忽略的 `.tools/runtime-device`；不把第三方完整反编译代码纳入仓库或发布。

## 扩展策略

在「更多信息 → 特殊设置」中开启「扩展 Java 隐藏（实验）」，填写明确包名后保存并重新打开目标。
默认关闭，空名单不选择任何应用。名单按完整包名匹配，仍受 LSPosed 作用域和原隐藏主开关限制，
不进入系统应用/system_server，也不出现在普通用户钩子库。

- `Throwable.getStackTrace`、`Thread.getStackTrace/getAllStackTraces` 返回移除明确模块/框架帧的副本，
  不修改原缓存、普通帧或线程键。已有独立 StackTraceElement Getter 规则仍保留。
- 堆栈名单增加真机观察到的精确生成类名 `LSPHooker_`，不扩大到所有相似名称。
- `Class.forName(String, boolean, ClassLoader)`、`findLoadedClass` 处理选定类名及对象数组描述符；
  ClassLoader 的 class 资源查询按精确命名空间返回空，普通资源不改。
- **不拦截单参数 `Class.forName(String)`**。中间构建在运动世界触发 WorkManagerInitializer 找不到：
  该入口依赖调用者的类加载器，反射钩子跳板会改变调用上下文。最终实现撤掉这一钩子，并添加本应用普通类对照。
- NIO `readAllBytes/readAllLines/lines` 和平台支持时的 `readString`，以及 RandomAccessFile.readLine，
  只处理当前进程 maps。包含 `/proc/self/maps`、`/proc/thread-self/maps`、当前 PID 及当前进程的 task/TID 路径；
  不处理其他进程或普通同名文件。保留未过滤行的字节、CRLF、末尾无换行、流关闭及原异常语义。
- 没有安装新原生文件钩子。旧 native 文件实验开关与中国移动兼容例外保持原行为。

文件入口只返回读取视图，不替换 `/proc` 文件，不写回系统文件，不修改 Dex 元素缓存。
关闭扩展策略会停止过滤；要确保进程未安装这些入口，需关闭后重新启动目标。

API 契约参考：[Throwable](https://developer.android.com/reference/java/lang/Throwable)、
[Class](https://developer.android.com/reference/java/lang/Class)、[Files](https://developer.android.com/reference/java/nio/file/Files)。
实际可用性以设备反射结果为准，不能仅用主机 JDK 能力推断 Android 支持。

## 验证与未解决问题

- 主机生产策略回归目前 213 项断言通过，包括 43 项扩展视图与调用者敏感入口检查。
- 设备支持的扩展入口为 15 个；两个 `Files.readString` 重载不存在，状态以 unavailable 单独报告，不能算作已覆盖。
- `scope_status.builtins.extended` 报告 enabled/installed/registered/failed/unavailable。
  最终设备 24 项检查通过：开启/关闭的真实阳性控制、原缓存/普通类/普通文件保留、流与读取接口，
  名单外应用的完整结果与关闭基线一致，且冷启动时不安装扩展入口。
- 普通版和 rootless 版均通过 assembleDebug、lintDebug（0 error）；设备安装同签名的 1.1.9-rootless。
- 最终对两目标启用扩展规则复测：中间构建新增的 WorkManager ClassNotFoundException 未再出现，
  但中国移动仍为 0x88c/0x79c 原生故障，运动世界仍为 EXIT_SELF 28，没有恢复正常启动。
- 恢复原测试 APK，保留原隐藏主开关及 DNS 设置。脚本曾确认扩展开关关闭；随后收尾检测到设备端已再次开启扩展及原生实验开关，
  因设备正在其他应用中使用，保留最新选择，未再覆盖。扩展名单为上述两目标；LAN 关闭。无障碍设置及设备启动标识不变。

这些规则覆盖已列明的 Java 读取视图。JNI FindClass、原生直接 maps 读取/inline SVC、dl_iterate_phdr、
线程/内存检查、直接 Binder、Unsafe、离线 APK 扫描仍可能暴露框架。
内部调用识别仍依赖堆栈名称，不能保证抵御主动伪装调用者。不能将此版本称为“完全不可检测”。
两目标在移出本模块作用域后仍无法启动是用户反馈，尚未排除设备环境或其他模块影响。
早先的 [启动调查与局域网验证](COMPATIBILITY_AND_LAN.md) 保留各阶段结果；未重启系统进程或设备。
