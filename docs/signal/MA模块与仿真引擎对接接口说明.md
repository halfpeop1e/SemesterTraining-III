# MA 模块与仿真引擎对接接口说明

> 适用对象：仿真引擎 / 总控（周锦泽）、车载 ATO（郭逸晨）接线方
> 所属模块：轨道线路与信号控制模块（黄豪，`com.bjtu.railtransit.signal`）
> 对应代码：`backend/src/main/java/com/bjtu/railtransit/signal/`
> 文档状态：随 `state.json` 中 `status=ACCEPTED` 一并定稿（2026-07-08）
> 关联文档：`tech-design.md` §2 / `verification.md` §5 / `review-checklist.md`（本地开发过程文档，位于 `.harness/`，不在 git 仓库内）

---

## 1. 总览

移动授权（MA）计算是**一个纯函数**，由仿真引擎在**每一个仿真 tick** 调用一次，为所有在线列车分别算出"最远能跑到哪（EoA）"和"允许的最大速度"。

```
引擎每 tick：
  ① 总控更新 Route / 信号机 / 道岔状态
  ② 调用 MovingAuthorityService.compute(...)   ← 本模块
  ③ ATO 依据返回的 MA 计算目标速度 / 加速度
  ④ 引擎物理积分，推进仿真时刻
  ⑤ 占用 / 冲突检测 → 回到 ①
```

- **不持有任何可变状态**：同输入必得同输出；可重复调用、可并行、可回放。
- **无副作用**：不会改 `LineProfile` / `TrainState` / `Route`，只读取。
- **fail-safe 默认收紧**：任何不确定的输入（前车状态未知、本车时间戳过期、道岔/信号非安全态）都让授权**变小**，绝不放大。

---

## 2. 方法签名（三个重载）

包路径：`com.bjtu.railtransit.signal.service.MovingAuthorityService`

```java
// 最常用：给定线路 + 全部列车状态（+ 进路 + 仿真时刻）
Map<String, MovingAuthority> compute(LineProfile line,
                                      List<TrainState> trains,
                                      Map<String, Route> routes,
                                      double nowSec);

// 不带 nowSec：关闭"时间戳过期"降级（仍保留"前车未知"降级）
Map<String, MovingAuthority> compute(LineProfile line,
                                      List<TrainState> trains,
                                      Map<String, Route> routes);

// 最简：只有线路 + 列车（无进路、无时刻）
Map<String, MovingAuthority> compute(LineProfile line,
                                      List<TrainState> trains);
```

获取实例（二选一）：

```java
// A. Spring 环境（后端已 @Service，直接注入）
@Autowired MovingAuthorityService maService;

// B. 非 Spring 的引擎 / 测试：手动 new（MaConfig 有默认安全值）
MovingAuthorityService maService = new MovingAuthorityService(new MaConfig());
```

返回：`Map<trainId, MovingAuthority>`，key 与 `TrainState.trainId` 一一对应。

---

## 3. 入参详解

### 3.1 `LineProfile line` —— 线路静态 + 当前线路状态

构建步骤（**必须先建里程索引**）：

```java
LineProfile line = new LineProfile();
line.setSegments(...);        // TrackSegment 邻接链（见 §6）
line.setSwitches(...);        // 道岔（可选）
line.setSignals(...);         // 信号机（可选）
line.setTurnbacks(...);       // 折返线（可选）
line.setAxleSections(...);    // 计轴区段（可选）
line.setTsrs(...);            // 临时限速（可选）
line.setGradients(...);       // 坡度（可选，影响制动距离）
line.setTotalLengthM(2000.0); // 线路总长 m

line.buildMileageIndex();     // ★ 必须调用一次：把 (Seg, offsetCm) 链成绝对里程 m
```

> 若从真实 CBTC 数据加载：`LineProfileLoader.loadFromJsonFile(path)` 会自动 `buildMileageIndex()` 并完成 cm→m、cm/s→km/h 单位换算，**无需手动调用**。

影响 MA 的字段（其余字段 MA 不读，可留空）：
`segments`（坐标链）、`totalLengthM`、`turnbacks`、`switches`（含 `state`）、`signals`、`axleSections`（含 `occupied`）、`tsrs`、`gradients`、`routes`/`overlaps`（经 `routes` 入参传入时才会用到）。

