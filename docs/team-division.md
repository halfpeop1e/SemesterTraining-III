# 团队分工


## 模块分工

| 成员 | 工程身份 | 负责模块 | 后端包 | 前端页面 |
|------|----------|----------|--------|----------|
| 成员一 | 总控调度与多车协同仿真工程师 | 总控调度系统与多车协同模块（含仿真引擎） | dispatch/, simulation/ | Dispatch/ |
| 成员二（黄豪） | 轨道线路与信号系统建模工程师 | 轨道线路与信号控制模块 | signal/ | LineSignal/ |
| 成员三（郭逸晨） | 车载运行控制与车辆动力学工程师 | 车载系统与车辆运行仿真模块 | vehicle/ | Vehicle/ |
| 成员四 | 供电能源与评估展示工程师 | 供电能源评估与结果分析模块（含仪表盘） | energy/, evaluation/ | EnergyEvaluation/, Dashboard/ |
| 成员五 | 文档与项目管理负责人 | 文档、测试与汇报材料 | - | - |

> 各模块开发完成后，请在仓库根 `handoff/` 文件夹下放一份《交接文档》（约定见 [`handoff/README.md`](../handoff/README.md)），
> 说明本模块覆盖的文件、对外接口、不可改的核心代码、允许联调改动的耦合点，方便其他模块平滑接入、减少冲突。
> 已完成：`handoff/轨道线路与信号控制-交接文档.md`（黄豪）；信号模块契约文档见 [`signal/`](./signal/README.md)。

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
