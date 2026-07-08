# 信号模块文档（轨道线路与信号控制 · 黄豪）

> 仓库内文档，随 git 提交。本目录只放**其他模块接线必需**的团队契约文档，个人草稿/汇报/测试用例不上传（留本地 `.docs/`）。

## 文档清单

| 文档 | 用途 |
|------|------|
| [MA模块与仿真引擎对接接口说明.md](./MA模块与仿真引擎对接接口说明.md) | **接线主文档**：`compute(...)` 签名、`TrainState`/`MovingAuthority` 字段、`Route`/`nowSec`、UP/DOWN 语义、fail-safe 表、`SignalAspect`/`Direction`/`TrainStateAdapter`、tick 接线示例、接入 Checklist |
| [MA模块与司机台协议对齐清单.md](./MA模块与司机台协议对齐清单.md) | 与 704 司机台协议的字段/编码冲突点与落地状态（方向、信号机显示已对齐） |

## 关联

- 代码：`backend/src/main/java/com/bjtu/railtransit/signal/`
- 测试：`backend/src/test/java/com/bjtu/railtransit/signal/`
- 变更日志：`backend/backend-changelog-1.md` ~ `backend-changelog-11.md`
- 交接文档 / 数据结构复用排查 / 依据摘录：[`../../handoff/`](../../handoff/)（各模块接线前必读，尤其「数据结构复用排查」防重复定义）

> 注：全流程汇报、QA 测试用例、思路梳理、教学资料整理等为个人本地文档（`.docs/`，不在仓库内）。