坐标接口（供引擎/调试用）：
`line.locateMileage(segId, offsetCm) → 绝对里程 m`、`line.segStartM(segId)`、`line.segEndM(segId)`。

### 3.2 `List<TrainState> trains` —— 全部列车当前状态

每个 `TrainState` 字段（**单位见 §12**）：

| 字段 | 类型 | 单位 | 必填 | 说明 |
|------|------|------|------|------|
| `trainId` | String | — | ✅ | 列车唯一标识，也是返回 Map 的 key |
| `positionM` | double | m | ✅ | **车头**里程（绝对里程，须已 buildMileageIndex） |
| `speedKmh` | double | km/h | ✅ | 当前速度，非 NaN |
| `accelerationMps2` | double | m/s² | ⚪ | 当前加速度（目前 MA 仅记录、不参与约束） |
| `lengthM` | double | m | ✅ | 列车长度，须 > 0 |
| `direction` | Direction | — | ✅ | `UP` / `DOWN`（见 §8）；`INVALID`/null 触发 fail-safe |
| `timestamp` | double | s | ⚪* | 仿真时刻；**带 nowSec 的重载下必填**（见 §4.4） |

> *校验：`validState(t)` 要求 `positionM` 非 NaN、`lengthM > 0`、`speedKmh` 非 NaN。任意列车不满足 → 该列车（及依赖它的列车）触发 `DEGRADED` 降级（见 §9）。

### 3.3 `Map<String, Route> routes` —— 每车已建立进路（可选）

- key = `trainId`（与 `TrainState.trainId` 一致）；value = 该车的 `Route`。
- **不必给每辆车都给**，没进路就只按线路/前车约束授权。
- `Route` 字段（仅以下被读取）：`id`、`endSignalId`、`overlapIds`（保护区段）。

```java
Route r = new Route();
r.setId(101);
r.setEndSignalId(57);                 // 进路终端信号机
r.setOverlapIds(List.of(201, 202));   // 保护区段（可选）
routes.put("T1", r);
```

### 3.4 `double nowSec` —— 仿真时刻（可选但推荐）

- 单位：**秒**，须与 `TrainState.timestamp` **同一时间基准**（都来自引擎时钟）。
- 传入后启用「MA 过期」降级：若某车 `timestamp < nowSec - maValiditySec`（默认 5s），该车 MA 收紧至当前位置、限速=0（`event=MA_EXPIRED`）。
- 不传（或 `Double.NaN`）则关闭该检查。

### 3.5 构造 `TrainState` 的权威入口（`TrainStateAdapter`）

为避免各模块把"段相对 + m/s"各写一份转换、或新建第二个 TrainState，本模块提供边界转换入口：

```java
package com.bjtu.railtransit.signal.service;
public final class TrainStateAdapter {
    // 段相对 + m/s → 绝对里程 m + km/h（locateMileage + Units）
    static TrainState fromSegmentRuntime(LineProfile line, String trainId,
                                         int segId, double offsetM, double speedMps,
                                         Direction direction, double lengthM, double timestamp);
    // 已知绝对里程 + km/h 时的便捷入口
    static TrainState fromAbsolute(String trainId, double positionM, double speedKmh,
                                   Direction direction, double lengthM, double timestamp);
}
```

- 其他模块喂 MA 前**统一调它**，不要手写 `new TrainState()` + 一堆 setter，也不要各自换算。
- 非法 segId → `positionM=NaN` → `computeOne` 本车状态校验 fail-safe → `DEGRADED`（见 §7）。
- 704 协议"积累走行距离(cm)"坐标系待周锦泽确认，暂未提供该入口；确认后再补。

---

## 4. 出参详解 `MovingAuthority`

每个列车一个 `MovingAuthority`，字段：

| 字段 | 类型 | 单位 | 含义 / 引擎侧消费建议 |
|------|------|------|----------------------|
| `trainId` | String | — | 列车标识 |
| `endOfAuthorityM` | double | m | **授权终点里程**（绝对里程）。列车本 tick 不得越过此点 |
| `maxSpeedKmh` | double | km/h | 该授权下允许的最大速度（取沿路最严限速） |
| `basis` | AuthorityBasis | — | 哪条约束最"紧"（见 §6 枚举） |
| `event` | SignalEvent | — | 伴随事件 / 降级标记（见 §7；`DEGRADED`/`MA_EXPIRED` 须立即停车） |
| `timestamp` | double | s | 直接拷贝自该车 `TrainState.timestamp` |
| `capSignalId` | Integer | — | 截断 EoA 的边界信号机 id（仅 `basis=SIGNAL` 时非 null） |
| `routeId` | Integer | — | 授权所沿进路 id（仅 `basis=ROUTE_END/OVERLAP_END` 时非 null） |

