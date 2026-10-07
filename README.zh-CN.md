# PosEdMCP

[English](README.md) | 中文

Android 上给本机 Agent 用的 MCP 服务器。LSPosed 模块 + root shell，让跑在手机里的
Agent 能真正操作这台设备。

不是"远程控制"：服务器跑在手机上，Agent 也在手机上，网络只走 `127.0.0.1`。

## 它做什么

| 能力 | 走哪条路 | 是否需要用户确认 |
|---|---|---|
| 设备/模块状态、应用列表、事件流 | 本进程 | 否 |
| 当前前台应用、亮灭屏 | system_server 模块 | 否 |
| 执行 shell 命令（uid 0） | `su` | **是，每次，不可关闭** |
| 截图 | system_server 特权 / `screencap` | 是（可关闭 system 路径） |
| 导出控件树 | `uiautomator`（root） | **是，不可关闭** |
| 注入点击/滑动/文本/按键 | system_server 特权 / `input` | 是（可关闭 system 路径） |
| 向第三方应用注入并调用代码 | LSPosed 作用域 + 内存 DEX | 是（可关闭） |
| 应用进程接入设备桥 | 应用内模块主动连接 | **是，每个包一次，不可关闭** |

上表里每一个"是"，都会被一样东西整体盖过——每次 15 分钟，且只有用户亲手开启才生效：
**解放双手模式**，见下。

## 设计上的三个要点

**一、确认弹窗是唯一把关点。** 每次特权操作都会弹出浮层，逐字显示即将执行的命令和
Agent 填写的理由，用户手动批准才执行。`root_shell_exec` 的确认不可关闭——这正是本
项目存在的理由：Agent 不能执行用户没读过的命令。无法弹出（既无悬浮窗权限也无通知
权限）时**拒绝执行**，而不是放行。

