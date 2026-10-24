# 作用域隐藏策略 1.1.8

在「更多信息 → 特殊设置」中统一开启或关闭。只对模块作用域内的第三方应用安装，
不进入系统应用或 system_server；内置钩子独立于普通钩子库。保存后重新打开目标应用。

## 包名范围与权限

使用精确包名，不按包含关系匹配，始终保留目标包自身：

- PosEdMCP、Display、Deploy：`dev.posedmcp`、`dev.display`、`dev.deploy`。
- Xposed/LSPosed/EdXposed/Dreamland 管理器：`de.robv.android.xposed.installer`、
  `org.lsposed.manager`、`org.lsposed.lspd`、`org.meowcat.edxposed.manager`、
  `com.solohsu.android.edxp.manager`、`top.canyie.dreamland.manager`、`io.github.libxposed.manager`。
- Magisk 管理器：`com.topjohnwu.magisk`、`io.github.vvb2060.magisk`。

不撤销或伪造 `QUERY_ALL_PACKAGES` 及厂商安装列表权限，不用 null 替换 Context 的 PackageManager。
过滤具体 PackageManager 实现的返回值，并处理厂商 Context 返回的覆盖实现。
权限已获准时同样过滤选定包；其他包沿用原平台可见性与原异常。
模块自己的调用保留访问通道，应用仍能查询自身。

## ApplistDetector 路径对照

研究来源：[Dr-TSNG/ApplistDetector，固定提交 9145ff5](https://github.com/Dr-TSNG/ApplistDetector/tree/9145ff503ea298b7491c5f56aed4bbd5fd81ad06/library/src/main)。
按其实际使用的入口补充通用过滤，没有修改检测器结果或依赖检测器包名。

| 检测入口 | 本轮处理 |
| --- | --- |
| getInstalledPackages / getInstalledApplications | 在真实列表中移除选定包，保留其他条目及对象；不修改原缓存列表 |
| queryIntentActivities / XposedModules 元数据扫描 | 过滤选定包的 ResolveInfo；同样处理 service/provider/receiver 查询 |
| getPackageInfo / getApplicationInfo / getPackageUid / getInstallSourceInfo | 对选定包使用相应的不存在语义；launch/resolve 返回空，availability 返回 false |
| getPackagesForUid / getNameForUid | 过滤反查结果，保留 UID 中其他可见包 |
| 隐藏 IPackageManager 代理 | 处理 List、String[] 与 ParceledListSlice，覆盖绕开 Context 的 Java 代理访问 |
| Runtime.exec / ProcessBuilder 的 pm list packages、cmd package list packages | 过滤该子进程的 stdout，支持 -f/-U；保留进程生命周期、stderr、退出码与原命令 |
| libc access/faccessat/stat/lstat/fstat/fstatat/open/openat/opendir | 选定包的私有及 Android data/media/obb 目录探测返回 ENOENT |
| libc 导出的 syscall 包装函数 | arm64 寄存器透传，仅拦截相关文件探测编号，保留其他系统调用 |

命令规则只识别普通包列表命令与简单 `sh -c`，不改写命令、不提权，不解析任意管道或复合 shell。
stdout 按行过滤、保留 CRLF/EOF 和非 package 行，单行缓冲上限 64 KiB。
原生规则保留其他路径和目标自身；处理目录 fd 相对路径与词法 `.`/`..`，不会调用 realpath 暴露文件存在性。
原生 hook 必须由框架提供，实际安装数、失败数及命中数由 `scope_status.builtins.files` 报告。

## maps 与堆栈

- 原有跟踪 maps reader 的规则增加大小写不敏感的 `XposedBridge.jar`，不改普通文本 Reader。
- `StackTraceElement.getClassName/getMethodName/getFileName/toString` 成组改写模块和框架痕迹。
  字符串表示保留原行号/原生行号语义，普通应用堆栈不变。
- 堆栈名单加入 `com.android.internal.os.ZygoteInit` 及其内部类；不把这些平台类加入 ClassLoader 禁止名单。
- `Method.getModifiers` 仍仅隐藏已挂钩 Java 方法的合成 native 位，保留真实 JNI 方法。

## 验证与边界

主机执行生产回调与流过滤：包名边界、UID 混合结果、Binder slice 副本、原权限异常、
开关、命令输出的字节/EOF、连续 maps 行、堆栈 Getter 和路径归一化均有回归。
`tests/run-runtime.ps1` 共通过 118 项断言；`tests/run-hooks.ps1` 的现有脚本回归通过。
最终 APK 的 assembleDebug、lintDebug 与签名校验完成，lint 为 0 error；私有框架入口仍有预期警告。

Android 16 / LSPosed 1.10.2：测试应用持有 QUERY_ALL_PACKAGES，PackageManager 及其缓存对象未替换，
自身仍可见。关闭时可以查到 PosEdMCP/Deploy 的单包信息、安装来源、启动入口、Intent 查询及 UID 反查，
开启后这些入口不再返回它们；反射访问框架 IPackageManager 代理同样执行过滤。
此 ROM 的普通安装列表在关闭策略时已经只返回测试应用自身，不能把该平台现象当作本模块的过滤效果。
pm/cmd 命令控制例正常退出并保留自身条目；其关闭基线也未包含选定包，移除逻辑另由主机字节流回归验证。
原生 10 个入口安装 0 失败，选定路径探测命中计数递增，自身目录访问及非文件 getpid 调用正常。
关闭基线中私有目录探测本已返回 ENOENT，因此不把 errno 本身作为隐藏成功的唯一证据。
原生 mmap 的桥接 JAR 控制行在 Java reader 中被移除；关闭后可读回。ZygoteInit 类始终可正常加载。
最终装机版本核对了 `Handler.java:42` 的行号表示，Java 策略 12 个、包查询 87 个、原生 10 个钩子均为 0 安装失败。
测试结束恢复了原测试 APK、原作用域策略和无障碍设置，启动标识前后相同。

这不保证整个检测器全部通过：其 native Xposed 引擎检测、工作资料/多开检测，以及未选定应用的
行为不属于上述包列表规则。原生 inline SVC、外部独立可执行文件/直接 Binder transact、
APK 离线扫描、目录枚举、其他 maps 读取路径、已缓存结果与厂商私有接口仍可能绕过对应入口。
本轮未重新加载系统进程、未重启设备，API 102 的原生通道仍需相应设备验证。