**消费约定（给 ATO / 司机台）**：
1. 列车目标停车点 ≤ `endOfAuthorityM`；目标速度 ≤ `maxSpeedKmh`。
2. 当 `event == DEGRADED` 或 `event == MA_EXPIRED`：**立即施加最大制动至停车**（`maxSpeedKmh` 此时= `degradedSpeedKmh`，默认 0）。
3. `basis` / `capSignalId` / `routeId` 仅用于显示、日志记录、调试，不影响行车约束本身。

---

## 5. 枚举常量（照抄，勿改名）

### `AuthorityBasis`（约束来源）
`LINE_LIMIT` · `TSR` · `PRECEDING_TRAIN` · `SWITCH` · `TURNBACK_END` · `SIGNAL` · `ROUTE_END` · `OVERLAP_END` · `AXLE_OCCUPIED`

### `SignalEvent`（伴随事件）
`NONE` · `PRECEDING_OCCUPATION` · `SPEED_RESTRICTION` · `SWITCH_ABNORMAL` · `MA_EXPIRED` · `DEGRADED` · `SIGNAL_BOUNDARY` · `ROUTE_BLOCKED` · `AXLE_OCCUPIED` · `POSITION_LOSS`

### `Direction`
`UP` · `DOWN` · `INVALID`

> 与司机台协议对齐：`Direction.fromDriverConsole(int)` 将司机台信号屏方向编码 `0=上行 / 1=下行 / 其余=INVALID`。
> `INVALID` 触发 fail-safe（见 §7）。MA 模块为方向定义权威（协议 §4「线路的上行/下行方向由信号系统定义」）。

### `SignalAspect`（信号机显示状态，TASK-10 新增）
权威编码取协议 **3.3.2「信号系统⇒总控」**：`RED(0x01)` · `YELLOW(0x02)` · `RED_YELLOW(0x03)` · `GREEN(0x04)` · `YELLOW_DARK(0x05)` · `RED_DARK(0x06)` · `GREEN_DARK(0x07)` · `WHITE(0x08)` · `BLUE(0x0A)` · `RED_BROKEN(0x09)` · `GREEN_BROKEN(0x10)` · `YELLOW_BROKEN(0x20)` · `WHITE_BROKEN(0x30)`。

- `static SignalAspect decodeSignalSystem(int)`：未知字节 → `null`（fail-safe 当停车）。
- `boolean isProceed()`：仅 `GREEN` 为放行（保守；如运营允许绿黄放行在此扩展）。
- `int toDriverConsoleScreen()`：聚合到「司机台信号屏」 红(0x01)/绿(0x02)/白(0x03) —— GREEN→绿、WHITE→白、其余→红（最严格）。
- 文档内信号机编码共 3 套（3.3.2 / 视景 TCMS2VIEW / 视景表2），另两套由总控/视景侧各自解码，**不进 MA 核心**。

---

## 6. 六维约束与 basis 映射

`computeOne` 为每列车收集以下候选边界，取**沿行车方向最近的前方边界**作为 EoA：

| # | 约束 | 触发条件 | basis | event |
|---|------|----------|-------|-------|
| 0 | 前车占用 | 存在沿方向的前车 | `PRECEDING_TRAIN` | `PRECEDING_OCCUPATION` |
| 1 | 折返 / 线路终点 | 总是（兜底） | `TURNBACK_END` | `NONE` |
| 2 | 道岔 gating | 前方道岔 `state != NORMAL`（含 null） | `SWITCH` | `SWITCH_ABNORMAL` |
| 3 | 前方防护信号机 | 前方存在**非放行**信号机（`aspect` 为 null/停车/灭/断；`aspect=GREEN` 放行不截断） | `SIGNAL` | `SIGNAL_BOUNDARY` |
| 4 | 计轴占用 | 前方计轴区段 `occupied` | `AXLE_OCCUPIED` | `AXLE_OCCUPIED` |
| 5 | 进路 + 保护区段 | `routes` 中含该车进路 | `ROUTE_END` / `OVERLAP_END` | `ROUTE_BLOCKED` |

