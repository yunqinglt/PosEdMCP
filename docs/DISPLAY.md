# Display 辅助软件接入

Display 0.4.0 的独立工程位于 [yunqinglt/Display](https://github.com/yunqinglt/Display)。已实现Project UI、Core、原子持久化和现代API102框架钩子（实测Vector2.2）。UI为作用域应用/开关→类别→基础字段编辑，与AIDL共用Core，配置和开关原子保存。UI截图见Display的[PROJECT-UI.md](https://github.com/yunqinglt/Display/blob/main/docs/PROJECT-UI.md)，钩子边界见[BACKEND.md](https://github.com/yunqinglt/Display/blob/main/docs/BACKEND.md)，新增接口见[EXPANDED-HOOKS.md](https://github.com/yunqinglt/Display/blob/main/docs/EXPANDED-HOOKS.md)。

PosEdMCP 通过显式绑定 `dev.display/dev.display.DisplayAidlService` 访问接口。两端必须同签名，绑定由 signature 级 `dev.display.permission.CONTROL` 保护，服务还逐事务核对调用包。`display-api/` 为维护源的本地快照，独立构建不引用 D 盘。

| MCP 工具 | 当前行为 |
| --- | --- |
| display_status | Core可用性及真实框架service连接状态 |
| display_capabilities | 51组规则（含截图事件/录屏状态），backendImplemented目录；runtimeSupported=null需查询具体进程 |
| display_validate_profile | 校验 profile 对象；不保存、不应用 |
| display_get_profile | 读取持久化配置及版本 |
| display_put_profile / display_delete_profile | CAS/持久化去重；禁止删除仍被引用的配置 |
| display_get_target | 读取目标固定配置版本及目标版本 |
| display_apply_profile / display_clear_target | 校验真实作用域并原子保存意图；清除保留版本墓碑 |
| display_runtime_state | 实际scope、每进程ACK、支持/失败项及调用计数 |

`profile` 直接传 JSON 对象，无需双重 JSON 编码。例如：

```json
{"profile":{"schemaVersion":1,"id":"demo","rules":[{"key":"state.batteryLevel","value":80}]}}
```

写工具包含reason、requestId、CAS版本；应用还包含profileId和profileRevision。变更沿用现有ConfirmationGate.Kind.PLUGIN策略；apply读取并展示指定版本后提交同一版本。后端原子保存最近256次成功变更的重试结果，窗口外须按CAS重新核对。

绑定合并并发请求，最多等 4 秒；不在主线程执行，不自动重试变更；断开、空绑定、binding died 和 MCP Service 销毁时解绑。4 秒仅为绑定上限，Binder 方法执行本身需要后端保证有界。

真实生效须结合Core报告的作用域、per-process ACK、失败项及实际值；不把目录、服务可达或保存成功等同于覆盖所有接口。已缓存值可能需要刷新/重启。旧的test-display-device.ps1是0.1.0前端历史回归，会在Core存在时主动终止；当前测试脚本见test-display-configure.ps1和test-display-framework.lua。

完整研究与协议说明位于 Display 工程 `docs/RESEARCH.md`、`docs/AIDL.md`。Display提供独立Project UI；PosEdMCP的工具与现有AIDL不变，工具列表从注册表自动列出上述工具。

历史前端回归：`tools/test-display-device.ps1 -Serial <设备序列号>`。HyperOS 如拒绝辅助软件服务唤起，需在系统设置中允许 Display/Deploy 后台自启动；更新APK还可能重置厂商自启动开关。绑定失败本身不能证明 LSPosed 作用域有问题。详细实机记录见 Display 工程 `docs/XIAOMI15-DEVICE.md`。

2026-10-22前端阶段：小米 15 / Android 15 实机 27 项检查通过，包含两个作用域 peer 在线、10 个工具注册、20 个规则目录、实际 Binder 校验、Core 缺失错误语义与 Deploy AIDL。

后端阶段：目录扩展到21规则，175项前端+26项Core回归通过。最终版本隐秘参数15项getter、六类真实传感器、时钟及定位对象检查通过；Edge定位单独配置在0.2.3取消Chromium定位调用方优化后，用户确认网页为东京站测试坐标。整组20规则曾使Edge冷启动native崩溃，根因未隔离；配置脚本不再对Edge应用整组规则。最终两目标绑定均撤回且ACK与恢复取值通过，两个示例配置仅保留未启用。

当前 Display 0.4.0 目录已扩展至51组，12类别/63基础字段。截图事件与录屏状态分开处理；详情见[扩展接口](https://github.com/yunqinglt/Display/blob/main/docs/EXPANDED-HOOKS.md)。PosEdMCP 1.1.7 包含同步契约，AIDL API版本与事务号不变。
