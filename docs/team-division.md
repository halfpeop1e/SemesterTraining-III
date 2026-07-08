# 团队分工

## 模块分工

TODO: 根据团队成员分配各模块负责人

| 模块 | 负责人 | 后端包 | 前端页面 |
|------|--------|--------|----------|
| 总控调度与多车协同 | - | dispatch/ | Dispatch/ |
| 轨道线路与信号控制 | 黄豪 | signal/ | LineSignal/ |
| 车载系统与车辆运行仿真 | - | vehicle/ | Vehicle/ |
| 供电能源评估与分析 | - | energy/, evaluation/ | EnergyEvaluation/ |
| 仿真引擎 | - | simulation/ | - |
| 仪表盘 | - | - | Dashboard/ |

> 各模块开发完成后，请在仓库根 `handoff/` 文件夹下放一份《交接文档》（约定见 [`handoff/README.md`](../handoff/README.md)），
> 说明本模块覆盖的文件、对外接口、不可改的核心代码、允许联调改动的耦合点，方便其他模块平滑接入、减少冲突。
> 已完成：`handoff/轨道线路与信号控制-交接文档.md`（黄豪）；信号模块契约文档见 [`signal/`](./signal/README.md)。

> git 上传原则：**其他模块能用上的文档/代码 → 上传**（如 `docs/`、`handoff/`、各模块代码）；**本地使用的不上传**（如 `.docx/` 原件、`.workbuddy/` 记忆、`.harness/` 流程状态、`agent.md`，均在仓库根上级，本就不在仓库内）。