限速 `maxSpeedKmh`：沿 `positionM → EoA` 区间取最严（线路限速 / TSR / 侧向道岔限速）。

---

## 7. fail-safe 行为（重要）

MA 永远"宁可给小、绝不给大"。以下情况会**收紧授权**：

| 触发 | EoA | maxSpeedKmh | event |
|------|-----|-------------|-------|
| 本车 `direction` 为 null/INVALID（含司机台非法方向码） | = 当前位置 | = `degradedSpeedKmh`(默认0) | `DEGRADED` |
| 本车状态非法（`positionM`/`speedKmh` NaN、`lengthM`≤0，含 adapter 非法 segId） | = 当前位置 | = `degradedSpeedKmh`(默认0) | `DEGRADED` |
| 本车 `timestamp` 过期（带 nowSec 时） | = 当前位置 | = `degradedSpeedKmh`(默认0) | `MA_EXPIRED` |
| 任一他车状态未知（NaN / 长度≤0） | = 当前位置 | = `degradedSpeedKmh` | `DEGRADED` |
| 前车状态未知 | = 当前位置 | = `degradedSpeedKmh` | `DEGRADED` |
| 前方道岔 null / 非 NORMAL | 止于道岔前 | 按线路限速 | `SWITCH_ABNORMAL` |
| 前方信号机 `aspect` 为 null（未接入）/停车/灭/断 | 止于机前 `signalStopMarginM` | 按线路限速 | `SIGNAL_BOUNDARY` |
| 前方信号机 `aspect=GREEN`（放行） | 不截断（可越过） | 按线路限速 | — |

> 说明：`Signal.aspect` 已接入。`aspect == null`（联锁状态未灌入）时按硬边界 fail-safe；灌入 `SignalAspect` 后绿灯放行、停车/灭/断截断。灭灯/断灯按 fail-safe 当停车。

---

## 8. 方向语义（UP / DOWN）

- `UP`：里程递增方向。前车 = 位置更大者中最小；边界取 `min` 且 `> 当前位`；信号机停车余量 `m - signalStopMarginM`。
- `DOWN`：里程递减方向。前车 = 位置更小者中最大；边界取 `max` 且 `< 当前位`；信号机停车余量 `m + signalStopMarginM`（停在低里程侧）。

两方向实现完全镜像，已通过 `DownDirectionVerification`（17+ 断言）验证。

---

## 9. 集成接线示例（引擎 tick 循环）

```java
// 初始化（一次）
MovingAuthorityService ma = new MovingAuthorityService(new MaConfig());
LineProfile line = LineProfileLoader.loadFromJsonFile("line-profile.json"); // 自动建里程索引

while (simRunning) {
    double nowSec = engine.clock();                 // 仿真时刻（秒）
    List<TrainState> trains = engine.allTrains();   // 每 tick 最新列车状态
    Map<String, Route> routes = dispatch.currentRoutes(); // 周：每车进路（可空 Map）

    Map<String, MovingAuthority> maMap =
            ma.compute(line, trains, routes, nowSec); // ★ 本模块调用点

    for (TrainState t : trains) {
        MovingAuthority m = maMap.get(t.getTrainId());
        ato.applyAuthority(t, m);                   // 郭：依 MA 算速度曲线
    }
    engine.step(dt);                                // 物理积分，推进 nowSec
}
```

**REST 备选（松耦合 / 跨进程）**：已暴露 `POST /api/signal/ma`，入参 `{lineProfile, trains, routes?, nowSec?}`，返回 `ApiResponse{success,message,data}`，`data` 即 `Map<trainId, MovingAuthority>` 的 JSON。引擎若跨进程部署可用此接口，但**同进程内直接调 `compute()` 更低延迟**。

---

## 10. 引擎侧接入 Checklist

