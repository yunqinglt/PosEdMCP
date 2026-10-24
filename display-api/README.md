# Display AIDL 契约副本

维护源：[Display/display-api](https://github.com/yunqinglt/Display/tree/main/display-api)。本目录是可独立构建的源码快照，不能只改单端。
当前为 v1 / Display 0.4.0，包含控制接口、作用域运行端接口与规则契约；Core、框架钩子和 Project UI 均已实现。
PosEdMCP 使用控制接口；目标进程由 Display 自己的模块使用运行端接口。
集成说明：[DISPLAY.md](../docs/DISPLAY.md)。协议完整说明见 Display 仓库 [AIDL.md](https://github.com/yunqinglt/Display/blob/main/docs/AIDL.md)。

同步后核对：

```powershell
git diff --no-index -- display-api/src D:/Display/display-api/src
git diff --no-index -- display-api/build.gradle.kts D:/Display/display-api/build.gradle.kts
```
