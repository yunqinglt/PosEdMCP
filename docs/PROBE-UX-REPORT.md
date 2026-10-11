# 探针冻结的用户体验研究报告

> 研究问题：在 cgroup.freeze 与信号量两种后端下，冻结对用户体验的三个维度——
> ①与 Agent 的交流 ②冻结期间后台化目标应用 ③对其他应用组件的影响。
> 全部结论基于真机实测；测试日期 2026-10-08。

## 测试环境

| 项 | 一加 PLR110（ColorOS/Android 16） | 小米 24129PN74C（HyperOS 2/Android 15） |
|---|---|---|
| 应用版本 | 1.1.1 | 1.1.1 |
| 冻结后端 | cgroup.freezer（`/apps/uid_NNNN`） | cgroup.freezer（`uid_NNNN`） |
| 冻结守卫（FreezeGuard） | **已装并验证**（含 HANS 解冻拦截 + ANR kill 拦截） | **未装**（守卫有 ColorOS 检查，且该机 system_server 中的模块是旧版，`probe_guard` op 未生效） |
| 备注 | 守卫验证见 1.1.1 提交 | 恰好构成"无守卫"对照组 |

## 结论摘要

| 问题 | cgroup 后端（有守卫/ColorOS） | cgroup 后端（无守卫/MIUI 现状） | 信号量后端（回退路径） |
|---|---|---|---|
| ①与 Agent 交流 | ✅ 完全不受影响 | ✅ 完全不受影响 | ✅ 完全不受影响 |
| ②冻结期间后台化目标 | ✅ 可行（已实测） | ❌ 目标 ~10s 内死于输入 ANR | ⚠️ 同左，且新孵化的进程逃逸冻结 |
| ③其他组件 | ⚠️ 调用方会被挂起（见下） | ⚠️ 同左 | ⚠️ 同左，影响面更小（只冻已有 pid） |

---

## 问题 1：冻结会不会影响与 Agent 的交流

**结论：不影响。** 架构上，MCP 服务器跑在奈何桥（`dev.posedmcp`）自己的进程里，
与冻结目标（任意前台应用）是不同 uid、不同 cgroup；冻结只作用于目标的 uid 子树。

**实测（小米，市场冻结期间从宿主机调用）：**

```
device_info  → 正常返回，955ms（含 adb 推文件往返开销，与未冻结时无异）
probe_state  → 正常返回：frozen=true、60 线程、ui 树、activity 齐全
奈何桥自身进程 → 全程存活、可响应（pid 9461）
```

**边界情况（两条，都需写进对模型的提示）：**

1. **Agent 的宿主应用不能被冻结。** 本机的 Agent（RikkaHub 等）跑在它自己的应用里，
   用户若把它当前台冻住，Agent 自己就静止了——这不是服务器的问题，而是"冻错了对象"。
   `probe_state` 的 notes 已经教模型"冻结由用户按钮发起、冻的是前台应用"；
   可以再补一句"别让用户冻结你正在使用的宿主应用"。
2. **被冻结的进程无法应答任何请求**（包括桥请求）。探针的快照机制（冻结前抓取、
   落盘、`probe_state` 读快照）正是为此设计的，本轮实测无恙。

**缓解建议（低优先级）：** 无需缓解，仅文档化。

---

## 问题 2：冻结期间能否把目标置于后台、用户去干别的事

**结论分设备：ColorOS（有守卫）= 可以；无守卫 = 必死。这是本轮最重要的发现。**

### 无守卫侧的死链（小米实测复现，与 ColorOS 守卫前完全一致）

```
冻结市场 → HOME →
  am_anr: Input dispatching timed out ... Waited 5001ms for FocusEvent(hasFocus=false)
  am_kill: [0,21589,com.xiaomi.market,200,bg anr]
  exit-info: reason=6 (ANR)  description="bg anr: Input dispatching timed out ..."
```

目标在后台化后 **10 秒左右被输入 ANR 杀掉**。根因：冻结的应用无法确认焦点丢失，
input dispatcher 等待 5 秒后判 ANR 并杀进程。与 ROM 厂商无关，是通用 Android 机制。

### 有守卫侧（ColorOS，1.1.1 已实测）

冻结 htmlviewer → HOME → 20 秒后：**pid 不变、cgroup.freeze 仍为 1**，
日志 `freeze guard: blocked anr kill of uid 10252 (bg anr)`。
期间桌面与其他应用完全可交互（守卫只拦目标 uid 的 ANR kill 和 HANS 解冻，
不碰任何别的进程）。

### 遗留问题（按重要性排序）

1. **目标死亡后探针状态不清理。** 小米实测：市场死后，我们的状态仍显示
   frozen=true 直到 60s 到期，悬浮窗也还显示冻结态——"冻着一个已经不存在的应用"。
   **需要 TARGET_DIED 检测**（到期时/读状态时校验 pid+starttime 是否还活着），
   这是手机内 Agent 报告里 P0 状态机的一部分，尚未实现。
2. **最近任务划掉目标的杀法未验证。** 守卫只拦 reason 含 "anr" 的 kill；
   从最近任务划掉/`am task remove` 走的是另一条 reason（大概率 "remove task"），
   目标仍可能被杀。需要实测确认 reason 并决定是否纳入守卫。