- [ ] 每 tick 在「总控更新 Route/信号/道岔 **之后**、ATO **之前**」调用 `compute`
- [ ] `LineProfile` 已 `buildMileageIndex()`（或经 `LineProfileLoader` 加载）
- [ ] 每个 `TrainState` 填全：`positionM` / `speedKmh` / `lengthM` / `direction` / `timestamp` 非 NaN，`lengthM > 0`
- [ ] `timestamp` 与 `nowSec` **同一时间基准（秒）**
- [ ] `routes` 的 key = `trainId`（String），与 `TrainState.trainId` 完全一致
- [ ] 消费 MA：`endOfAuthorityM` 作为本 tick 允许前进最远里程；`maxSpeedKmh` 作为限速
- [ ] `event == DEGRADED` / `MA_EXPIRED` 时**立即停车**
- [ ] 接周 Route/联锁后，`switch.state` 不再为 null；`signal.setAspect(SignalAspect)` 灌入联锁显示 → MA 自动细化（无需改本模块代码）
- [ ] 司机台方向字段经 `Direction.fromDriverConsole(...)` 解码后填入 `TrainState.direction`；非法值→`INVALID` 会触发 fail-safe

---

## 11. 单位约定

| 量 | 单位 |
|----|------|
| 速度 | km/h（接口层）；内部物理换算 m/s |
| 距离 / 位置 / 里程 | m |
| 坡度 | ‰（千分之，负=下坡） |
| 时间 | s（仿真时刻、反应时间、制动时间） |
| 加速度 | m/s² |

> 真实 CBTC 数据常用 cm / cm/s，`LineProfileLoader` 会自动换算；手动构造请用上表单位。

---

## 12. 已知限制与后续

1. **CBI / 总控状态接入前**：`switch.state` 为 null 视为非安全态（fail-safe 截断）。接周锦泽 Route / 联锁状态后自动消解（无需改本模块代码）。
2. **信号机显示状态已接入（TASK-10）**：`Signal.aspect` 已加，`eoaFromSignals` 按 `SignalAspect` 放行/截断（绿灯放行、停车/灭/断/null 截断）。**联锁侧只需把 `SignalAspect` 灌进 `Signal.setAspect(...)`** 即可，无需改 MA 代码。
3. **复杂度 O(n·k)**：n 车、k 线路实体；每车独立扫全车（O(n²) 找前车）。课程/小仿真规模无压力；若上百车或高频 tick，可按方向排序降到 O(n log n)。
4. **加速度未参与约束**：`accelerationMps2` 目前仅记录，MA 的制动距离用 `aBrakeMps2`（配置）估算，不读单车实时加速度。
5. **司机台协议对齐范围**：仅完成"MA 模块 ↔ 司机台"冲突点（方向编码、信号机显示）。故障限速/定位丢失/完整性丢失等属车辆/ATP 输入，非司机台协议直接冲突，按用户边界暂未做（见《MA模块与司机台协议对齐清单》）。

---

## 13. 配置项 `MaConfig`（可调参数一览）

| 参数 | 默认 | 含义 |
|------|------|------|
| `safeSeparationM` | 20.0 | 停车后两车最小净距 m |
| `tReactionS` | 2.0 | 反应时间 s |
| `tSafetyMarginS` | 1.0 | 安全余量 s |
| `aBrakeMps2` | 9.0 | 列车额定制动减速度 m/s²（车辆参数） |
| `aFloorMps2` | 0.8 | **兜底**下限 m/s²（极端坡度/坏数据用，非实际制动能力） |
| `defaultLineSpeedKmh` | 80.0 | 无具体限速表时的线路默认限速 km/h |
| `signalStopMarginM` | 10.0 | 信号机前停车余量 m |
| `degradedSpeedKmh` | 0.0 | 降级时允许最大速度 km/h（0=必须停车） |
| `maValiditySec` | 5.0 | MA 有效期 s（仅带 `nowSec` 的重载生效） |

> 复现 `tech-design.md` §2.5 数值算例（`t_total=5s → requiredGap=110m`）用 `MaConfig.exampleConfig()`。

---

## 14. 验收对照（引擎联调通过判据）

联调时建议验证以下端到端行为（单测已覆盖，见 `backend` 测试包）：
- 跟车场景：后车 EoA ≈ 前车车尾 − 安全净距 − 制动距离（含坡度修正）。
- 折返/线路终点：列车被限制在终点内。
- 道岔失表 / 信号前方：列车在边界前停车（fail-safe）。
- 前车状态丢失：后车立即 `DEGRADED`、限速归零。
- UP / DOWN 双向行为对称正确（DOWN 停车余量在机前低里程侧）。
- 司机台对齐：`Direction.fromDriverConsole` 编解码、`SignalAspect` 解码/到信号屏映射、绿灯放行不误杀通过、非法方向 fail-safe（见 `DriverConsoleAlignmentVerification`，41 断言）。
