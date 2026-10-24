# PosEdMCP 开发记忆

更新：2026-10-24。用户长期使用反馈，后续开发需延续。

## 本轮反馈与实现

1. 单独启动作用域应用时，凭据/钩子库查询会拉起 PosEdMCP 主进程，导致启动钩子到得太晚。
   已将 `BridgeTokenService`、`BridgeTokenProvider` 放进 `:bootstrap` 进程。
   真机发现 Android 16 的包可见性会阻止部分目标绑定该服务，因此增加框架远程配置通道：
   `dev.posedmcp.XposedService` provider 也在 `:bootstrap`，只接受 root/system 的框架 Binder；
   使用 libxposed service API 100..102 共用的版本及偏好更新事务，发布完整快照到框架私有配置。
   注入端优先通过框架的 `getRemotePreferences` 读快照，在包加载阶段提前恢复策略及普通钩子。
   API 100 构造路径直接使用传入的 `XposedInterface`，不依赖不存在的 `attachFramework`。
   主进程发布私有原子快照，子进程每次直接读盘，避免多进程 SharedPreferences 缓存旧配置；
   返回调用 UID 对应包的普通钩子，不返回系统钩子。
   模块在 `Application.attach` 后、provider 初始化及 `Application.onCreate` 前，做最多 1500ms 的 Binder 等待；
   服务连接和 Messenger 回复均用工作线程，避免主线程互等。
   超时继续启动，网络桥和稍后的钩子恢复仍可工作。这个限制只约束 Binder 等待，不包括安装用户钩子的时间。
   组件工厂、Application 构造器及 attach 之前执行的代码不在这次提前恢复的保证范围。

2. 增加「更多信息 → 导出诊断日志」，保存 ZIP 到用户选择的位置。
   收集版本、设备、钩子状态、应用/`:bootstrap` 轮转日志、应用 UID 的 logcat，
   以及已连接作用域进程和 system_server 中有界的近期模块日志/策略状态。
   token 脱敏，不直接打包偏好设置、脚本、钩子源码、参数记录或截图。
   日志文本仍可能包含用户数据。完整系统 logcat 需要另外通过 ADB 收集。
   已退出或无法连接的作用域进程，其内存日志无法恢复；导出中保留连接失败原因。

3. 内置隐藏钩子独立于 `HookRegistry`，不显示为普通用户钩子，也不会被普通 `hook_clear` 清掉。
   特殊设置可统一开关，默认启用，仅作用于作用域内第三方应用；系统应用和系统框架排除。
   - `StackTraceElement#getClassName`：替换模块/框架相关类名，保留应用自身堆栈类名。
   - `ClassLoader#loadClass`：对外部探测模块/框架类返回 ClassNotFoundException，保留模块自身加载路径。
   - `Method#getModifiers`：仅针对记录为原始 Java 方法的已装钩子去除合成 native 位，保留真实 JNI 方法。
   - `BufferedReader#readLine`（用户反馈中写作 BufferReader）：跟踪 maps 的流/reader 链，跳过模块相关映射行；
     不改普通文本 reader，保留 EOF。当前跟踪 FileInputStream/FileReader 的文件路径构造方式，
     不覆盖自定义 Reader、仅 FileDescriptor 输入或原生层直接读 maps。
   这是减少 Java 可见痕迹的策略，不能承诺对所有检测完全不可见。
   ART 首次校验回调参数适配类会重入核心钩子并等待自身校验，真机会卡住；
   两个 HookApi 后端现都在安装前初始化适配类，并在安装期间暂不执行隐藏逻辑。
   `scope_status` 可只读查询目标进程的模块版本、后端、内置钩子失败数及 DNS 命中数。