3. **"现场画面"只在快照里。** 目标后台化后，屏幕显示的是新前台应用；
   冻结的视觉现场只有冻结前抓的截图（模型可随时 `screen_capture` 时已不是它）
   与 `probe_state` 里的 ui 树/线程栈。这是语义问题不是缺陷——报告要讲清楚：
   后台化 = 保留"法医现场"，不保留"可视现场"。
4. **恢复后目标留在后台。** 解冻不会把目标拉回前台；用户需要自己切回。
   可以考虑解冻后弹 toast/通知提示，或提供"解冻并回到它"的按钮。
5. **信号量后端下，新孵化的进程逃逸。** 小米实测中冻结 Kuaishou 时它的主进程
   尚未孵化（只有 :push_v3、:permission 两个子进程被冻）——cgroup 后端下后续
   孵化会"出生即冻"（uid 子树冻结），信号量后端下则自由运行。这是 cgroup 后端
   又一个实打实的 UX 优势。

---

## 问题 3：其他应用组件是否受影响

**结论：目标自身的一切（含其 provider/binder/广播）对外部调用方是"黑洞"——
调用方线程会挂起；但系统其余部分与我们自己的服务不受影响。**

### 实测：Provider 调用方挂起（小米，Kuaishou）

```
冻结前：content query content://com.smile.gifmaker.msProviderF/ → 1.5s 返回
冻结后：同样查询 → 整整 15 秒无响应，被 timeout 强杀
```

任何应用/组件对被冻结应用的 **ContentProvider 调用、同步 Binder 调用**都会
让**调用方自己的线程**挂起（直到超时），严重时调用方自己 ANR。
**有序广播**投递到冻结应用时，整条广播队列会阻塞，后面的接收者全部延后。
这三类是最实际的"殃及池鱼"面。

### 实测：启动链被冻结（小米，Kuaishou）

冻结时 Kuaishou 只有两个子进程（主进程尚未孵化），冻结后**主进程始终没有出现**——
子进程冻着，应用无法完成启动流程。含义：**冻结一个正在启动的应用 = 冻结它的启动**，
对"开屏广告刚出现就冻"这个核心场景反而有利（广告 SDK 的初始化链一并静止）。

### 逐项影响清单与缓解

| 组件/机制 | 影响 | 缓解（现状/建议） |
|---|---|---|
| 目标自己的进程 | 全冻结（预期） | — |
| 调用方的 provider/binder 线程 | 挂起至超时，可能连带 ANR | 暂无；建议冻结前提示"此应用有 N 个 provider 被外部使用"（`dumpsys package` 可查） |
| 有序广播队列 | 阻塞后续接收者 | 暂无；守卫可延后目标专属广播（手机内 Agent 报告的 P1） |
| 共享 uid 的其他包 | 一并冻结 | **实测第三方无共享 uid**（Android 新版已移除 sharedUserId），系统 uid 1000 的 50 个包共享——但我们拒绝冻结系统应用，无实际风险 |
| 隔离渲染进程（isolated uid） | **不受 uid 冻结影响** | 已记录；冻结前枚举渲染进程并单独纳入（报告的 P1） |
| 新孵化的进程 | cgroup：出生即冻 ✅；信号量：逃逸 ⚠️ | 后端差异，见摘要表 |
| 系统服务/ROM 冷冻器 | ColorOS：HANS 解冻与 ANR kill 已被守卫 ✅；MIUI：未守卫 ❌ | **建议把 ANR kill 守卫推广到全部 ROM**（`ProcessRecord#killLocked` 是 AOSP 通用入口，只是当前被 ColorOS 检查挡在门外；HANS 部分保持 ColorOS 专属） |
| 奈何桥自身 + 无障碍 + MCP | 实测无影响 ✅ | — |
| 冻结目标在最近任务被划掉 | 疑似被杀（未实测） | 待验证 reason 后决定是否进守卫 |

---

## 后端差异总结（对 UX 的三个维度）

| | cgroup.freezer | 信号量 |
|---|---|---|
| 冻结范围 | uid 子树，原子、含未来孵化 | 已有 pid 快照，新孵化逃逸 |
| 解冻 | 幂等写 0，无 pid 复用风险 | 按 pid+starttime 核验后 CONT |
| 状态观测 | `/proc` 显示 S，需看 cgroup.events | `/proc` 显示 T，直观 |
| 与 ROM 冷冻器 | 同文件争写（ColorOS 已守卫） | 无争写，但 ROM 可能重启被冻进程 |
| UX 差异 | 全面优于信号量，唯一代价是与 ROM 争写 | 仅作回退 |

## 下一步建议（按优先级）

1. **ANR kill 守卫推广到全部 ROM**（拆开 FreezeGuard 的 ColorOS 检查：killLocked 部分通用，HANS 部分专属）。
2. **TARGET_DIED 状态检测**：到期/查询时核验 pid+starttime，死了就清状态、窗口回待机。
3. **冻结前检查**：目标有无外部可见 provider/广播接收者，有则提示风险。
4. 验证"最近任务划掉"的 reason 并决定守卫范围。