只有一处例外，而且它是用户本人的决定，不是一个"设置"：**解放双手模式**把一个固定时长、
会自己到期的窗口整体交出去。它是干什么的、代价是什么、为什么做成这样，见
[下文](#解放双手模式有意把闸门打开)。

**二、不做自动 fallback 链。** 哪些工具走 shell、哪些走模块特权是显式指定的——否则
一个被放宽的设置可能悄悄降级成一条没人看过的 root 命令。

**三、system_server 里不 hook 任何系统方法。** 截图和输入注入走隐藏 API 反射（全部
包在 try/catch 里，失败退回 root 路径），前台应用用 2 秒轮询而非注册
`TaskStackListener`——注册需要伸进 `ActivityTaskManager` 的私有单例和 AIDL 接口，
写错就是 system_server 崩溃、手机无限重启。一个监控模块导致 bootloop 比少一个事件
严重得多。

## 安装

前置：已 root（Magisk / KernelSU）、已装 LSPosed、Android 9+。本项目在 Android 16 /
arm64 上开发验证。

```bash
./tools/bootstrap-gradle.sh     # 下载 Gradle（仅首次）
echo "sdk.dir=E:/SDK" > local.properties   # Android SDK 路径，按需改
./tools/gradle.sh assembleDebug
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

在 LSPosed 管理器里启用 PosEdMCP，作用域勾选 **系统框架**（System Framework）。第三方
应用按需勾选。**勾选后必须重启手机**——系统框架的 hook 在开机时注入。

> 改完 APK 重新安装后，**也要重启**才让 system_server 用上新代码：模块类在进程启动时
> 加载，而 system_server 只在开机时启动一次。

打开 PosEdMCP 应用，按界面上的 STATUS 一栏逐项处理：

1. **悬浮窗权限**——审批弹窗要用。缺失时会退化成通知，再缺失就直接拒绝。
2. **无障碍**——见下节。它同时负责保活和免 root 的截图/手势/控件树。
3. 应用会自动启动服务并常驻通知栏；"ENDPOINT" 一栏是地址和 token。

### 无障碍：必须做的一步

**没有它，这个服务器在最需要它的时候是死的。** 应用一离开屏幕，系统就会冻结它的进程
（实测 ColorOS 在 24 秒内就冻）。被冻结的进程不再 accept 任何连接，MCP 端点完全失联，
而且**在任何日志里都不留痕迹**——排查时极容易误判成崩溃或代码 bug。

而 Agent 通常跑在**另一个应用**里（RikkaHub 之类），这正是我们的应用在后台的时刻。

解决办法是启用无障碍服务：宿主应用会持有系统绑定，因此不会被冻结。ROM 自己的无障碍
设置页就写着这一条（"开启无障碍辅助功能后，应用将获得自启动权限，不受自启动管理页面
设置项的影响"）。

同一个服务还顺带提供了**不需要 root** 的截图、手势注入和控件树读取——在 Android 16 上
这不是锦上添花：`SurfaceControl` 已不再暴露 display token，system_server 那条截图路径
根本不存在了。

实测对照（应用在后台、另一个应用在前台）：

| | 修复前 | 启用无障碍后 |
|---|---|---|
| 冻结线程 | 全部（33/33 处于 `do_freezer_trap`） | 0/33 |
| MCP 端点 | 完全无响应 | 连续 90 秒正常应答 |

> **更新应用时不要用 `am force-stop`。** 它会把应用标记为停止状态，系统因此解除无障碍
> 绑定——你会顺手毁掉保活，然后困惑于它为什么又被冻了。用 `kill <pid>`：系统会因为绑定
> 而自动重启进程并重新绑定。
>
> ```bash
> adb shell su -c "kill $(adb shell pidof dev.posedmcp)"
> ```

电池无限制（应用里的 **Battery settings**）仍然建议做，ColorOS 可能还需要在
「设置 → 电池 → 应用电池管理」里额外允许后台活动，但**它单独不够**。

> **在这台 ROM 上，应用还会被直接杀掉——无障碍绑定也拦不住。** 实测一次会话中途：
> `OplusClearSystemService` 以 `powersavemode(kill-res)` 把我们两个进程一起收了（同一秒
> 还收了通知管理和其他几个），无障碍绑定大约一秒半后把它们拉了回来。绑定让应用**不被冻结**，
> 但不等于杀不掉。表面症状是这两秒里 MCP 端点连接被拒、以及被 hook 应用的桥掉线——看起来
> 像崩溃，其实不是。如果发生得频繁，上面那些电池设置才是值得去动的地方。
>
> **它还可能把服务留在"开着但已经死了"的状态。** 进程一死，无障碍框架就把这次断开记成崩溃、
> 把组件放进 `mCrashedServices`，然后不再绑定它——而设置里的开关仍然显示开着。在那个开关上
> 点"开"没有任何作用，因为那一屏本来就认为它是开的：**必须先关掉再打开**。状态页把这两种
> 情况分开说，并提供一个 **Repair accessibility…** 按钮，用 root 做那次关开。这个区分正是
> 按钮存在的理由："没开"和"开着但没在跑"从应用内部看一模一样，而修法完全相反。

### 确认策略：什么该放宽，什么不该

每个 `input_inject` / `screen_capture` / `ui_dump` 默认都弹窗。做界面自动化时这没法用
——点一下弹一次。所以这三个（以及插件相关）可以在应用里关掉确认。

**`root_shell_exec` 永远弹窗，且不可关闭。** 这是有意的：放宽的只是模块/无障碍特权那条
路，真正的权限边界不会因为一个设置而消失。

### 解放双手模式：有意把闸门打开

上面所有内容都默认用户在场，读得到每一条弹窗。解放双手模式是给"用户有意不在场"准备的
——Agent 在跑一长串操作，没人想点四十次"允许"，或者手机正被隔空驱动。

它开启期间，**每一类**请求都由应用替用户回答，包括那两个任何设置都关不掉的：root shell
和桥的信任决定。弹窗完全不出现。

代价必须说白。那个弹窗不是形式——它是这个设备和 Agent 之间唯一站着的东西。一条删除文件、
停用系统组件或写分区的命令，会**一字不差地照 Agent 写的那样执行**，而一台开不了机的手机，
不是这个应用能还原回来的。

所以它被做成"难开，也难忘"：

- **它不是一个开关。** 开启要走三个弹窗，每个讲的后果都不一样，然后还要在输入框里手敲
  `HANDOFF`，按钮才会从灰变亮。口袋、误触、蹭一下都开不了它——只有读着字的人能。
- **它会到期。** 默认 15 分钟，每次手动续 5 分钟。没有"一直开着"这个选项。
- **重启手机就清掉——但应用被杀掉不会。** 这台 ROM 自己会挑时候杀应用。实测一次会话中途：
  `OplusClearSystemService` 以 `powersavemode(kill-res)` 把我们两个进程都收了，无障碍绑定
  一秒后又拉了回来，而窗口剩下的那几分钟也跟着没了。被一次例行内存回收顺手废掉会话，这
  对谁都没好处。所以期限用的是墙钟时间、能扛过进程重启，同时把**开机时刻**一起记下来——
  那才是把"重启手机"（该结束它的那件事）和"ROM 又发作了"区分开的东西。
- **它会在两个地方说自己开着。** 状态页从开启那一刻起就在倒计时，常驻通知上带着剩余时间。
  没有第三处，而且是有意的：为什么一个字都不告诉 Agent，见下。
- **它会报告正在做什么。** 每一次经由这道敞开闸门的操作都会弹一条横幅，写明是哪个工具、
  跑的是什么——所以一段无人看管的窗口不等于一段没人知道的窗口。开启、关闭、到期各有一条
  自己的横幅，这三条**不限速**。动作横幅则每 12 秒最多允许一条打断你，中间那些仍然会**静默
  更新**通知并在正文里计数（"and 3 more since the last banner"）。Agent 是成串调用的，
  震四十下的手机只会被静音；但一串调用即使整串都落在同一个窗口里，记录也仍然是完整的。
- **而且日志那行照写。** 每一次自动放行同时会写进日志，写明动作和剩余时间。被跳过的是
  "决定"这一步，不是"记录"：

  ```
  W PosEdMCP: HAND-OFF MODE ARMED for 15 min - every tool now runs without asking
  I PosEdMCP: confirmation[SHELL] AUTO-APPROVED by hand-off mode (14 min left): id
  ```

以及它**没有**改变的那件事：**只有用户能开它。** 没有任何工具能碰它，所以 Agent 没法自己
给自己扩权。

**而且这件事有意不告诉模型。** 在 `module_status` 里报一句状态很容易，第一版也是这么做的，
后来拿掉了：对模型说"现在没人看着"，恰恰是那种会诱使它放开手脚的上下文。反过来的那个担忧
——一个把弹窗当成自己脑子的 agent——在它该在的地方解决：提示词里现在写着"假设你发出的每一条
命令都会一字不差地直接执行，没有人先读过"，以及"弹窗不是你的安全网"。所以模型看到的确认
策略里没有任何关于 hand-off 的字，而让这件事成立的那套指导是无条件的、开关都一样成立。

### root 可用性

如果 root 管理器默认对应用隐藏 `su`（Magisk 的 SuList 模式、KernelSU 的类似机制），
`su` 在应用进程里会**直接不存在**（`No such file or directory`）。注意用 `adb shell`
或 `run-as` 验证会得到误导性结论——那两者继承的是 shell 的挂载命名空间，而普通应用
进程不是。`device_info` 的 `root.diagnostics` 会指明具体是哪种情况。

## 接上客户端

服务器监听 `127.0.0.1:8765`，端点 `/mcp`（MCP Streamable HTTP），Bearer token 认证。

同机客户端直接连 `http://127.0.0.1:8765/mcp`。PC 上的客户端先 `adb forward`。
客户端提示词见 [docs/MCP_PROMPT.md](docs/MCP_PROMPT.md)。`/health` 不需要认证，用来探活。

测试时建议绕开 `adb forward`——它在应用进程被替换后可能静默失效：

```bash
POSEDMCP_TOKEN=<token> ./tools/mcp-call.sh '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## 工具

只读：`device_info`、`module_status`、`list_packages`、`foreground_app`、`events_poll`、
`plugin_list`、`apk_info`、`apk_list`、`dex_classes`、`dex_search`、`smali_disassemble`、
`smali_assemble`、`hook_records`、`hook_list`、`script_list`。

需要确认：`root_shell_exec`、`screen_capture`、`ui_dump`、`input_inject`、`plugin_load`、
`plugin_invoke`、`lua_exec`、`hook_method`、`hook_lua`、`hook_clear`、`invoke_method`。

不确认但会改状态的：`launch_app`——把某个应用切到前台，等同于点它的图标。以及
`script_save`——把一个脚本存进「自动化」页，只写本应用自己的存储，不改设备上任何东西。
放在这里说是因为它们都不弹窗，而它们确实会改变你会看到的东西。
`smali_assemble` 写文件同样不弹窗——真正的边界是**执行**，而那一步在 `plugin_load` 和
`lua_exec` 上；自动化页里点 Run 就是执行。

### 自动化页

应用界面分三个 Tab：**Status**（服务状态、端点、确认策略、工具清单）、**Scripts** 和
**Hooks**。工具栏右侧有一个 **About** 按钮：作者、项目地址，以及——真正值钱的那一行——
**当前到底是谁在运行这个模块**，连同它选的钩子 API。这个答案是从模块实例那里经桥问来的，
不是应用自己判断的：本进程连模块自己的类都看不见，它能得出的只是"装了什么"，而那是另一个
问题。在 LSPosed 上读作 `LSPosed 1.10.2 · classic hooks`；在 Vector 上读作
`Vector 2.2, libxposed API 102 · libxposed hooks`。

Scripts 页列出模型替你存下的脚本，每条显示名称、作用、目标应用，以及**上一次运行的结果**；
可以打开看源码、运行、删除。

在那一页点 Run **不再弹确认框**——那一下点击就是你本人的决定，再问一次等于问两遍。
所以能给模型这条能力的前提是：脚本得由你先看过、而且是你自己去点。模型自己跑的路径
（`lua_exec`）仍然每次都弹窗。

**点 Run 会先把目标应用拉到前台。** 这台 ROM 会在应用切到后台后几秒内冻结它，被冻结的
进程不应答桥请求，脚本就只会超时——所以先把目标叫起来、等它的模块连上，再执行。代价是
你会被切到那个应用去，而本应用此时在后台：**这依赖无障碍服务在运行**（没有它本应用也会被
冻结、运行永远完不成）。因此无障碍没开时，Run 会直接说明原因并给一个跳转设置的按钮，
而不是让你看着一个像脚本错误的失败。

界面是 **Material 3**（`Theme.Material3.DayNight.NoActionBar` + Material Components）：
工具栏、TabLayout、脚本卡片、按破坏性分级的按钮（填充／色调／描边）、开关。颜色全部取自
主题，所以在 Android 12+ 上跟随壁纸取色，别处用 Material 默认色——代码里没有一处写死颜色。

> Material3 主题本身继承 AppCompat 主题，所以 `MainActivity` 是 `AppCompatActivity`，
> AppCompat 和 Material 也就进了模块的 dex：**APK 从 14 MB 涨到 26 MB**（debug、未混淆）。
> 类是按需加载的，但模块 APK 会被注入每一个作用域进程，这个足迹值得知道。
> 真要在意，可以给 release 打开 R8——目前两种构建都没开。

## 反编译与运行时观察

给在设备上做逆向的 Agent 用。产出 smali 汇编和应用元信息，不做 Java 源码；改动通过
**运行时注入**完成，不改 APK、不重签名。

```
apk_info / apk_list     应用是什么：清单、组件、权限、签名、包内文件
   ↓
dex_classes / dex_search 里面有什么：类、方法、字符串
   ↓
smali_disassemble       具体怎么写的
   ↓
hook_method             它运行时到底发生了什么（零 DEX，模块直接装钩子）
   ↓
   ├─ 改动能用「值」表达（固定返回 / 换参数 / 改字段）→ 还是 hook_method。
   │  它是数据不是代码：不用编译，不碰 DEX，记录里标 altered 证明生效过。
   ├─ 改动是「逻辑」（循环、分支、拼字符串、连着调好几个 API）→ lua_exec。
   │  同样不用编译：解释器随模块一起进了目标进程。
   └─ 既不是「值」也不是「逻辑」的结构性改动 → smali_assemble → plugin_load
```

- 反汇编/汇编用 **baksmali/smali**，纯 Java，直接跑在 ART 上（apktool 不行，它的资源
  解码要调用宿主机原生的 aapt2）
- 清单解析用系统自己的 `PackageManager`，比任何重实现都准
- 引擎跑在 `android:process=":dex"` 的独立进程里：大 APK 反编译吃内存，OOM 时只死这个
  进程，MCP 端点和你正在看的确认弹窗不受影响
- 大输出一律落盘、返回路径与统计数字；只有单个小类才内联
- **hook 是每进程状态**，所以 `hook_method` / `hook_clear` / `plugin_load` 会作用于该包
  的**所有**进程，`hook_records` 合并各进程结果并标注来源。一个应用常有多个进程，
  只问其中一个会得到"没有 hook"这种误导性答案

## 注入逻辑：为什么是 Lua

`invoke_method` 能表达「一次调用」，`hook_method` 能表达「一次改动」，但有很多事这两者都
表达不了：先列目录、再按结果决定下一步、把几个返回值拼起来、循环遍历一批对象。这些是
**逻辑**，而在此之前唯一的出路是手写 smali。

那条路对模型太陡，而且失败是**静默**的：一个空值守卫的分支极性写反，就什么都不列、
什么都不追加、也不抛异常——看起来和「数据本来就不存在」一模一样。实测中，一个注入进
GitHub 应用的探针正是这样得出「这个应用没登录」的结论，而它其实登录着。

所以加了一个 Lua 解释器（LuaJ，纯 Java）：

- **不需要编译。** 解释器随本模块一起被 LSPosed 注入目标进程，脚本没有组装、传输、加载
  这几步——它只是文本。
- **确认弹窗显示脚本本身。** 比 smali 可读得多：用户看到的就是要跑的东西。
- **能力面没有扩大。** 脚本能碰到的东西和现有工具是同一套：应用的 Context、类加载器、
  它的方法（含私有）、字段、文件。它是已有权限的新语法，不是新权限。
- **没有 `io` 和 `os`。** 文件只能经 `app.files` / `app.read` 读，而且只读。
- **不会挂死应用。** 脚本跑过指令预算即被中断，且这个护栏脚本自己关不掉。
- **`app.files` 读不到目录时抛错，而不是返回空表。** 空表只意味着一件事：目录确实是空的。
  这一条是专门针对上面那类静默失败定的。
- **`app.db` 只读。** 这是给「看不懂的应用看它存了什么」用的——混淆过的类名帮不上忙，数据库
  结构能。只读不是限制而是重点：一个不能写的句柄破坏不了应用还开着的库；要写就该走应用
  自己的 API（ContentResolver 之类），它才会顺带更新缓存、观察者和通知，裸 UPDATE 不会。
  读数据库和读它的文件是同一级别的权限，而 `app.read` 早就有。

脚本拿到全局表 `app`：`name()`、`uid()`、`context()`、`class()`、`new()`、`call()`、
`get()`、`set()`、`methods()`、`files()`、`exists()`、`read()`、`db()`、`log()`。`app.call` 走
`getDeclaredMethod` + `setAccessible`，沿继承链找方法，重载按**实参类型契合度**打分选择
（`ContentValues` 有九个两参 `put`，`put("title","Dentist")` 仍能选中 `put(String,String)`）；
真的打平时**拒绝并列出候选**而不是猜，此时可以按 `app.methods` 打印的写法指定：
`app.call(values, "put(String,Integer)", "key", 5)`。

smali 那条路保留：`invoke_method` 和 Lua 都表达不了的**结构性**改动仍然得走它。

## 持久化钩子：为什么，以及代价

运行时钩子只活在**被装进去的那个进程**里。在这台设备上这不是小问题：ColorOS 几秒就冻结
后台应用、随时回收，用户一操作，应用可能已经是新起的进程——钩子没了。于是「找到方法 →
挂钩子 → 让用户去操作 → 回来读记录」这条链，在下一次应用启动时价值归零。这不是不方便，
是整条链路不可靠。

所以 `hook_method` / `hook_lua` 装的不只是钩子，还**把它记下来**：

```
hook_method / hook_lua
   ├─ 立刻装进该应用的每个活着的进程（照旧，看得出成没成）
   └─ 存进应用自己的钩子库
          ↓
   某个进程启动、模块连上桥
          ↓
   应用把这个包**启用中**的钩子推回给它（hook_method / lua_exec）
          ↓
   hook_list 显示「上次生效时间」，Hooks 页显示它开着
```

定义存在应用自己的 SharedPreferences 里（模块读不到——这台设备跨 uid 读 prefs 全封死），
**由应用在 peer 连上时推回去**。推送必须另起线程：接受连接的那个线程随即要变成该 peer 的
读循环，从它里面发请求会等一个没人读的回包，直接死锁。

**代价必须说清楚：持久钩子只弹一次确认框。** 之后它在该应用每次启动时自动生效，再不问
任何人。这动的正是本项目的核心——确认弹窗是唯一把关点。补上它的位置是 **Hooks 页**：
按应用分组，点开列出每条钩子（层级、目标方法、文本描述、上次生效时间/失败原因），
有开关和删除。所以这个页面**必须准**，而且**关掉或删除要立刻伸进活着的进程里卸掉**，
不能只是把记录划掉——否则用户以为停了，实际上它下次启动还会回来。
`hook_clear` 同理：它连保存的定义一起删。

**而且它在目标进程完全起不来时也能用**——这恰恰是它最被需要的情形。钩子把应用搞崩之后，
应用每次启动都会把钩子重新装回去，于是继续崩；**能救回来的只有"删掉保存的定义"这一步，
而它不需要任何活着的进程**。第一版 `hook_clear` 和别的钩子工具一样先要求有一个够得到的
进程，结果这个唯一能打破死循环的操作，偏偏在需要它的场景里拒绝工作——有人撞上了，只能在
应用里手动清掉。现在那个检查没了，改为如实汇报：够到了哪些进程（通常是零个）、以及哪些
定义已经被删掉。

它现在还收 **`id`**（`hook_list` 逐条给出），所以能单独删掉一条：目标字符串可读，但它的
**子串**不可读——一个类上挂着十一条去广告钩子时，「删我说的那一条」需要一个能精确指认的
东西。对不上号的 id 会被直接拒绝，而不是悄悄落到过滤器上，所以过期的 id 不会变成
「这个包里的全部」。

**Lua 也能挂钩子。** `app.hook{...}` 交给模块一个 Lua 函数，由 Java 侧持有——脚本跑完
环境就没了，这个引用是唯一让闭包活下来的东西——之后每次命中都回调进去。这是 Lua 唯一
能「跑完之后继续起作用」的形式，也正好补上 `lua_exec` 做不到的那一格。持久化就是把脚本
存下来、进程启动时重跑一遍自我装填，所以**脚本里除了注册钩子什么都别做**。

钩子体出错不会传给应用（应用自己的调用正被它打断），但**会被记下来**，`hook_records`
里报 `bodyError`——一个每次都抛异常的钩子，不报的话和「什么都没匹配上」长得一模一样，
那正是这个模块被重做一遍的原因。

## 在 `system_server` 里挂钩子，以及把你捞出来的那个模块

这个模块有意不在 `system_server` 里 hook 任何东西。截图、注入输入、前台跟踪全都走公开广播和
对 `IActivityTaskManager` 的反射调用，且包在 `try/catch` 里——正是为了平台一变，坏掉的是一个
功能，而不是整个进程：监控模块把手机搞成无限重启，比少一个事件严重得多。

这条一直守到某个时刻：Agent 发现它**根本碰不到** `system_server`，而它收到的报错把事情
变得更糟。挡路的是三处**互相独立**的断点，任何一处都足够：

1. **寻址断的。** 来自 `system_server` 的 peer 注册成角色 `system`，键就是字面量 `system`；
   而所有 hook 工具都通过 `app:<pkg>:<pid>` 前缀解析目标，于是 `hook_method` 在
   `requirePeer` 就拒绝了——而它拒绝时说的是「把包加进模块作用域」，**这是在让人去做一件
   已经做完的事**。
2. **对面根本没有能应答的东西。** `SystemHooks` 只注册了六个 op——`status`、`screenshot`、
   `input`、`foreground`、`ping`、`probe_display`——没有任何 hook 相关的。
3. **没有东西会把钩子装回去。** `BridgeServer` 只对角色 `app` 触发 peer-ready 回调，所以
   维持其他所有钩子存活的那条回灌链路，对它从来没跑过。

修法对外只有一个词宽，对内没有重写任何东西。钩子需要一个包名才能存下来，所以 `system_server`
用 **`android`** 称呼——经典 Xposed 就是这么叫它的——而所有解析目标的地方现在都经过同一个
helper，把这个名字映射到 `system` peer。`HookRegistry`、`LuaRuntime`、`MethodInvoker`
一行没改：它们只要一个 `ClassLoader`，所以把这几个 handler 注册到系统那条桥上就是全部。

### 怎么把 system_server 和一个「只是加载了它的类」的进程分开

`Framework.isSystemServer` 原先靠框架递来的包名判断，进程名作第二条，`/proc/self/cmdline` 只
当最后兜底。第一条回答的不是它看起来在回答的问题：**回调里的包名是框架当时正在加载的那个包**，
而在 `com.android.systemui` 的进程里，它可能是 `android`。

在 Vector 上实测：systemui 因此认定自己是 system_server，装了第二份 `SystemHooks`。两份都以
`system` 这个 peer 身份连接——而 peer 的键就是它的角色，所以 `BridgeServer` 在每次新连接到来时
把旧的那个关掉。于是两者轮流连、轮流被关，**每秒一次，持续了十几个小时**。表现出来的症状全是
间接的：系统那条桥从来没有稳定在线过，每一次往 system peer 推送都撞在一个正在被拆掉的 socket
上，而系统 op 可能是 systemui 在用自己那份权限应答。这些在应用里一点都看不出来，所以查它靠的
是日志。

进程自己的名字是这里**唯一不可能指错进程**的信号，所以只要能读到就由它说了算；框架给的提示只
留给「连自己的名字都读不到」的进程。

**这里的代价不一样，提示词里也如实写了。** 留在 `system_server` 里的持久钩子会在用户够得着
删除它的页面**之前**被装回去——坏钩子就是这样变成开不了机的手机的。所以针对这个目标的确认
弹窗比项目里任何一个都长：它说清 `system_server` 是什么、这个钩子是不是持久的、以及下面那个
救砖模块装没装。当救砖模块处于悬停状态时，`persist=false` 的钩子仍然允许——它活不过进程，把
它一并拒掉只会拿掉最后一种查看「到底哪里坏了」的手段。

### `posedmcp-guard`

出路不能住在应用里，因为**可能永远跑不起来的就是这个应用**。它是一个 Magisk 模块，在
[`magisk/posedmcp-guard`](magisk/posedmcp-guard)，用
[`tools/install-guard.sh`](tools/install-guard.sh) 安装——**没有做成 zip**，因为 zip 是给
安装器用的安装器，而这里一切通往设备的方式都是 `adb`。

```
post-fs-data.sh   在 zygote 起来之前，数那些没能走完的开机
service.sh        数 system_server 重启次数——这是看见用户态重启的唯一方式，因为
                  软重启不会重跑 post-fs-data——并在系统稳定两分钟后清零计数
```

它分两级动手，**顺序本身就是设计**：

1. **悬停，不破坏任何东西。** 只要启用的钩子指向 `system_server`，应用就会在自己的外部媒体
   目录里写一张纸条。救砖模块读到纸条才动手——这就是它区分「这台机器是因为我们才重启」和
   「这台机器在重启」的方式；没有纸条，它清零计数、把问题留给该负责的人。有纸条时它写一个
   kill-switch（一个 `persist.` 属性，外加同样事实的一个文件），应用在**装载任何持久系统钩子
   之前、以及在给任何人弹窗之前**检查它。钩子库一格不动，原因显示在 Hooks 页上，一个按钮就能
   解除。
2. **只有在悬停没起作用之后**，它才把钩子库复制到 `/data/adb/posedmcp-guard/backup/` 并把
   它挪开。

里面有两个决定最初都是错的，值得记下来：

- **读不到 `pidof` 不是信息。** 第一版拿读到的 pid 和上一次比，于是**一次普通重启**——pid
  消失又回来——被记成两次重启，进而悬停了一台什么都没做错的设备。现在空读数直接忽略，只有
  「读到了、而且变了」才算。
- **用户解除悬停同样是信息。** 用户在 Hooks 页清掉 kill-switch，意味着上一次悬停并没有被留着
  继续观察，所以救砖模块要把自己退回第一级，而不是升级到那个会挪文件的级别。

脚本出厂前会检查 CR。带 CRLF 行尾的 Magisk 脚本会以 `#!/system/bin/sh<CR>: not found`
失败，而且是在开机早期——那个阶段没有任何东西能告诉你为什么。

## 注入到 native 层

`app.native` 把代码往下一层推进：目标进程里的原生代码。模块本身就是从目标应用进程里运行的，
所以它加载的 .so 就活在那个进程的地址空间里，够得到 Java 够不到的东西——应用自己的 .so、
libc、以及任何符号名能解析到的地方。

```
app.native.status()           用了哪条加载路径，或为什么不可用
app.native.probe()            自检
app.native.open(path)         dlopen，返回一个小整数 id；失败返回 nil
app.native.symbol(id, name)   dlsym，返回地址（"0x…"）；失败返回 nil
app.native.call(addr, ...)    调用函数指针（最多六个参数）
app.native.read(addr, len)    读内存，返回字节表
app.native.write(addr, bytes) 写内存
app.native.string(addr[, max]) 读 C 字符串
app.native.error()            上一次 dlopen/dlsym 的错误
```

**几个必须知道的设计取舍：**

- **地址是十六进制字符串，不是数字。** Lua 的数字在这里是 double，指针只有落在 53 位以内
  才能原样往返——实测有一个没有，回来差了四个字节，下一次 `dlsym` 就把目标进程打挂了。
  字符串是精确的，而且打印出来就能读。要用数值比大小就用 `app.native.number("0x…")`，
  它只在能精确表示时返回值，否则给 nil——而不是悄悄四舍五入。
- **dlopen 句柄不离开 native 层。** 这台设备上句柄不总是地址：非默认命名空间里的库拿到的是
  linker 内部表的合成值。把它发到 Java、Lua 再传回来会让 `dlsym` 在 linker 自己的命名空间
  查找里崩掉，所以调用方拿到的是一个小 id，真实句柄留在 C 侧的表里。
- **参数和返回值是机器字**，所以这里只调整数/指针函数：浮点、double、结构体按值传递都无法
  表达。这是只传字长的桥的固有边界，不是以后能补上的。
- **读写坏地址会直接带走目标进程。** 这就是伸手进别人内存的本质，不做兜底。脚本要读之前
  先想清楚地址从哪来。

**.so 是怎么进去的：** 模块 APK 里带着 `lib/arm64-v8a/libposednative.so`，在目标进程里用
`<apk>!/lib/<abi>/lib.so` 这个形式 `System.load`——Android 的 linker 认这种写法，而 APK 所在
的文件上下文是应用可以执行的，所以不需要往任何地方写文件。**实测确认可行**（nativeloader
日志：`Load …base.apk!/lib/arm64-v8a/libposednative.so using isolated ns … : ok`）。

> 模块无法自己找到这个路径：它的类加载器给不出 code source，PackageManager 又看不到它不属于
> 的应用。**由本应用通过桥把路径告诉它。**

## 架构

```
┌──────────────── 应用进程 (dev.posedmcp) ────────────────┐
│  McpService (前台服务)                                  │
│    ├── HttpTransport  127.0.0.1:8765  /mcp              │
│    ├── McpServer      JSON-RPC, 工具分发                │
│    ├── ToolRegistry   28 个工具 + 确认策略               │
│    ├── ConfirmationGate ──> ConfirmOverlay (应用浮层)   │
│    ├── BridgeServer   127.0.0.1:8766  (进程间桥)         │
│    ├── RootShell      su, 管道 stdio（非 pty）           │
│    └── EventStore     环形事件缓冲 + seq 游标            │
└──────────────────────────────────────────────────────────┘
             ▲ TCP + token / 首次连接由用户批准
             │
┌────────────┴───────────┐   ┌────────────────────────────┐
│ system_server (role=   │   │ 被作用域覆盖的应用          │
│   system)              │   │  AppHost                   │
│  SystemHooks           │   │   ├ 内存 DEX 加载          │
│   ├ 截图 / 输入注入     │   │   └ 插件调用               │
│   ├ 前台应用轮询        │   │                            │
│   └ 亮灭屏广播          │   │                            │
└────────────────────────┘   └────────────────────────────┘
```

插件以字节流经桥接送进目标进程，用 `InMemoryDexClassLoader` 加载、**不落盘**——否则
每次注入都要先经 root 命令推文件，等于每次多一次弹窗。

多个进程的应用（闹钟有主进程和 `:clockWidget`）以 `包名:pid` 分别登记；插件装在哪个
进程，调用就路由到哪个。

## 两个后端，以及为什么由框架来选

注入本模块的是 Xposed 框架，而现在流通的有两代：2012 年的经典
`de.robv.android.xposed`，和 `io.github.libxposed.api`——Vector（同一位作者对
LSPosed 的重写）就建立在后者之上。所以模块发布了**两个入口**，并且不在它们之间做选择：

| | 由谁声明 | 入口类 |
|---|---|---|
| 经典 | `assets/xposed_init` | `PosEdMcpModule implements IXposedHookLoadPackage` |
| 现代 | `META-INF/xposed/java_init.list` + `module.prop` | `VectorModule extends XposedModule` |

框架调用哪个，后端就是哪个——而这也不是能用别的方式表达出来的偏好：经典入口拿到的是
`XposedBridge`，现代入口拿到的是 `XposedInterface`，两者互相够不到。你会得到哪一个，是
**调用你的那一方决定的**。两个入口随后做的事完全一样——把同一个类加载器交给同一个
`SystemHooks` 和 `AppHost`，所以这个模块只有一份实现、两种被启动的方式。

**是什么逼出了这件事。** Vector 仍然能加载经典模块，但走的是一个兼容桥（它日志里的
`VectorLegacyBridge`），而那个桥比真货薄。在 Vector 2.2 上实测：
`AndroidAppHelper.currentApplication()` 返回 null，于是 `lua_exec` 里 `app.context()`
永远是 nil，任何需要 Context 的脚本——ContentResolver、PackageManager——根本写不了。
框架自己的发行说明就把这个兼容桥称为脆弱的那一半：它记录过一个版本"模块加载了，然后什么
都没发生"，原因是 R8 把 `XposedHelpers.findClass` 路上会碰到的类合并掉了。跑在各自的原生
API 上，好过让其中一个去模拟另一个。

**不同的部分很小**，因为钩子早就已经在接口后面了：

- `Framework` 是那道缝。两个入口都在那里登记自己；`HookRegistry` 和 `app.hook` 只管向它
  要一个 `HookApi`，不关心拿到的是哪个。
- `LibXposedHookApi` 是第二份实现。真正不同的只有两点：现代 API **没有 `setResult`**
  ——拦截器要么调 `chain.proceed()`，要么不调而直接返回一个值，这正是 `setResult` 的含义；
  参数是随 `proceed(args)` 走的，不在一个共享数组里。这两点在三十行里就对齐了。
- `AppHost.currentApplication()` 先问平台（`ActivityThread`），再问框架。平台那条路在前
  后两代上都成立；框架那条路才是会变的那个。

### 一个 API 100 的框架拿到现代入口之后会怎样

上面那个干净的故事不是实际发生的过程，而这段弯路值得留着——它没有一处能猜出来。
LSPosed 1.10.2 是一个 **API 100** 框架，它**同样会读** `META-INF/xposed/java_init.list`，
然后关键的是：**那个入口加载失败时它不会退回 `assets/xposed_init`**。所以喂给它一个装不上的
入口，得到的不是一个降级的模块，而是**没有模块**：

| 声明 | LSPosed 1.10.2 的反应 |
|---|---|
| `minApiVersion=101` | 直接拒绝：*"此模块需要更新的 Xposed 版本（101），因此无法激活"* |
| `minApiVersion=100` | 接受，找到入口，然后按 **101 以前的约定**构造它——把 `(XposedInterface, ModuleLoadedParam)` 当构造参数——再撞 `NoSuchMethodException` |

所以这个入口必须**两种构造方式都支持**。`VectorModule` 有一个无参构造给 101+（框架自己
调 `attachFramework()` 再回调 `onModuleLoaded()`），还有一个双参构造给 100（自己 attach、
自己认领）。而"被以第二种方式构造"本身就是信息：那个框架早于 101，它成熟的 API 是经典那套
——所以这条路径**有意让 `Framework` 保持经典后端**，现代钩子后端只服务于那些经典支持恰好
是薄弱环节的框架。

**还有一个调用必须改成反射。** API 102 把 `attachFramework` 改成要一个 `Runnable`（热重载
用）；101 及以前只收框架本身。**没有哪个 jar 能同时满足两边**——按 101 编译会把
`NoSuchMethodError` 挪到 Vector 上，按 102 编译则留在 LSPosed 上。于是这一个方法改成
运行时查找并调用存在的那个 arity，其余仍然是普通代码。

两台机器上的结果：Vector 按 101 的方式构造入口、钩子走 `XposedInterface`；LSPosed 按 100 的
方式构造、钩子走 `XposedBridge`。同一个 APK、同一次构建、没有任何配置。

**唯一没有改变的是确认闸门。** 后端决定的是"钩子怎么装上去"，不是"要不要问你"。

> **重装模块之后，必须让框架重新读一次 APK。** daemon 缓存着模块的路径和描述符，而安装会把
> APK 挪到新的 `~~hash` 目录，缓存路径于是失效——Vector 会打
> `XSharedPreferences: Apk parser fails: NoSuchFileException`，然后继续用旧入口。
> 在 LSPosed 上的答案是重启手机；Vector 上有个更轻的办法：
> `/data/adb/modules/zygisk_vector/cli modules disable dev.posedmcp` 再
> `... enable dev.posedmcp`，daemon 就会重读，之后新起的进程拿到的就是新代码。

## 凭据是怎么送到模块手里的

这是本项目里最绕的一段，因为 **Android 把带外通道全堵死了**：

| 通道 | 结果 |
|---|---|
| 抽象 Unix socket | SELinux 拒绝 `connectto`（`untrusted_app` → `untrusted_app`，安全类别不同）——平台设计边界，不是配置问题 |
| ContentProvider | 包可见性：`Unknown authority`。宿主应用的 manifest 不是我们能改的 |
| 显式 `bindService` | 同样被包可见性挡住，`bindService` 直接返回 false（系统应用和 uid 1000 不受影响，所以 systemui 反而连得上） |
| 直接读文件 | Android 16 把 prefs 移到 `/data/misc/<uuid>/prefs/`，跨 uid 进不去；即使 `chcon` 去掉类别，`untrusted_app` 读 `app_data_file` 仍受限 |
| `XSharedPreferences` | 框架自己的机制，依赖守护进程在开机时放权，实测未生效 |

于是改成**在应用已经建立的那条连接上发放凭据**：应用连上桥但不带 token 时，弹窗问
用户"某个包要接入"，批准后把 token 交给他并记住。每个包问一次；被拒绝的包在 10 分钟内
不再重复打扰。

这不是密码学意义上的强身份——批准的是"声称自己是这个包的那条连接"。它换来的是**完整性**
（防止别的进程伪造事件、抢答伪造截图），而不是机密性；真正的权限边界始终是那个确认弹窗。
对作用域内的应用，token 本来也藏不住：模块就跑在人家进程里。

保留的其它通道作为优化路径：外部媒体目录 `Android/media/<pkg>/`、Binder 服务、
ContentProvider——能通就用，省掉一次弹窗。

## 开发

```bash
./tools/gradle.sh assembleDebug
./tools/build-plugin.sh          # 示例插件 → tools/plugin-demo/build/plugin.b64
```

`tools/gradle.sh` 把 `GRADLE_USER_HOME` 重定向到仓库内的 `.gradle-home/`：Windows 用户
目录含非 ASCII 字符时部分工具链会出问题。代理设置放在 `.gradle-home/gradle.properties`，
不进版本库。

需要 JDK 17+（本项目用 JDK 22 验证）。

## 已知限制

- **system 路径的截图在本机不可用**。Android 16 移除了 `SurfaceControl.getPhysicalDisplayToken`
  和 `getPhysicalDisplayIds`——运行时枚举确认这两个方法在该设备的 framework 里根本不存在，
  不是反射写法问题。`ScreenCapture.captureDisplay` 需要一个 display token，而没有公开的
  途径拿到它。root 的 `screencap` 路径覆盖了这个能力：`screen_capture` 的 `mode=auto`
  会先问 system_server 上一次的失败原因，跳过这条死路，直接走 root（一次确认）。
  其余 system 能力（前台追踪、事件、输入注入）均正常。
- 没有单元测试。所有验证都是在真机上按行为做的。
- **自动化页的 Run 要求无障碍服务在运行**，理由见上：它会把你切到目标应用，本应用于是
  在后台，而没有无障碍绑定就会被系统冻结、运行永远完不成。无障碍关闭时页面会直接说明，
  不会把它伪装成脚本失败。
- **`app.native` 读/写坏地址会连带杀死目标应用。** 这是直接操作别人进程内存的固有代价，
  没有兜底；脚本拿到的地址从哪来，决定了它有多危险。
- **`lua_exec` 的指令预算只约束 Lua 本身。** 脚本如果把时间花在慢的 Java 调用上（网络、
  文件），预算不会触发，只能靠桥的请求超时兜底——而超时后脚本所在线程仍会把当前调用跑完，
  这一点和 `plugin_invoke` 一样。
- **持久钩子可能漏掉应用启动最早期的那几次调用。** 重新装填要经过一次桥往返（进程起 →
  模块连上 → 应用把定义推回来 → 装上），所以 `Application.onCreate` 这类最前面的调用
  多半已经过去了。要抓那些，只能在 LSPosed 作用域那一层做，不在本工具的范围内。
- **native 层的钩子没有实现。** Hooks 页上的 `layer` 字段目前只会是 `dex`——留这个字段是
  因为它下一步就是 native，而不是因为它现在有两种取值。arm64 内联 hook（改写函数序言 +
  trampoline）风险很高（写坏序言或漏刷指令缓存会直接带走目标进程），要做应当单独一轮。

### 调试隐藏 API 时的两个坑

- **`Class.getDeclaredMethods()` 会被隐藏 API 过滤**：返回的列表里只有公开成员，看起来像
  "这个方法不存在"。必须先在进程内装好豁免（`VMRuntime.setHiddenApiExemptions`，
  见 `HiddenApi.java`），否则整条反射链会静默地什么都找不到。
- 设备上的 `/system/framework/framework.jar` 是**桩**，里面的 dex 没有真实实现，不能用来
  查方法签名。用 `device_info` 的 `displayProbe` 在运行时枚举才准。

### 还有一个关于 `Settings.Secure` 的

`Settings.Secure.getString` 会在**调用方进程内**留一份 name/value 缓存，而别处写入的值
**不一定会让它失效**。实测：一个用这种方式读"已启用的无障碍服务"的应用，把本服务从设置里
摘掉时立刻就知道了，然后再把设置写回去它**一直不知道**——也就是说，用户刚修好无障碍，应用
会永远告诉他还是关着的。改成直接查 provider：写入是它处理的，问它才是反映实情的读法。

## 状态

已在 OnePlus PLR110 / Android 16 / arm64-v8a / Magisk v27.2-kitsune-4 /
Zygisk-LSPosed 1.10.2 (7182) 上验证：

- 模块被 LSPosed 正确加载；system_server 走 `SystemHooks` 分支并连上桥（`role=system`）
- MCP 握手、`tools/list`、鉴权（含 401 拒绝路径）
- root shell 执行与逐条确认弹窗；中文理由渲染逐字正确
- `ui_dump` 端到端跑通
- system 路径的**前台应用查询**与**输入注入**（`InputManagerGlobal.injectInputEvent`，
  不经 shell），以及 `foreground.changed` / `screen.on|off` 事件流
- `screen_capture` 的 `mode=auto` 在 system 路径不可用时正确回退到 root
- **向 `com.coloros.alarmclock` 注入插件并 hook 到 `Activity.onResume`**，
  按行为验证（返回了该应用真实的 Activity 生命周期）
- 多进程应用的 peer 登记与路由
- **静态分析链路**：`apk_info` / `dex_classes` / `dex_search` 字段与 `dumpsys package`
  对得上；`smali_disassemble` → `smali_assemble` 往返后方法签名与原始一致
- **运行时观察链路**：在时钟进程里 hook `Activity.onResume`，切前后台后
  `hook_records` 读到 3 条真实调用（线程与时间戳均正确）
- **端到端注入**：反汇编应用的闹钟解析函数拿到正式的 Bundle 契约，注入插件调用
  应用自己的 `add_alarm` 接口，在时钟应用里创建出一个 **06:07 / 标签 "PosEdMCP" /
  已启用** 的闹钟，并用 `delete_alarm` 清理了过程中的临时闹钟
- **保活**：启用无障碍后，应用在后台、另一个应用在前台时，实测 90 秒内**零冻结线程**
  且 MCP 端点持续应答（修复前是 33/33 线程处于 `do_freezer_trap`、端点完全失联）
- **无需 root 的界面操作**：`launch_app` 成功把 GitHub 应用切到前台（前台窗口为
  `com.github.android/.main.MainActivity`）
- **`lua_exec` 在 `com.github.android` 进程内跑通**：脚本列出该应用数据目录的 8 个条目与
  `shared_prefs` 下的 10 个文件，并读到它自己 `AccountManager` 里的 `yunqinglt /
  com.github.android` —— 同一个检查用手写 smali 探针跑时返回了空串，并在阳性对照下暴露
  出那是探针 bug 而不是设备状态
- **`app.db`**：只读打开 `com.github.android` 正在使用的 WAL 库，列出 15 张表、读回
  `recent_searches` 的真实行（`sunflower233`、`mlinux-project`、`micode`）；库不存在和表
  不存在都给出具体错误
- **自动化页**：`script_save` 存下的脚本出现在 Scripts 页，显示名称、目标应用、作用与
  上次运行结果；点 Run 会真的经桥执行并把结果写回卡片（实测中目标进程在后台被冻结，
  卡片如实显示 `FAILED … timed out`）
- **持久化钩子（端到端，零人工确认这一步除外）**：把一条钩子定义放进钩子库后，**目标
  应用是新起的进程**——模块一连上桥，钩子就自动装了进去，`hook_records` 读出 3 条真实
  `Activity.onResume` 调用（线程 `main`、时间戳正确）；同一应用后来起的第二个进程
  （`:clockWidget`）同样被自动装上。全程没有弹过任何确认框，这正是持久化要证明的事
- **关闭即生效**：在 Hooks 页把开关关掉，活着的进程里钩子当场被摘（`hook_records` 变成
  "nothing is hooked"），并且**该应用重启后不会自己回来**；重新打开开关，立刻装回运行中
  的进程
- **Lua 钩子**：`app.hook{...}` 注册成功并持久化；回调在真实调用上触发，闭包里的局部变量
  跨调用累加（日志里 `#3`），`ctx.this` 解析到真实实例、`ctx.args` 为 0 长度——都符合预期
- **Hooks 页**：按应用分组、默认折叠、点开二级目录，列出 `DEX · RULE`/`DEX · LUA`、
  目标方法、文本描述、`last armed <时间>`，带开关与删除按钮
- **真实使用**：用户自己在 `com.coolapk.market` 上注册了 **12 条 Lua 钩子**去广告——
  把信息流广告卡片收成 0 高度、把帖子里内嵌的赞助卡片摘掉、开屏页和插屏页创建时立刻关闭
  ——走的是 `hook_lua`，之后在酷安每个进程里自动重装
- **解放双手模式，端到端**：三个警告弹窗与手敲确认（词不对按钮保持灰、词对了才亮）；
  开启；状态页倒计时在走；**`root_shell_exec`——那个平时永远弹窗的工具——103 毫秒返回
  `uid=0`，全程无弹窗**；`module_status` 把 `HAND_OFF_MODE` 连剩余时间一起报给 Agent；
  上面那两条审计日志；关掉后闸门恢复；服务重启把它清掉
- **横幅**：开启时弹 `Hand-off mode is ON`；连发四条 root 命令只弹出一条，正文是
  `echo burst-4` / `(and 2 more since the last banner)`——合并生效，四次调用一条不漏；
  关闭时弹 `Hand-off mode is off (turned off in the app)`；杀掉进程后弹
  `Hand-off mode is off (the service restarted)`，顺带证明在服务的 `onCreate` 里发通知
  不会干扰前台服务的启动。以及反过来的那一半：hand-off **关闭**、截图确认被放宽的情况下，
  `screen_capture` 无弹窗跑通且**没有**发任何横幅——报告的范围是 hand-off，不是
  "一切没弹窗的事"
- **窗口能扛过应用被杀，但扛不过重启手机。** 开启之后杀掉进程——正是这台 ROM 对它做过的事
  ——重启后倒计时回到原处（`ON — 14:21 left`）。和期限一起写下的**开机时刻**，才是把"该结束
  它的重启手机"分到另一类去的东西。
- **无障碍的几种状态被正确区分了。** ROM 的清理进程杀掉进程之后，服务停在框架的 crashed
  集合里：开着、没在跑、也拒绝重新绑定——就是用户遇到并报告为"此服务出现故障"的那个状态。
  把该组件从 `enabled_accessibility_services` 里摘掉再放回就清掉了（实测：crashed 集合从
  含该组件变成空，服务重新绑定）。状态页现在会说明处在哪种状态，并把那次关开做成按钮。
- **第二个后端，在 Vector 2.2 上**：框架改由 `VectorModuleManager` 加载 `VectorModule`，
  不再把经典入口塞进 `VectorLegacyBridge`；`app.context()` 拿到了时钟真实的
  `DeskClockApp`（此前一直是 nil）；`LibXposedHookApi` 装的钩子在 `main` 线程上抓到了真实的
  `Activity.onResume` 调用；搬过来的时钟探针也完整跑通——它的 prefs 文件，以及通过它自己的
  provider 读回的三条闹钟。
- **旧设备也好了，而且走的是它没料到的那个入口。** LSPosed 1.10.2 取的是**现代入口**
  （`java_init.list` 赢，而且没有回退），按 101 以前的约定构造它，再用**经典钩子后端**运行。
  重启一个作用域应用，它重新被注入；在那个应用里跑 `lua_exec`，拿到的 Context 是真实的
  `AlarmClockApplication`。两台机器，一个 APK。
- **`system_server` 里的持久钩子。** 在小米那台（Vector 2.2 / Android 15）上：`hook_list`
  查 `android` 会报系统 peer 在线；`hook_method` 返回 `process: system, hooked: true`；
  `hook_records` 随后读到在里面真实拦下的调用，来自 `binder:3514_F` 和 `PowerManagerService`
  线程。重启之后**没有任何人下指令**，钩子自己在 `system_server` 里重新装上并继续记录。在
  Hooks 页关掉它，它当场就从进程里被卸掉——`hook_records` 立刻报空——而且救砖模块的那张纸条
  也跟着没了，正是这一点让救砖模块不会对一台问题不在自己的机器动手。
- **`posedmcp-guard`，两级都验过。** 先验归属，而且这是对的顺序：数到三次没能走完的开机、
  而磁盘上没有系统钩子时，它**清零退让**而不是悬停。有纸条时，第一级写入属性和文件，应用随后
  **在任何弹窗之前就拒绝持久系统钩子**——同时仍然放行 `persist=false`；Hooks 页显示原因和
  一个解除按钮，按下后属性和文件都被清掉，救砖模块退回第一级。第二级把两份钩子库都复制进
  `backup/` 再挪开，还原之后应用把库完整读了回来。

### 尚未验证

- **新设备的失败路径。** 没有测试覆盖现代后端在"框架缺失""钩子装不上""链条抛异常"时的
  表现，而经典那条路是有的。

- **救砖模块从没拦过一次真的无限重启。** 它的每个分支都是靠手工跑脚本、配合人为堆出来的计数
  验的，一次普通重启被完整观察过——但还没有人真的让设备带着一个坏的系统钩子开不完机，而那是
  唯一能确认时机在关键时刻成立的测法。
- **在 `system_server` 里只留过观察型钩子。** 验证用的钩子挂在一个改不了任何行为的方法上。
  会在那里**改变行为**的钩子还没跑过，所以"装得上、能扛过重启"是成立的，"坏的那种也能被兜住"
  还不成立。

- **状态页上"故障"那一支没在屏幕上看到过。** 那个状态是真的——正是它引出了这次改动——但这台
  ROM 重新绑定服务太快，试了多种造法（SIGKILL、`force-stop`、应用停止时写设置）都没能把它
  按住足够久来读那一屏。那一支提供的，就是我手动对真正 crashed 的服务跑过的那两条命令，
  而那两条是有效的。
- **解放双手模式的到期没有看到底。** 它周围的一切都验过：倒计时在走、`module_status`
  用同一个比较翻转、重启会清掉。但为了看计时器归零而把闸门敞开十五分钟，不划算，
  所以最后这一步是推出来的，不是看出来的。与之同一条路径上的「the window ran out」
  横幅同样没见过。（唯一一次尝试是因为更好的理由提前结束的：ROM 在第 8 分钟把应用杀了，
  而正是这次杀出了上面那个"重启即清"的缺陷。）
- **被跳过的那几条横幅是否真的静默，在这里观察不到。** 合并本身验过了——第 4 次调用的
  正文里带着前几次的计数——但"中间那次更新会不会又弹一次横幅"属于 `setOnlyAlertOnce`
  的系统行为，而 `adb` 没法告诉你手机有没有震。如果它其实重弹了，症状是四条横幅而不是
  一条：吵，但不危险，改一个标志位就行。
- **Lua 钩子的 `set_result` / `set_arg` / `set_field` 没有专门测过。** 真正**改变**行为
  而不是观察的那一半显然在用——酷安那批效果里有好几条是行为性的，而让
  `getDetailSponsorCard` 返回 nil 靠观察做不到——但这次是**通过那次使用**确认的，
  不是靠一条专门的测试。
- Hooks 页的删除按钮、`hook_method` 的注册路径没有跑过完整一轮；`hook_lua` 的跑了
  （就是上面酷安那批）。
- 无障碍路由的 `ui_dump` / `screen_capture` / `input_inject` —— 实现完成、编译通过、
  路由已接，但还没在真机上跑过完整一轮（每次都需要人工点确认弹窗）。
  非 root 的界面自动化正是这条路的重点，值得先跑一遍
  [docs/GITHUB_STAR_DEMO.md](docs/GITHUB_STAR_DEMO.md)。
- 有一次读取工具返回值的实验里看到中文变成 U+FFFD。同一份数据在应用自己的日志里是
  完好的，所以最可能出在测试客户端而不是服务端；但在查清之前，任何**从服务端读回中文**
  的地方都值得留意。

## 许可

无。自用项目。
