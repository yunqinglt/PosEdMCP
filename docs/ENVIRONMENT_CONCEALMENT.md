# 1.1.9：环境与挂载读取视图

2026-11-07。本地普通版和 rootless 版共享本次改动。

在「更多信息 → 特殊设置」开启「扩展 Java 隐藏（实验）」并填写完整包名。规则仍受主开关和 LSPosed 作用域限制，名单为空时不应用。保存后重新打开目标应用。原生挂载查询有独立开关，默认关闭。

| 入口 | 处理范围 |
| --- | --- |
| `System.getenv("PATH")` 和 `System.getenv()` | 只移除明确的 Magisk 私有目录或 su/magisk 文件路径。保留公共目录、相似名称、次序、原有空段和其他变量。环境映射返回只读副本，真实环境及子进程继承保持原样。 |
| `System.getProperty` 两个重载 | 精确名单：`magisk.version`、`magisk.versionCode`、`ro.magisk.version`、`ro.magisk.version.code`、`xposed.version`、`lsposed.version`。按缺失属性返回 null 或调用方默认值；原异常、普通属性和底层 Properties 保留。 |
| proc 挂载 | 处理本进程的 `mounts`、`mountinfo`、`/proc/mounts` 及本进程的 PID/TID 别名。支持已跟踪的 BufferedReader、RandomAccessFile 和平台提供的 NIO 读取。单数 `mount` 若不存在，保留原异常；不将其重定向到别的文件。 |
| Java 子进程 | 在共同的 ProcessBuilder 启动入口识别无参数 `mount` 或单文件 `cat` 挂载查询，包括受支持的 Toybox 及简单 `sh -c` 形式。仅在父进程读取管道 stdout 时过滤；原 Process 对象、stderr、退出状态和命令不变。 |
| 原生 `system("mount")` | 额外开启「原生挂载查询（实验）」才安装 libc 钩子。仅处理字面量 `mount` 和 `/system/bin/mount`。复用 libc 的 system，使用设备上的 awk 对挂载列表做字段过滤；不改写传入的任意命令。没有匹配的 system 调用直接透传。 |

挂载过滤解析实际字段，不因选项中出现 `magisk` 或普通路径包含相似字样而删行。保留其他 PID 和普通同名文件。原生开关沿用扩展规则名单，跳过已知有原生加载冲突的中国移动包；框架原生 API、awk 或 shell 能力不足时不启用过滤或透传。关闭后需重启目标进程，才能确认此前安装的 libc 钩子已不在进程中。

### 新 PID 与作用域

系统启动的 Android 应用进程通常由 [Zygote](https://source.android.com/docs/core/runtime/zygote) 派生。Java 启动 shell 或原生命令时，[execve](https://man7.org/linux/man-pages/man2/execve.2.html) 替换进程映像，父进程的钩子不会随它保留。子进程有新 PID，并不意味着 LSPosed 会加载模块；这里观察的是父进程读到的输出。对实际由 Zygote 启动的应用进程，是否加载仍取决于框架和作用域。

边界：不改写内核挂载命名空间，不覆盖任意 shell 脚本、复合命令、重定向/合并输出、原生 `execve`/`popen`、原生环境读取、`/proc/self/environ`、直接 syscall、Android SystemProperties/getprop 或对全局 Properties 的直接遍历。原生 `system` 过滤依赖系统 mount 的列表格式。过滤后的 stdout 流本身可以与平台流类型区分。没有复测或宣称修复中国移动、运动世界的启动问题。

### 验证

- 主机 260 项断言通过，含路径、属性默认值、普通数据、进程身份及限定命令的对照。普通版、rootless 均通过构建及 lint。
- Android 16 / LSPosed 1.10.2：扩展 Java 安装 19 个入口，0 失败，2 个 readString 重载不可用。测试应用中的 PATH 与属性阳性对照从可见变为不可见，原 Properties 和普通值保持。
- 真机验证了 `Runtime.exec` 的六个重载及简单 shell/Toybox 路径。启用前后进程类型均为 `java.lang.UNIXProcess`，普通命令仍输出原文并返回 23。原生 `system` 钩子报告安装成功，恰好命中两个 mount 调用；普通 system 命令保留原输出和等待状态。
- 测试应用自身的挂载视图原本没有匹配记录，未把空结果算作过滤成功。把生产 awk 命令在 ADB shell 的实际挂载视图中单独跑对照：455 条保留 443 条，删 12 条已知标记，无新增；相似普通路径与挂载选项对照仍保留。
