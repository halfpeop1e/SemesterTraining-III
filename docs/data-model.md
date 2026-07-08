# 数据模型

## 成员一：总控调度模块（Phase 1 已实现）

### LineProfile（线路剖面）
- lineId, lineName, direction, totalLengthKm
- List<Station>: id, name, code, km (里程km)
- 数据来源：老师提供的「线路数据(1).xls」车站表 + 站台表

### StationGeo（车站地理坐标）
- id, name, code, longitude, latitude, km
- 数据来源：CPTOND-2025 参考 + OSM 补充（WGS84坐标，精度约50m）

### TrainState（列车运行状态）
- trainId, trainName
- positionMeters（距线路起点米数）
- speed（km/h）
- status: STOPPED / RUNNING / ARRIVING / DEPARTING / FINISHED
- currentStationIndex, nextStationIndex
- departureTime, nextStationKm

### SimulationSnapshot（仿真快照）
- simulationTime（秒）, simTimeFormatted（HH:MM:SS）
- totalTrains, activeTrains
- trains: List<TrainState>
- headways: List<HeadwayInfo>（fromTrainId, toTrainId, distanceMeters, timeSeconds, status: SAFE/WARNING/DANGER）
- commands: List<TrainCommand>

### TrainCommand（调度指令）
- trainId, commandType: DEPART / HOLD / SLOW / ARRIVE / TERMINATE
- targetValue, reason

### DispatchPlan（发车计划）
- lineId, headwaySeconds（默认360秒 = 6分钟）
- departureIntervalSeconds, trainCount
- schedule: List<ScheduleEntry>

---

## 后续待扩展

TODO:
- 信号机模型（Signal）
- 限速区段模型（SpeedLimit）
- 坡度区段模型（SlopeSection）
- 能耗模型（EnergyModel）
- 计轴区段模型（AxleCounter）