4. 增加 `libc.so#getaddrinfo` 广告域名过滤基础能力。
   特殊设置可启用及编辑规则；默认关闭、无预置域名名单。普通域名精确匹配，`*.example.com` 仅匹配子域名。
   规则规范化 IDN/大小写/末尾根域点并检查长度、格式和条数；C 层采用标签边界匹配。
   命中返回 EAI_NONAME，不分配 addrinfo，清空结果指针；其他请求调用原函数。
   通过框架提供的 native hook API 安装，接口缺失或安装失败会报告原因。
   不覆盖直连 IP、自建 DNS/DoH、其他解析入口或已有连接。
   参考：[LSPosed Native Hook ABI](https://github.com/LSPosed/LSPosed/wiki/Native-Hook)。

雅典娜预设同时加入已在 Deploy 策略使用的 Hans importance 豁免，仅匹配 `dev.posedmcp` 及 `:` 子进程。
用户在设置中启用过的预设由 `SpecialHooks.refresh` 更新到新源码，保留开关状态和系统钩子救援机制。

## 后续需求：本轮明确不实现

- 新建独立的「去广告策略」页面。
- 从广告 SDK 入手做更精准的钩子策略，域名过滤只是初步能力。
- 设计可导入/导出的钩子策略接口与数据格式，便于分享和复用。
- 后续设计时考虑 SDK/版本匹配条件、应用范围、Java/native 钩子定义与域名规则；
  导入策略执行仍应遵守项目已有的审批与系统钩子救援约束。
- 本轮只记录上述方向，不新增策略页面，不把当前内部配置 JSON 宣称为已稳定的策略导出协议。

## 本轮验证与待验证

- 主机回归：钩子 owner 共存、嵌套记录、清理/挂起，Athena/Hans 包边界，配置快照更新、
  UID 过滤、隐藏回调、真实 JNI 位保留、连续 maps 行及 EOF、异常替换、token 脱敏、原生域名匹配。
- Android APK 编译、Lint 与 APK 内入口/原生导出检查。
- 真机：PLR110、Android 16、LSPosed 1.10.2；模块更新到 1.1.7。严格遵守用户要求，未重启设备。
- 冷启动前退出模块主进程及 bootstrap，并临时停用模块自己的无障碍绑定/服务；
  启动 fixture 后只有 `:bootstrap` 出现，主进程没有被拉起。Provider 与 Application 均读到保存钩子的 `early`。
  该次 `am start -W` 为 496ms，仅是测试应用样本，不是所有应用的性能承诺。
  测试完成后重新打开主应用并恢复原无障碍列表。
- 隐藏：9 组安装、0 失败；模块类探测返回 CNFE，自己的堆栈类名保留，模块类名被替换；
  JNI 原生位保留，已挂钩 Java 方法 native 位隐藏；原生 maps 中 4 行模块映射在 Java reader 中为 0 行，
  普通文本及 EOF 保持不变。普通测试钩子删除后内置钩子仍保留。
- Native DNS：框架 hook API 成功接入，两条测试规则命中计数 2；精确及通配子域名返回 EAI_NONAME，
  数字 IP/localhost 正常。禁用时 example.com 正常解析。
- 日志：通过实际文件选择器保存 ZIP，校验 ZIP 完整性、版本、轮转日志、logcat 和已连接作用域近期日志/策略状态；
  校验未含当前凭据及历史 64 位十六进制 token。旧模块进程不支持 diagnostics 时保留错误原因。
- 配置刷新：bootstrap 不重启时，经实际设置页面关闭隐藏/DNS、清空规则，再启动目标，测试类可见且解析正常；
  重新开启隐藏后，目标恢复隐藏。最终保持隐藏开启、广告 DNS 关闭且无测试域名。
- 已删除临时 Sentinel 保存钩子并恢复原 Deploy fixture APK；测试临时配置副本已清理。
- 没有重新加载 system_server 或重启设备；系统侧新增 Java 代码及其他框架（尤其 API 102）仍需另行真机回归。
  新版 Athena Lua 可经已有桥重新下发，但本轮不把 ROM 上所有 Hans 分支的运行行为视为已验证。
- 可复用 Android 测试应用源码见 `tests/android-runtime-fixture`；构建脚本不自动安装或改作用域。
  用户要求未来独立去广告页面/SDK 精确策略/策略导出接口，本轮仍只记录，不实现。
