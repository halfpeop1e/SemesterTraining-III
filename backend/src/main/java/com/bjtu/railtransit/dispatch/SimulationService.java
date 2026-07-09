package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.domain.model.*;
import com.bjtu.railtransit.energy.TractionPhysics;
import com.bjtu.railtransit.signal.service.MovementAuthorityRegistry;
import com.bjtu.railtransit.signal.service.SignalCycleService;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 多列车协同仿真引擎 —— 七状态完整状态机 + ATP/ATO/ATS 多系统协同。
 *
 * 架构层次:
 * Layer 1: ScheduleManager ─ 时刻表管理 (ATS)
 * Layer 2: SafetyEnforcer ─ 安全间隔 + 紧急制动 (ATP)
 * Layer 3: TrainController ─ 列车运行状态机 + 运动学 (ATO)
 * Layer 4: CommandExecutor ─ 调度指令执行
 * Layer 5: Recorder ─ 事件记录与快照
 *
 * 状态转移:
 * DEPOT_WAITING ─(发车时刻到)→ DEPARTING ─(v>0)→ ACCELERATING ─(v≈target)→ CRUISING
 * ↑ ↓
 * │ (接近下站)→ BRAKING
 * │ ↓
 * └──────────────────(到站,停站完成)── DWELLING ←──(v=0, 到站)─────────┘
 *
 * 所有时间单位: 仿真秒 (simulation seconds)
 * 所有距离单位: 米 (meters)
 */
@Service
public class SimulationService {

    private final DispatchEngine dispatchEngine;
    private final LineDataService lineDataService;
    private final EnergyOptimizer energyOptimizer;
    private final TractionPhysics physics;
    private final CommandBus integrationCommandBus;
    private final StatusFusion statusFusion;
    private final OnboardEventHandler onboardEventHandler;
    private final SignalCycleService signalCycleService;
    private final MovementAuthorityRegistry movementAuthorityRegistry;

    private boolean simulationRunning = false;
    private double simulationTimeSeconds = 0;
    private final Map<String, TrainState> trains = new LinkedHashMap<>();
    private LineProfile lineProfile;

    // ── 仿真时钟权威控制 ──
    /** 上次步进的外部系统时间 (ms), 用于防止多客户端重复推进 */
    private long lastStepSystemTimeMs = 0;
    /** 两次步进间的最小外部时间间隔 (ms), 防止多客户端同时步进 */
    private static final long MIN_STEP_INTERVAL_MS = 300;

    // ── 调度指令系统 ──
    /** 每列车当前活跃指令 */
    private final Map<String, SimulationSnapshot.TrainCommand> activeCommands = new LinkedHashMap<>();
    /** 历史指令 */
    private final List<SimulationSnapshot.TrainCommand> commandLog = new ArrayList<>();
    /** 晚点事件日志 */
    private final List<SimulationSnapshot.DelayEvent> delayEventLog = new ArrayList<>();

    // ── 记录系统 ──
    private final List<SimulationSnapshot.TrainPositionPoint> positionHistory = new ArrayList<>();
    private double lastSampleTime = -10;
    private final Map<String, List<SimulationSnapshot.StationArrival>> stationArrivalMap = new LinkedHashMap<>();
    private double totalEnergyKwh = 0;
    private double totalTractionEnergyKwh = 0; // 累计牵引能耗 kWh
    private double totalRegenEnergyKwh = 0; // 累计再生制动回收 kWh
    private double totalAuxEnergyKwh = 0; // 累计辅助能耗 kWh
    private double totalCruisingEnergyKwh = 0; // 累计巡航能耗 kWh

    // ── 仿真日志采集（供能源评估等模块使用）──
    private final List<SimulationLog> simulationLogs = new ArrayList<>();

    public List<SimulationLog> getSimulationLogs() {
        return new ArrayList<>(simulationLogs);
    }

    // ── 物理常量 ──
    private static final double EBRAKE_KMH_PER_S = 4.68; // 紧急制动 km/h/s (≈1.3 m/s²)
    private static final double CRUISE_SPEED = 70.0; // 默认巡航速度 km/h

    public SimulationService(LineDataService lineDataService, DispatchEngine dispatchEngine,
            EnergyOptimizer energyOptimizer, TractionPhysics physics, CommandBus integrationCommandBus,
            StatusFusion statusFusion, OnboardEventHandler onboardEventHandler,
            SignalCycleService signalCycleService, MovementAuthorityRegistry movementAuthorityRegistry) {
        this.lineDataService = lineDataService;
        this.dispatchEngine = dispatchEngine;
        this.energyOptimizer = energyOptimizer;
        this.physics = physics;
        this.integrationCommandBus = integrationCommandBus;
        this.statusFusion = statusFusion;
        this.onboardEventHandler = onboardEventHandler;
        this.signalCycleService = signalCycleService;
        this.movementAuthorityRegistry = movementAuthorityRegistry;
    }

    // ================================================================
    // 初始化
    // ================================================================

    public void startSimulation(int durationSec) {
        // ── 如果已有数据且暂停中: 直接恢复运行 ──
        if (!trains.isEmpty()) {
            simulationRunning = true;
            return;
        }

        simulationTimeSeconds = 0;
        simulationRunning = true;
        trains.clear();
        activeCommands.clear();
        commandLog.clear();
        delayEventLog.clear();
        positionHistory.clear();
        stationArrivalMap.clear();
        totalEnergyKwh = 0;
        totalTractionEnergyKwh = 0;
        totalRegenEnergyKwh = 0;
        totalAuxEnergyKwh = 0;
        totalCruisingEnergyKwh = 0;
        simulationLogs.clear();
        lastSampleTime = -10;

        lineProfile = lineDataService.getLineProfile();
        dispatchEngine.getFlowModel().setEventMultiplier(PassengerFlowModel.EVENT_NONE);
        dispatchEngine.clearLogs();

        // ── 生成时刻表 ──
        double headway = dispatchEngine.getFlowModel().calculateDemandHeadway(0);
        int trainCount = (int) Math.ceil(3600.0 / headway);
        trainCount = Math.max(4, Math.min(8, trainCount));
        dispatchEngine.generateTimetable(lineProfile, trainCount, 0);

        List<LineProfile.Station> stations = lineProfile.getStations();

        // ── 交路分配: 大部分全程, 1/4 用南段小交路增加多样性 ──
        RoutePattern fullRoute = RoutePattern.fullRoute();
        RoutePattern shortSouth = RoutePattern.shortSouth();

        for (int i = 0; i < trainCount; i++) {
            String tid = "T" + (i + 1);
            boolean isUp = true; // 初始全部上行发车
            RoutePattern rp = (i < trainCount - 1 || trainCount <= 2) ? fullRoute : shortSouth;
            String trainNum = DispatchEngine.generateTrainNumber(9, isUp, i + 1);

            TrainState train = new TrainState();
            train.setTrainId(tid);
            train.setTrainName(tid);
            train.setTrainNumber(trainNum);
            train.setDirection(isUp ? "UP" : "DOWN");
            train.setRoutePattern(rp.getPatternId());
            // ── 能源感知: 根据满载率自动选择运行等级 ──
            String opLevel = OperationLevel.NORMAL;
            PassengerFlowModel.TimePeriod period = dispatchEngine.getFlowModel().getCurrentPeriod(0);
            if (period == PassengerFlowModel.TimePeriod.EARLY_MORNING
                    || period == PassengerFlowModel.TimePeriod.NIGHT) {
                opLevel = OperationLevel.ENERGY_SAVE;
            }
            train.setOperationLevel(opLevel);
            train.setSkipNextStation(false);
            train.setTurnbackCount(0);
            train.setPositionMeters(0);
            train.setSpeed(0);
            train.setMaxSpeedLimit(CRUISE_SPEED);
            train.setTargetSpeed(CRUISE_SPEED);

            // 从时刻表获取计划发车时间
            DispatchEngine.TimetableEntry tEntry0 = dispatchEngine.getTimetableEntry(tid, 0);
            double plannedDep = (tEntry0 != null) ? tEntry0.plannedDeparture : i * headway;
            train.setPlannedDepartureFromDepot(plannedDep);

            train.setStatus("DEPOT_WAITING");
            train.setCurrentStationIndex(-1);
            train.setNextStationIndex(rp.getUpStartStationIndex());
            if (!stations.isEmpty()) {
                train.setNextStationKm(stations.get(rp.getUpStartStationIndex()).getKm());
            }

            // 车厢初始化
            List<TrainCar> cars = new ArrayList<>();
            for (int c = 0; c < 6; c++) {
                TrainCar car = new TrainCar();
                car.setCarIndex(c);
                car.setPositionMeters(train.getPositionMeters() - c * 19.0);
                car.setSpeed(0);
                car.setMass(35000.0);
                cars.add(car);
            }
            train.setCars(cars);
            train.setDelaySeconds(0);
            train.setPlannedDwellSeconds(0);
            train.setActualDwellSeconds(0);
            train.setEmergencyBraking(false);
            train.setTrainLengthMeters(6 * 19.0); // 6B编组 114m
            train.setLoadFactor(0);
            train.setStateSource("DISPATCH_ESTIMATED");
            train.setMinDwellSeconds(dispatchEngine.calcMinDwellTime(stations.get(rp.getUpStartStationIndex()).getId()));
            train.setTripId("T" + (i + 1) + "-" + trainNum + "-U1"); // 初始上行第1趟

            trains.put(tid, train);
        }

        // ── 能源优化: 错峰启动 —— 为发车时间叠加微偏移，避免多车同时加速造成供电峰值 ──
        Map<String, Double> offsets = energyOptimizer.computeStaggeredDepartures(trains);
        for (Map.Entry<String, Double> entry : offsets.entrySet()) {
            TrainState ts = trains.get(entry.getKey());
            if (ts != null) {
                double offset = entry.getValue();
                ts.setPlannedDepartureFromDepot(ts.getPlannedDepartureFromDepot() + offset);
            }
        }
    }

    // ================================================================
    // 核心步进: 每步 = 1 仿真秒
    // ================================================================

    public void stepSimulation(int steps) {
        // ── 仿真时钟权威控制: 防止多客户端重复推进 ──
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastStepSystemTimeMs < MIN_STEP_INTERVAL_MS) {
            return; // 忽略过快的重复步进请求
        }
        lastStepSystemTimeMs = nowMs;

        List<LineProfile.Station> stations = lineProfile.getStations();
        int stationCount = stations.size();

        for (int step = 0; step < steps; step++) {
            if (!simulationRunning)
                return;
            simulationTimeSeconds += 1;

            // ═══ 安全优先级顺序 ═══
            // 原则: 安全约束 > 信号约束 > 调度策略 > 客流服务需求 > 能源优化
            // 所有调度指令执行前后都应经过 SafetyGuard 检查

            // Step 1: 总控读取全线状态，并融合独立车载系统的最新可信报告
            fuseOnboardReports();
            // Step 2: ATS / Dispatch 生成运行计划或调度建议
            atsCheckDepartures(stations);

            // Step 3: Signal / ATP 生成移动授权、限速和区段占用约束
            signalCycleService.runCycle(trains.values(), simulationTimeSeconds);

            // Step 4: ATO 根据约束生成牵引、惰行、制动命令
            for (TrainState train : trains.values()) {
                atoTrainStep(train, stations, stationCount);
            }

            // Step 5: 车辆动力学更新状态 (已在 atoTrainStep 中完成运动学)

            // Step 6: SafetyGuard 再次检查超速、越过 MA、间隔不足、异常状态
            safetyGuardCheck(stations);

            // Step 7: 能源优化 (安全约束之后, 不覆盖安全指令)
            energyOptimizeStep();

            // Step 8: 指令执行 + 晚点传播评估
            commandExecuteDelays();

            // Step 9: 写入 SimulationSnapshot 和事件日志
            recordSample();
        }
    }

    /** Backend-owned authoritative clock. Frontends only start, pause or reset it. */
    @Scheduled(fixedRate = 1000)
    public void authoritativeTick() {
        if (simulationRunning) stepSimulation(1);
    }

    public void pauseSimulation() { simulationRunning = false; }
    public double getSimulationTimeSeconds() { return simulationTimeSeconds; }
    public TrainState findTrain(String trainId) { return trains.get(trainId); }

    private void fuseOnboardReports() {
        for (TrainState train : trains.values()) {
            StatusReport report = statusFusion.latest(train.getTrainId());
            if (report == null || report.getTimestampSeconds() <= train.getLastReportTimeSeconds()) continue;
            if (report.getTimestampSeconds() > simulationTimeSeconds + 2) continue;
            // A software HMI may be connected for monitoring without owning train
            // dynamics. Legacy reports without this field retain the old behavior.
            if (Boolean.FALSE.equals(report.getAuthoritative())) continue;

            train.setPositionMeters(report.getPositionMeters());
            train.setSpeed(Math.max(0, report.getSpeedKmh()));
            train.setAcceleration(report.getAccelerationMps2() * 3.6);
            if ("UP".equals(report.getDirection()) || "DOWN".equals(report.getDirection())) {
                train.setDirection(report.getDirection());
            }
            train.setCurrentSegmentId(report.getCurrentSegmentId());
            train.setDelaySeconds(report.getDelaySeconds());
            train.setLastReportTimeSeconds(report.getTimestampSeconds());
            train.setStateSource("ONBOARD_REPORTED");
            if (report.getPhase() != null && !report.getPhase().isBlank()) {
                train.setStatus(report.getPhase());
            }
        }
    }

    // ================================================================
    // Layer 1: ATS 时刻表检查 + 发车控制
    // ================================================================

    private void atsCheckDepartures(List<LineProfile.Station> stations) {
        for (TrainState train : trains.values()) {
            if (!"DEPOT_WAITING".equals(train.getStatus()))
                continue;
            String tid = train.getTrainId();

            // HOLD 指令覆盖发车
            SimulationSnapshot.TrainCommand cmd = activeCommands.get(tid);
            if (cmd != null && "HOLD".equals(cmd.getCommandType())) {
                train.setDelaySeconds(train.getDelaySeconds() + 1);
                continue;
            }

            // 到达发车时刻
            if (simulationTimeSeconds >= train.getPlannedDepartureFromDepot()) {
                train.setStatus("DEPARTING");
                train.setDirection("UP");
                RoutePattern rp = getRoutePattern(train);
                train.setCurrentStationIndex(rp.getUpStartStationIndex());

                if (train.getNextStationIndex() < stations.size()) {
                    train.setNextStationKm(stations.get(train.getNextStationIndex()).getKm());
                }

                int nextIdx = train.getNextStationIndex();
                if (nextIdx > 0 && nextIdx < stations.size()) {
                    double prevKm = stations.get(rp.getUpStartStationIndex()).getKm();
                    double nextKm = stations.get(nextIdx).getKm();
                    double distMeters = Math.abs((nextKm - prevKm) * 1000.0);
                    train.setSectionDistance(distMeters);
                    train.setSectionProgress(0);
                }

                activeCommands.remove(tid);

                SimulationSnapshot.TrainCommand departCmd = new SimulationSnapshot.TrainCommand();
                departCmd.setTrainId(tid);
                departCmd.setCommandType("DEPART");
                departCmd.setReason(String.format("计划发车时刻 %s 到达 (车次 %s, 上行)", formatTime(simulationTimeSeconds),
                        train.getTrainNumber()));
                commandLog.add(departCmd);

                double delay = simulationTimeSeconds - train.getPlannedDepartureFromDepot();
                if (delay > 0)
                    train.setDelaySeconds(delay);
            }
        }
    }

    // ================================================================
    // Layer 2.5: SafetyGuard — 超速/越过MA/异常状态二次检查
    // ================================================================

    private void safetyGuardCheck(List<LineProfile.Station> stations) {
        for (TrainState train : trains.values()) {
            if (!train.occupiesTrack()) continue;

            // 1. 超速检查
            if (train.getSpeed() > train.getMaxSpeedLimit() + 2) {
                if (!train.isEmergencyBraking()) {
                    train.setEmergencyBraking(true);
                    train.setAcceleration(-EBRAKE_KMH_PER_S);
                    SimulationSnapshot.TrainCommand cmd = new SimulationSnapshot.TrainCommand();
                    cmd.setTrainId(train.getTrainId());
                    cmd.setCommandType("EMERGENCY_BRAKE");
                    cmd.setReason(String.format("SafetyGuard: 超速 %.0f > 限速 %.0f km/h",
                            train.getSpeed(), train.getMaxSpeedLimit()));
                    applyCommand(cmd);
                }
            }

            // 2. 越过MA二次检查
            if (!"DWELLING".equals(train.getStatus())
                    && !"TERMINAL_DWELL".equals(train.getStatus())
                    && !"TURNING_BACK".equals(train.getStatus())) {
                List<TrainState> active = trains.values().stream()
                        .filter(TrainState::occupiesTrack)
                        .filter(t -> !t.getTrainId().equals(train.getTrainId()))
                        .sorted(Comparator.comparingDouble(TrainState::getPositionMeters))
                        .collect(Collectors.toList());

                for (TrainState other : active) {
                    int dirSign = train.getDirectionSign();
                    // 只检查同方向前车
                    if (!train.getDirection().equals(other.getDirection())) continue;
                    boolean isAhead = dirSign > 0
                            ? other.getPositionMeters() > train.getPositionMeters()
                            : other.getPositionMeters() < train.getPositionMeters();
                    if (!isAhead) continue;

                    double ma = dispatchEngine.calcMovementAuthority(other, train);
                    if (dispatchEngine.needsEmergencyBrake(train, ma)) {
                        if (!train.isEmergencyBraking()) {
                            train.setEmergencyBraking(true);
                            train.setAcceleration(-EBRAKE_KMH_PER_S);
                            SimulationSnapshot.TrainCommand cmd = new SimulationSnapshot.TrainCommand();
                            cmd.setTrainId(train.getTrainId());
                            cmd.setCommandType("EMERGENCY_BRAKE");
                            cmd.setReason(String.format("SafetyGuard: 即将越过MA (前车%s, MA=%.0fm)",
                                    other.getTrainId(), ma));
                            applyCommand(cmd);
                        }
                        break;
                    }
                }
            }

            // 3. 异常状态检查 (emergencyBraking但不在运行状态)
            if (train.isEmergencyBraking() && train.getSpeed() <= 0
                    && !"DWELLING".equals(train.getStatus())
                    && !"TERMINAL_DWELL".equals(train.getStatus())) {
                train.setSpeed(0);
                train.setAcceleration(0);
                train.setStatus("DWELLING");
            }
        }
    }

    // ================================================================
    // Layer 2: ATP 安全间隔 + 紧急制动
    // ================================================================

    private void atpSafetyEnforce(List<LineProfile.Station> stations) {
        // 获取所有占用线路的列车 (包括DWELLING/TERMINAL_DWELL/TURNING_BACK等)
        // 不参与追踪的: DEPOT_WAITING, FINISHED
        List<TrainState> active = trains.values().stream()
                .filter(TrainState::occupiesTrack)
                .sorted(Comparator.comparingDouble(TrainState::getPositionMeters))
                .collect(Collectors.toList());

        for (int i = 0; i < active.size() - 1; i++) {
            TrainState following = active.get(i);
            TrainState leading = active.get(i + 1);

            // DWELLING/TERMINAL_DWELL/TURNING_BACK 列车仍占用线路, 必须参与MA计算
            // 仅跳过未上线的DEPOT_WAITING列车
            if ("DEPOT_WAITING".equals(following.getStatus())) continue;

            // 计算移动授权 (CBTC Movement Authority) — 方向感知
            double ma = dispatchEngine.calcMovementAuthority(leading, following);
            following.setMovementAuthority(ma);

            // 紧急制动判断: 后车即使立即EB也会侵入MA
            if (dispatchEngine.needsEmergencyBrake(following, ma)) {
                if (!following.isEmergencyBraking()) {
                    following.setEmergencyBraking(true);
                    following.setAcceleration(-EBRAKE_KMH_PER_S);

                    SimulationSnapshot.TrainCommand ebCmd = new SimulationSnapshot.TrainCommand();
                    ebCmd.setTrainId(following.getTrainId());
                    ebCmd.setCommandType("EMERGENCY_BRAKE");
                    ebCmd.setReason(String.format(
                            "ATP紧急制动! 前车%s(状态:%s)位置%.0fm, MA=%.0fm, 本车%.0fm (%dkm/h)",
                            leading.getTrainId(), leading.getStatus(), leading.getPositionMeters(),
                            ma, following.getPositionMeters(), (int) following.getSpeed()));
                    applyCommand(ebCmd);
                }
            } else if (following.isEmergencyBraking()) {
                // 危险解除
                following.setEmergencyBraking(false);
            }

            // 安全间距预警 → SLOW 指令 (仅对运动中的列车)
            if (!"DWELLING".equals(following.getStatus())
                    && !"TERMINAL_DWELL".equals(following.getStatus())
                    && !"TURNING_BACK".equals(following.getStatus())) {
                int dirSign = following.getDirectionSign();
                double gap = dirSign > 0
                        ? leading.getPositionMeters() - following.getPositionMeters()
                        : following.getPositionMeters() - leading.getPositionMeters();
                double safeDist = dispatchEngine.calcSafeDistance(following.getSpeed() / 3.6);

                if (gap < safeDist * 0.8 && gap > 0 && !following.isEmergencyBraking()) {
                    double limitedSpeed = Math.max(25, following.getSpeed() * 0.6);
                    following.setMaxSpeedLimit(limitedSpeed);

                    SimulationSnapshot.TrainCommand slowCmd = activeCommands.get(following.getTrainId());
                    if (slowCmd == null || !"SLOW".equals(slowCmd.getCommandType())) {
                        slowCmd = new SimulationSnapshot.TrainCommand();
                        slowCmd.setTrainId(following.getTrainId());
                        slowCmd.setCommandType("SLOW");
                        slowCmd.setTargetValue(limitedSpeed);
                        slowCmd.setReason(String.format("ATP限速 %.0fkm/h, 间距%.0fm < 安全距%.0fm",
                                limitedSpeed, gap, safeDist));
                        applyCommand(slowCmd);
                    }
                } else if (gap >= safeDist && !following.isEmergencyBraking()) {
                    following.setMaxSpeedLimit(CRUISE_SPEED);
                    SimulationSnapshot.TrainCommand existingSlow = activeCommands.get(following.getTrainId());
                    if (existingSlow != null && "SLOW".equals(existingSlow.getCommandType())) {
                        activeCommands.remove(following.getTrainId());
                    }
                }
            }
        }
    }

    // ================================================================
    // Layer 3: ATO 列车运行状态机
    // ================================================================

    private void atoTrainStep(TrainState train, List<LineProfile.Station> stations, int stationCount) {
        String status = train.getStatus();
        String tid = train.getTrainId();

        if ("FINISHED".equals(status))
            return;

        // ── DEPOT_WAITING: 等待 ATS 发车指令 ──
        if ("DEPOT_WAITING".equals(status)) {
            return; // atsCheckDepartures 处理
        }

        // ── TURNING_BACK: 终端折返 ──
        if ("TURNING_BACK".equals(status)) {
            stepTurningBack(train, stations);
            return;
        }

        // ── TERMINAL_DWELL: 终端站乘降清客 ──
        if ("TERMINAL_DWELL".equals(status)) {
            stepTerminalDwell(train, stations);
            return;
        }

        // ── DWELLING: 站停阶段 ──
        if ("DWELLING".equals(status)) {
            stepDwelling(train, stations);
            return;
        }

        // ── 运行状态: 运动学更新 ──
        int nextIdx = train.getNextStationIndex();

        // 边界检查
        if (!train.isUpDirection()) {
            // 下行: 检查是否已过起点站
            RoutePattern rp = getRoutePattern(train);
            if (nextIdx < rp.getDownEndStationIndex()) {
                train.setStatus("FINISHED");
                activeCommands.remove(tid);
                return;
            }
        } else {
            // 上行: 检查是否已过终点站
            RoutePattern rp = getRoutePattern(train);
            if (nextIdx > rp.getUpEndStationIndex()) {
                train.setStatus("FINISHED");
                activeCommands.remove(tid);
                return;
            }
        }

        // 更新加速度(考虑状态转移和紧急制动)
        updateKinematics(train, stations);

        // 位移 (方向感知)
        double speedMs = train.getSpeed() / 3.6;
        double delta = train.isUpDirection() ? speedMs : -speedMs;
        train.setPositionMeters(train.getPositionMeters() + delta);
        train.setSectionProgress(train.getSectionProgress() + speedMs);

        // 车厢同步
        syncCars(train);

        // 更新当前站索引
        updateStationIndex(train, stations, stationCount);

        // 检测到站
        checkStationArrival(train, stations, stationCount);
    }

    // ── 站停逻辑 ──
    private void stepDwelling(TrainState train, List<LineProfile.Station> stations) {
        String tid = train.getTrainId();
        int currentStationIdx = train.getCurrentStationIndex();

        // HOLD 指令延长停站
        SimulationSnapshot.TrainCommand holdCmd = activeCommands.get(tid);
        boolean held = (holdCmd != null && "HOLD".equals(holdCmd.getCommandType()));

        train.setActualDwellSeconds(train.getActualDwellSeconds() + 1);

        // 停站是否结束？
        boolean dwellComplete = train.getActualDwellSeconds() >= train.getPlannedDwellSeconds();

        if (dwellComplete && !held) {
            RoutePattern rp = getRoutePattern(train);
            boolean isUp = train.isUpDirection();

            // 检查是否为终端站 (需要折返)
            if (isTerminalStation(train, rp, stations)) {
                // ── 先进入 TERMINAL_DWELL (乘降/清客/确认) ──
                train.setStatus("TERMINAL_DWELL");
                train.setPlannedDwellSeconds(DispatchEngine.TERMINAL_DWELL_SEC);
                train.setActualDwellSeconds(0);
                train.setMinDwellSeconds(DispatchEngine.DWELL_MIN_TERMINAL);

                SimulationSnapshot.TrainCommand tdCmd = new SimulationSnapshot.TrainCommand();
                tdCmd.setTrainId(tid);
                tdCmd.setCommandType("TERMINAL_DWELL");
                tdCmd.setReason(String.format("到达%s(终端站), 乘降清客 %.0fs",
                        stations.get(currentStationIdx).getName(), DispatchEngine.TERMINAL_DWELL_SEC));
                commandLog.add(tdCmd);
                return;
            }

            // ── 非终端站发车 ──
            departFromStation(train, stations, currentStationIdx);
        } else if (held) {
            train.setDelaySeconds(train.getDelaySeconds() + 1);
        }
    }

    // ── TERMINAL_DWELL 逻辑 ──
    private void stepTerminalDwell(TrainState train, List<LineProfile.Station> stations) {
        String tid = train.getTrainId();
        train.setActualDwellSeconds(train.getActualDwellSeconds() + 1);

        if (train.getActualDwellSeconds() >= train.getPlannedDwellSeconds()) {
            // 终端站乘降完成 → 进入折返
            train.setStatus("TURNING_BACK");
            train.setActualDwellSeconds(0);
            double turnbackTime = dispatchEngine.calcTurnbackTime(simulationTimeSeconds);
            train.setPlannedDwellSeconds(turnbackTime);
            train.setMinDwellSeconds(DispatchEngine.DWELL_MIN_TERMINAL);

            SimulationSnapshot.TrainCommand tbCmd = new SimulationSnapshot.TrainCommand();
            tbCmd.setTrainId(tid);
            tbCmd.setCommandType("TURN_BACK");
            tbCmd.setReason(String.format("终端站乘降完成, 开始折返换端 %.0fs", turnbackTime));
            commandLog.add(tbCmd);
        }
    }

    /** 非终端站发车 */
    private void departFromStation(TrainState train, List<LineProfile.Station> stations, int currentStationIdx) {
        String tid = train.getTrainId();
        boolean isUp = train.isUpDirection();

        int nextIdx;
        if (isUp) {
            nextIdx = currentStationIdx + 1;
        } else {
            nextIdx = currentStationIdx - 1;
        }

        if (nextIdx < 0 || nextIdx >= stations.size()) {
            train.setStatus("FINISHED");
            activeCommands.remove(tid);
            return;
        }

        // 记录实际出发时间
        train.setActualDepartureFromStation(simulationTimeSeconds);

        // 计算到下一站的区间参数
        train.setNextStationIndex(nextIdx);
        train.setNextStationKm(stations.get(nextIdx).getKm());
        double prevKm = stations.get(currentStationIdx).getKm();
        double nextKm = stations.get(nextIdx).getKm();
        train.setSectionDistance(Math.abs((nextKm - prevKm) * 1000.0));
        train.setSectionProgress(0);

        train.setStatus("DEPARTING");

        // 应用限速
        SimulationSnapshot.TrainCommand slowCmd = activeCommands.get(tid);
        if (slowCmd != null && "SLOW".equals(slowCmd.getCommandType())) {
            train.setMaxSpeedLimit(slowCmd.getTargetValue());
        } else {
            train.setMaxSpeedLimit(CRUISE_SPEED);
            activeCommands.remove(tid);
        }

        // 发车日志
        SimulationSnapshot.TrainCommand departCmd = new SimulationSnapshot.TrainCommand();
        departCmd.setTrainId(tid);
        departCmd.setCommandType("DEPART");
        departCmd.setReason(String.format("从 %s 发车, 停站 %.0fs → 下一站 %s",
                stations.get(currentStationIdx).getName(), train.getActualDwellSeconds(),
                stations.get(nextIdx).getName()));
        commandLog.add(departCmd);
    }

    // ── 折返逻辑 ──
    private void stepTurningBack(TrainState train, List<LineProfile.Station> stations) {
        String tid = train.getTrainId();
        train.setActualDwellSeconds(train.getActualDwellSeconds() + 1);

        if (train.getActualDwellSeconds() >= train.getPlannedDwellSeconds()) {
            // 折返完成: 切换方向 + 反向发车
            boolean wasUp = train.isUpDirection();
            train.setDirection(wasUp ? "DOWN" : "UP");
            train.setTurnbackCount(train.getTurnbackCount() + 1);

            int currentStationIdx = train.getCurrentStationIndex();
            int nextIdx;

            if (wasUp) {
                // 上行→下行: 从当前站返回, 下一站是索引-1
                nextIdx = currentStationIdx - 1;
            } else {
                // 下行→上行: 下一站是索引+1
                nextIdx = currentStationIdx + 1;
            }

            if (nextIdx < 0 || nextIdx >= stations.size()) {
                train.setStatus("FINISHED");
                activeCommands.remove(tid);
                return;
            }

            // 更新车次号 (折返后方向改变)
            int oldSeq = Integer.parseInt(train.getTrainId().substring(1));
            train.setTrainNumber(DispatchEngine.generateTrainNumber(9, train.isUpDirection(), oldSeq));

            train.setNextStationIndex(nextIdx);
            train.setNextStationKm(stations.get(nextIdx).getKm());
            double curKm = stations.get(currentStationIdx).getKm();
            double nextKm = stations.get(nextIdx).getKm();
            train.setSectionDistance(Math.abs((nextKm - curKm) * 1000.0));
            train.setSectionProgress(0);
            train.setMaxSpeedLimit(CRUISE_SPEED);
            train.setStatus("DEPARTING");

            // 清除旧指令
            activeCommands.remove(tid);

            SimulationSnapshot.TrainCommand tbDoneCmd = new SimulationSnapshot.TrainCommand();
            tbDoneCmd.setTrainId(tid);
            tbDoneCmd.setCommandType("DEPART");
            tbDoneCmd.setReason(String.format("折返完成 → %s方向, 发车至 %s",
                    train.isUpDirection() ? "上行(郭公庄→国图)" : "下行(国图→郭公庄)",
                    stations.get(nextIdx).getName()));
            commandLog.add(tbDoneCmd);
        }
    }

    /** 判断当前站是否为该交路的终端站 */
    private boolean isTerminalStation(TrainState train, RoutePattern rp, List<LineProfile.Station> stations) {
        int idx = train.getCurrentStationIndex();
        if (train.isUpDirection()) {
            return idx >= rp.getUpEndStationIndex();
        } else {
            return idx <= rp.getDownEndStationIndex();
        }
    }

    // ── 运动学更新 (含状态转移, 双向感知, 运行等级感知) ──
    private void updateKinematics(TrainState train, List<LineProfile.Station> stations) {
        String status = train.getStatus();
        double speed = train.getSpeed();
        int nextIdx = train.getNextStationIndex();
        boolean isUp = train.isUpDirection();

        // ── 根据运行等级获取参数 ──
        OperationLevel opLevel = getOperationLevel(train);
        double cruiseSpeed = opLevel.getCruiseSpeedKmh();
        double accelKmhPerS = opLevel.getAccelMs2() * 3.6; // 转换为 km/h/s
        double decelMs2 = opLevel.getDecelMs2();

        // ── 赶点区间加速: 检查活跃SPEED_UP指令的恢复等级 ──
        SimulationSnapshot.TrainCommand speedCmd = activeCommands.get(train.getTrainId());
        if (speedCmd != null && "SPEED_UP".equals(speedCmd.getCommandType()) && speedCmd.getTargetValue() > 0) {
            int recoverLevel = Math.min((int) speedCmd.getTargetValue(), DispatchEngine.RECOVERY_CRITICAL);
            double boost = DispatchEngine.RECOVERY_SPEED_BOOST[recoverLevel];
            if (boost > 0)
                cruiseSpeed += boost;
        }

        // 紧急制动优先
        if (train.isEmergencyBraking()) {
            double newSpeed = Math.max(0, speed - EBRAKE_KMH_PER_S);
            train.setSpeed(newSpeed);
            train.setAcceleration(-EBRAKE_KMH_PER_S);
            if (newSpeed <= 0) {
                train.setStatus("DWELLING");
                train.setEmergencyBraking(false);
            }
            return;
        }

        double posKm = train.getPositionMeters() / 1000.0;
        double maxSpeed = Math.min(cruiseSpeed, train.getMaxSpeedLimit());

        // ── 甩站逻辑: 当 skipNextStation=true 且接近下一站时, 不制动直接通过 ──
        if (train.isSkipNextStation() && nextIdx >= 0 && nextIdx < stations.size()) {
            double nextStationKm = stations.get(nextIdx).getKm();
            double distToStationKm = isUp ? (nextStationKm - posKm) : (posKm - nextStationKm);
            double distToStationM = distToStationKm * 1000.0;

            // 已通过该站 (位置已过站超过300m)
            if (distToStationM < -50) {
                train.setSkipNextStation(false); // 甩站完成, 继续运行
                // 跳到下一个站
                if (isUp) {
                    train.setNextStationIndex(Math.min(nextIdx + 1, stations.size() - 1));
                } else {
                    train.setNextStationIndex(Math.max(nextIdx - 1, 0));
                }
                train.setNextStationKm(stations.get(train.getNextStationIndex()).getKm());
                updateStationIndex(train, stations, stations.size());

                // 记录甩站指令
                SimulationSnapshot.TrainCommand skipCmd = new SimulationSnapshot.TrainCommand();
                skipCmd.setTrainId(train.getTrainId());
                skipCmd.setCommandType("SKIP_STATION");
                skipCmd.setReason(String.format("甩站通过 %s (快车模式)", stations.get(nextIdx).getName()));
                commandLog.add(skipCmd);
            }
        }

        // ── 判断是否需要制动进站 (方向感知, 甩站时不制动) ──
        if (!train.isSkipNextStation() && nextIdx >= 0 && nextIdx < stations.size()) {
            double nextStationKm = stations.get(nextIdx).getKm();
            double distToStationKm = isUp ? (nextStationKm - posKm) : (posKm - nextStationKm);
            double distToStationM = distToStationKm * 1000.0;

            // 已经到达/超过站台: 强制进站
            if (distToStationM <= 0) {
                train.setSpeed(0);
                train.setAcceleration(0);
                arriveAtStation(train, stations, nextIdx);
                return;
            }

            // 制动距离
            double brakingDistance = (speed / 3.6) * (speed / 3.6) / (2 * decelMs2);

            if (distToStationM <= Math.max(brakingDistance * 1.05, 100)) {
                if (!"BRAKING".equals(status)) {
                    train.setStatus("BRAKING");
                }

                double requiredDecelMs2 = (speed / 3.6) * (speed / 3.6) / (2 * Math.max(distToStationM, 5.0));
                double requiredDecelKmh = Math.min(decelMs2 * 3.6, requiredDecelMs2 * 3.6);
                double newSpeed = Math.max(0, speed - Math.max(requiredDecelKmh, 0.3));
                train.setSpeed(newSpeed);
                train.setAcceleration(-requiredDecelKmh);

                if (newSpeed <= 0.5 && distToStationM <= 20) {
                    train.setSpeed(0);
                    train.setAcceleration(0);
                    arriveAtStation(train, stations, nextIdx);
                }
                return;
            }
        }

        // ── 正常加速/巡航 ──
        double newSpeed = speed;
        double accel;

        if (speed < maxSpeed) {
            accel = Math.min(accelKmhPerS, maxSpeed - speed);
            newSpeed = speed + accel;
            if ("BRAKING".equals(status) || "ARRIVING".equals(status)) {
                train.setStatus("ACCELERATING");
            }
        } else {
            accel = 0;
            newSpeed = maxSpeed;
            if (!"CRUISING".equals(status) && !"DEPARTING".equals(status) && !"ACCELERATING".equals(status)) {
                train.setStatus("CRUISING");
            }
        }

        train.setSpeed(newSpeed);
        train.setAcceleration(accel);

        if ("DEPARTING".equals(status) && newSpeed > 5) {
            train.setStatus("ACCELERATING");
        }
    }

    // ── 到站处理 ──
    private void arriveAtStation(TrainState train, List<LineProfile.Station> stations, int stationIndex) {
        train.setSpeed(0);
        train.setAcceleration(0);
        train.setStatus("DWELLING");
        train.setCurrentStationIndex(stationIndex);

        // 记录实际到站时刻
        train.setActualArrivalAtStation(simulationTimeSeconds);

        // 计算计划停站时间
        int stationId = stations.get(stationIndex).getId();
        double plannedDwell = dispatchEngine.calcDwellTime(stationId, simulationTimeSeconds);

        // SPEED_UP 指令: 多级缩停站 (基于targetValue编码的恢复等级)
        String tid = train.getTrainId();
        SimulationSnapshot.TrainCommand speedUpCmd = activeCommands.get(tid);
        if (speedUpCmd != null && "SPEED_UP".equals(speedUpCmd.getCommandType())) {
            int recoverLevel = Math.min(Math.max((int) speedUpCmd.getTargetValue(), 0),
                    DispatchEngine.RECOVERY_CRITICAL);
            double dwellCut = DispatchEngine.RECOVERY_DWELL_CUTS[recoverLevel];
            // 硬约束: 缩短后不得低于最小停站时间
            double minDwell = dispatchEngine.calcMinDwellTime(stationId);
            plannedDwell = Math.max(minDwell, plannedDwell - dwellCut);

            // L3/L4: 不再自动强制甩站, 改为生成调度建议 (需人工确认)
            if (recoverLevel >= DispatchEngine.RECOVERY_AGGRESSIVE) {
                SimulationSnapshot.TrainCommand suggestCmd = new SimulationSnapshot.TrainCommand();
                suggestCmd.setTrainId(tid);
                suggestCmd.setCommandType("SUGGEST_SKIP");
                suggestCmd.setReason(String.format("严重晚点建议: 甩站通过 %s (需调度员确认)",
                        stations.get(stationIndex).getName()));
                suggestCmd.setIssuedTime(simulationTimeSeconds);
                suggestCmd.setStatus("PENDING");
                commandLog.add(suggestCmd);
            }
        }

        train.setPlannedDwellSeconds(plannedDwell);
        train.setActualDwellSeconds(0);

        // 记录到站事件
        stationArrivalMap.putIfAbsent(tid, new ArrayList<>());
        SimulationSnapshot.StationArrival arrival = new SimulationSnapshot.StationArrival();
        arrival.setTrainId(tid);
        arrival.setStationIndex(stationIndex);
        arrival.setStationName(stations.get(stationIndex).getName());
        arrival.setArrivalTimeSeconds(simulationTimeSeconds);
        arrival.setDepartureTimeSeconds(simulationTimeSeconds + plannedDwell);
        arrival.setDwellSeconds(plannedDwell);
        // ── 从时刻表获取计划时刻，计算偏差 ──
        DispatchEngine.TimetableEntry tEntry = dispatchEngine.getTimetableEntry(tid, stationIndex);
        if (tEntry != null) {
            arrival.setPlannedArrivalSeconds(tEntry.plannedArrival);
            arrival.setPlannedDepartureSeconds(tEntry.plannedDeparture);
            arrival.setPlannedDwellSeconds(tEntry.plannedDwell);
            arrival.setArrivalDeviation(simulationTimeSeconds - tEntry.plannedArrival);
            arrival.setDepartureDeviation((simulationTimeSeconds + plannedDwell) - tEntry.plannedDeparture);
        } else {
            arrival.setPlannedArrivalSeconds(simulationTimeSeconds);
            arrival.setPlannedDepartureSeconds(simulationTimeSeconds + plannedDwell);
            arrival.setArrivalDeviation(0);
            arrival.setDepartureDeviation(0);
        }
        stationArrivalMap.get(tid).add(arrival);

        // 到站指令
        SimulationSnapshot.TrainCommand arrCmd = new SimulationSnapshot.TrainCommand();
        arrCmd.setTrainId(tid);
        arrCmd.setCommandType("ARRIVE");
        arrCmd.setReason(String.format("到达 %s, 停站 %.0fs",
                stations.get(stationIndex).getName(), plannedDwell));
        commandLog.add(arrCmd);

        // 清除速度限制指令(到站了恢复)
        SimulationSnapshot.TrainCommand existingSlow = activeCommands.get(tid);
        if (existingSlow != null && "SLOW".equals(existingSlow.getCommandType())) {
            activeCommands.remove(tid);
        }
    }

    // ── 到站检测 (双向感知) ──
    private void checkStationArrival(TrainState train, List<LineProfile.Station> stations, int stationCount) {
        int nextIdx = train.getNextStationIndex();
        if (nextIdx >= stationCount || nextIdx < 0)
            return;
        if ("DWELLING".equals(train.getStatus()) || "DEPOT_WAITING".equals(train.getStatus()))
            return;

        double posKm = train.getPositionMeters() / 1000.0;
        double stationKm = stations.get(nextIdx).getKm();
        boolean isUp = train.isUpDirection();
        double distToStationM = isUp ? (stationKm - posKm) * 1000.0 : (posKm - stationKm) * 1000.0;

        // 到站判定: 位置已过站且已减速
        if (distToStationM <= 0 && train.getSpeed() <= 1.0) {
            if (!"DWELLING".equals(train.getStatus())) {
                arriveAtStation(train, stations, nextIdx);
            }
        }
    }

    // ── 辅助 (双向感知) ──
    private void updateStationIndex(TrainState train, List<LineProfile.Station> stations, int stationCount) {
        double posKm = train.getPositionMeters() / 1000.0;
        boolean isUp = train.isUpDirection();
        int newIdx = -1;
        for (int i = stationCount - 1; i >= 0; i--) {
            if (posKm >= stations.get(i).getKm() - 0.02) {
                newIdx = i;
                break;
            }
        }
        // 下行时, 当前站是已过的最远站, 不是最小的那个
        if (!isUp && newIdx >= 0) {
            // 查找position左侧最近的车站
            newIdx = -1;
            for (int i = 0; i < stationCount; i++) {
                if (posKm <= stations.get(i).getKm() + 0.02) {
                    newIdx = i;
                    break;
                }
            }
        }
        train.setCurrentStationIndex(newIdx);
    }

    private void syncCars(TrainState train) {
        if (train.getCars() != null) {
            for (TrainCar car : train.getCars()) {
                // 下行时车厢在列车后方(更大位置)
                double offset = car.getCarIndex() * train.getCarLength();
                car.setPositionMeters(train.isUpDirection()
                        ? train.getPositionMeters() - offset
                        : train.getPositionMeters() + offset);
                car.setSpeed(train.getSpeed());
            }
        }
    }

    // ================================================================
    // Layer 4: 能源优化 —— 惰行窗口 + 再生协同 + 峰值监控
    // ================================================================

    /** 最近一次能源优化结果 (供快照使用) */
    private EnergyOptimizer.EnergyOptimizationResult lastEnergyResult;

    private void energyOptimizeStep() {
        // 每20仿真秒评估一次 (避免每步都计算)
        if (simulationTimeSeconds % 20 != 0)
            return;

        lastEnergyResult = energyOptimizer.evaluate(trains, simulationTimeSeconds, dispatchEngine);

        // ── 策略1: 应用惰行窗口 ──
        for (EnergyOptimizer.CoastingDecision cd : lastEnergyResult.coastingOpportunities) {
            TrainState t = trains.get(cd.trainId);
            if (t != null && t.getSpeed() > EnergyOptimizer.COAST_MIN_SPEED) {
                // 惰行: 通过降低目标速度来实现滑行效果
                // 不直接切断牵引，而是将目标巡航速度设为节能模式的55km/h
                t.setTargetSpeed(EnergyOptimizer.COAST_CRUISE_SPEED);

                // 记录惰行指令
                SimulationSnapshot.TrainCommand coastCmd = new SimulationSnapshot.TrainCommand();
                coastCmd.setTrainId(cd.trainId);
                coastCmd.setCommandType("SPEED_UP"); // 复用SPEED_UP类型表示惰行节能
                coastCmd.setTargetValue(EnergyOptimizer.COAST_CRUISE_SPEED);
                coastCmd.setReason(cd.reason);
                coastCmd.setIssuedTime(simulationTimeSeconds);
                coastCmd.setStatus("EXECUTING");
                commandLog.add(coastCmd);
            }
        }

        // ── 策略2: 再生制动协同 ──
        for (EnergyOptimizer.RegenCoordination rc : lastEnergyResult.regenCoordinations) {
            TrainState absorbing = trains.get(rc.absorbingTrainId);
            if (absorbing != null && (absorbing.getAcceleration() >= 0 || "CRUISING".equals(absorbing.getStatus())
                    || "DWELLING".equals(absorbing.getStatus()))) {
                // 优先让附近可吸收列车恢复/维持正常加速（不用限速）
                absorbing.setMaxSpeedLimit(OperationLevel.normal().getCruiseSpeedKmh());

                SimulationSnapshot.TrainCommand regenCmd = new SimulationSnapshot.TrainCommand();
                regenCmd.setTrainId(rc.absorbingTrainId);
                regenCmd.setCommandType("SPEED_UP");
                regenCmd.setTargetValue(rc.recoverableEnergyKw);
                regenCmd.setReason(rc.reason);
                regenCmd.setIssuedTime(simulationTimeSeconds);
                regenCmd.setStatus("EXECUTING");
                commandLog.add(regenCmd);
            }
        }

        // ── 策略3: 峰值功率控制 ──
        if ("danger".equals(lastEnergyResult.peakRiskLevel)
                && lastEnergyResult.tractionCount > EnergyOptimizer.MAX_SIMULTANEOUS_TRACTION) {
            // 同时牵引列车过多 → 对晚出发的列车施加微延迟
            List<TrainState> accelTrains = trains.values().stream()
                    .filter(t -> "ACCELERATING".equals(t.getStatus()) || "DEPARTING".equals(t.getStatus()))
                    .filter(t -> !"FINISHED".equals(t.getStatus()))
                    .sorted(Comparator.comparingDouble(TrainState::getPlannedDepartureFromDepot))
                    .collect(Collectors.toList());

            // 超过阈值的列车施加SLOW限速
            for (int i = EnergyOptimizer.MAX_SIMULTANEOUS_TRACTION; i < accelTrains.size(); i++) {
                TrainState t = accelTrains.get(i);
                if (t.getMaxSpeedLimit() > 40) {
                    t.setMaxSpeedLimit(40);

                    SimulationSnapshot.TrainCommand peakCmd = new SimulationSnapshot.TrainCommand();
                    peakCmd.setTrainId(t.getTrainId());
                    peakCmd.setCommandType("SLOW");
                    peakCmd.setTargetValue(40);
                    peakCmd.setReason(String.format("峰值功率控制: 同时牵引%d列 > %d列上限, 对%s限速40km/h",
                            lastEnergyResult.tractionCount, EnergyOptimizer.MAX_SIMULTANEOUS_TRACTION, t.getTrainId()));
                    peakCmd.setIssuedTime(simulationTimeSeconds);
                    peakCmd.setStatus("EXECUTING");
                    applyCommand(peakCmd);
                }
            }
        }

        // ── 策略4: 运行等级自适应 ──
        for (TrainState t : trains.values()) {
            if ("FINISHED".equals(t.getStatus()) || "DEPOT_WAITING".equals(t.getStatus()))
                continue;
            if ("ACCELERATING".equals(t.getStatus()) || "CRUISING".equals(t.getStatus())
                    || "DWELLING".equals(t.getStatus())) {
                String recommendedLevel = energyOptimizer.recommendOperationLevel(
                        Math.max(0, lastEnergyResult.currentLoadFactor),
                        EnergyOptimizer.TimePeriod.fromSimTime(simulationTimeSeconds));

                if (OperationLevel.ENERGY_SAVE.equals(recommendedLevel)
                        && !OperationLevel.ENERGY_SAVE.equals(t.getOperationLevel())
                        && !"DWELLING".equals(t.getStatus())) {
                    t.setOperationLevel(OperationLevel.ENERGY_SAVE);
                    t.setTargetSpeed(OperationLevel.energySaving().getCruiseSpeedKmh());
                }
            }
        }
    }

    /** 获取最新能源优化结果 */
    public EnergyOptimizer.EnergyOptimizationResult getLastEnergyResult() {
        return lastEnergyResult;
    }

    // ================================================================
    // Layer 5: 指令执行 + 晚点传播
    // ================================================================

    private void commandExecuteDelays() {
        // 晚点全量评估 (每30秒评估)
        if (simulationTimeSeconds % 30 == 0) {
            DispatchEngine.DelayResult delayResult = dispatchEngine.evaluateAllDelays(
                    new ArrayList<>(trains.values()), simulationTimeSeconds);

            // ── 追踪本轮评估中收到SPEED_UP的列车──
            Set<String> speedUpTrains = new java.util.HashSet<>();

            for (SimulationSnapshot.TrainCommand cmd : delayResult.commands) {
                if ("SPEED_UP".equals(cmd.getCommandType())) {
                    speedUpTrains.add(cmd.getTrainId());
                    // 直接覆盖旧指令 (恢复等级可能变化)
                    applyCommand(cmd);
                }
            }

            // ── 清理已恢复列车的SPEED_UP (本轮未收到新SPEED_UP的列车) ──
            for (Map.Entry<String, SimulationSnapshot.TrainCommand> entry : activeCommands.entrySet()) {
                if ("SPEED_UP".equals(entry.getValue().getCommandType())
                        && !speedUpTrains.contains(entry.getKey())) {
                    activeCommands.remove(entry.getKey());
                }
            }

            for (SimulationSnapshot.DelayEvent evt : delayResult.events) {
                // 去重：同一列车同一类型的事件只保留最新的
                delayEventLog.removeIf(
                        e -> e.getTrainId().equals(evt.getTrainId()) && e.getEventType().equals(evt.getEventType()));
                delayEventLog.add(evt);
            }
            dispatchEngine.logDelayEvents(delayResult.events);
        }

        // 晚点传播评估 (仅对运动中且占用线路的列车)
        List<TrainState> activeMoving = trains.values().stream()
                .filter(TrainState::occupiesTrack)
                .filter(t -> !"DWELLING".equals(t.getStatus()))
                .filter(t -> !"TERMINAL_DWELL".equals(t.getStatus()))
                .filter(t -> !"TURNING_BACK".equals(t.getStatus()))
                .sorted(Comparator.comparingDouble(TrainState::getPositionMeters))
                .collect(Collectors.toList());

        DispatchEngine.DelayResult propResult = dispatchEngine.evaluateDelayPropagation(activeMoving,
                simulationTimeSeconds);
        for (SimulationSnapshot.TrainCommand cmd : propResult.commands) {
            applyCommand(cmd);
        }
        for (SimulationSnapshot.DelayEvent evt : propResult.events) {
            delayEventLog.add(evt);
        }
        dispatchEngine.logDelayEvents(propResult.events);
    }

    // ================================================================
    // Layer 5: 记录
    // ================================================================

    private void recordSample() {
        // 位置采样 (每5秒, 更密集以支持双向运行图)
        if (simulationTimeSeconds - lastSampleTime >= 5) {
            for (TrainState t : trains.values()) {
                if ("FINISHED".equals(t.getStatus()) || "DEPOT_WAITING".equals(t.getStatus()))
                    continue;
                SimulationSnapshot.TrainPositionPoint pt = new SimulationSnapshot.TrainPositionPoint();
                pt.setTrainId(t.getTrainId());
                pt.setTimeSeconds(simulationTimeSeconds);
                pt.setPositionKm(t.getPositionMeters() / 1000.0);
                pt.setDirection(t.getDirection());
                positionHistory.add(pt);
            }
            lastSampleTime = simulationTimeSeconds;
        }

        // 每步采集仿真日志 + 能耗估算
        for (TrainState t : trains.values()) {
            if ("FINISHED".equals(t.getStatus()) || "DEPOT_WAITING".equals(t.getStatus()))
                continue;

            // ── 构建 SimulationLog ──
            SimulationLog log = new SimulationLog();
            log.setTimestamp(Math.round(simulationTimeSeconds * 1000)); // ms
            log.setSpeed(t.getSpeed() / 3.6); // km/h → m/s
            log.setPosition(t.getPositionMeters());
            log.setDirection(t.isUpDirection() ? "up" : "down");
            log.setLoadWeight(0); // 乘客重量后续可从客流模型获取
            log.setEmergencyBrake(t.isEmergencyBraking());
            log.setAvailableTractionCount(t.getCarCount());
            log.setAvailableBrakeCount(t.getCarCount());
            log.setFaultSpeedLimit(0);
            log.setDrivingMode(t.isEmergencyBraking() ? "EUM" : "CM");

            // 根据位置估算当前Seg编号
            log.setCurrentSegId(estimateSegId(t.getPositionMeters()));

            // 解析 trainId: "T1" → 1
            try {
                log.setTrainId(Integer.parseInt(t.getTrainId().substring(1)));
            } catch (NumberFormatException e) {
                log.setTrainId(0);
            }

            // ── 基于 TB/T 1407.2 牵引物理模型计算能耗 ──
            double accelMs2 = t.getAcceleration() / 3.6;
            double mass = t.getCarCount() * 35000.0;
            double resistanceN = physics.totalResistanceForce(t);
            double inertiaForceN = physics.inertiaForce(mass, accelMs2);

            if (t.getSpeed() <= 0.5) {
                // 停站中
                log.setTractiveBrakeCmd("coast");
                log.setTractiveBrakePercent(0);
                log.setTractionForce(0);
                log.setBrakeForce(0);
            } else if (accelMs2 > 0.05) {
                // 牵引工况 — Davis阻力 + 惯性力 + 坡道阻力
                double tractionForceN = resistanceN + Math.max(0, inertiaForceN);
                log.setTractiveBrakeCmd("traction");
                log.setTractiveBrakePercent(Math.min(100, Math.abs(accelMs2) / 1.0 * 100));
                log.setTractionForce(tractionForceN);
                log.setBrakeForce(0);

                double stepKwh = physics.tractionStepEnergyKwh(t);
                totalEnergyKwh += stepKwh;
                totalTractionEnergyKwh += stepKwh;
            } else if (accelMs2 < -0.05) {
                // 制动工况 — 再生制动回收
                double brakeForceN = Math.abs(inertiaForceN);
                log.setTractiveBrakeCmd("brake");
                log.setTractiveBrakePercent(Math.min(100, Math.abs(accelMs2) / 1.0 * 100));
                log.setTractionForce(0);
                log.setBrakeForce(brakeForceN);

                totalRegenEnergyKwh += physics.regenStepEnergyKwh(t, 0.65);
            } else {
                // 惰行/巡航 — 仅克服阻力 (Davis + 坡道)
                log.setTractiveBrakeCmd("coast");
                log.setTractiveBrakePercent(0);
                log.setTractionForce(0);
                log.setBrakeForce(0);

                if (t.getSpeed() > 5) {
                    double stepKwh = physics.cruisingStepEnergyKwh(t);
                    totalEnergyKwh += stepKwh;
                    totalCruisingEnergyKwh += stepKwh;
                }
            }

            // ── 辅助能耗 (空调/照明, 在线列车均计入) ──
            totalAuxEnergyKwh += physics.auxiliaryStepEnergyKwh(t.getCarCount());

            simulationLogs.add(log);
        }
    }

    /** 根据位置估算所属Seg编号（按约1000m一个Seg粗略划分） */
    private int estimateSegId(double positionMeters) {
        return (int) (positionMeters / 1000) + 1;
    }

    // ================================================================
    // 指令管理
    // ================================================================

    private void applyCommand(SimulationSnapshot.TrainCommand cmd) {
        cmd.setIssuedTime(simulationTimeSeconds);
        cmd.setStatus("EXECUTING");
        activeCommands.put(cmd.getTrainId(), cmd);
        commandLog.add(cmd);
    }

    private List<TrainState> getActiveSortedByPosition() {
        return trains.values().stream()
                .filter(t -> !"FINISHED".equals(t.getStatus()) && t.getPositionMeters() > 0)
                .sorted(Comparator.comparingDouble(TrainState::getPositionMeters))
                .collect(Collectors.toList());
    }

    /** 获取列车对应的交路模式 */
    private RoutePattern getRoutePattern(TrainState train) {
        String pid = train.getRoutePattern();
        if (RoutePattern.SHORT_S.equals(pid))
            return RoutePattern.shortSouth();
        if (RoutePattern.SHORT_N.equals(pid))
            return RoutePattern.shortNorth();
        if (RoutePattern.EXPRESS.equals(pid))
            return RoutePattern.fullRoute();
        return RoutePattern.fullRoute();
    }

    /** 获取列车当前的运行等级参数 */
    private OperationLevel getOperationLevel(TrainState train) {
        String level = train.getOperationLevel();
        if (OperationLevel.ENERGY_SAVE.equals(level))
            return OperationLevel.energySaving();
        if (OperationLevel.EXPRESS.equals(level))
            return OperationLevel.express();
        if (OperationLevel.SLOW.equals(level))
            return OperationLevel.slow();
        return OperationLevel.normal();
    }

    /**
     * 应用调度策略指令 (CHANGE_LEVEL, SKIP_STATION, SHORT_TURN)
     */
    public void applyStrategy(String trainId, String strategyType, double targetValue) {
        TrainState train = trains.get(trainId);
        if (train == null)
            return;

        SimulationSnapshot.TrainCommand cmd = new SimulationSnapshot.TrainCommand();
        cmd.setTrainId(trainId);
        cmd.setCommandType(strategyType);

        switch (strategyType) {
            case "CHANGE_LEVEL":
                String level = targetValue >= 1 ? "EXPRESS" : targetValue >= 0 ? "ENERGY_SAVE" : "SLOW";
                train.setOperationLevel(level);
                if (OperationLevel.EXPRESS.equals(level)) {
                    // 快车模式自动启用甩站下一站
                    train.setSkipNextStation(true);
                }
                cmd.setReason(String.format("切换运行等级 → %s", getOperationLevel(train).getLevelName()));
                break;

            case "SKIP_STATION":
                train.setSkipNextStation(true);
                cmd.setTargetValue(targetValue);
                cmd.setReason(String.format("甩站指令: 下一站(%s)不停车通过",
                        train.getNextStationIndex() >= 0
                                && train.getNextStationIndex() < lineProfile.getStations().size()
                                        ? lineProfile.getStations().get(train.getNextStationIndex()).getName()
                                        : "未知"));
                break;

            case "SHORT_TURN":
                // 强制在指定站折返
                int turnStationIdx = (int) targetValue;
                train.setRoutePattern(RoutePattern.SHORT_S); // 临时切换为短交路
                cmd.setTargetValue(targetValue);
                cmd.setReason(String.format("指令折返: 在站#%d 强制折返", turnStationIdx));
                break;

            case "RESUME_NORMAL":
                train.setOperationLevel(OperationLevel.NORMAL);
                train.setSkipNextStation(false);
                cmd.setReason("恢复正常运营等级");
                break;
        }

        applyCommand(cmd);
    }

    // ================================================================
    // 快照
    // ================================================================

    public SimulationSnapshot getSnapshot() {
        SimulationSnapshot snapshot = new SimulationSnapshot();
        snapshot.setSimulationTime(simulationTimeSeconds);
        snapshot.setIntegrationCommands(integrationCommandBus.all());
        snapshot.setPendingManualConfirmations(integrationCommandBus.pendingConfirmations());
        snapshot.setOnboardReports(statusFusion.reports());
        snapshot.setOnboardEvents(onboardEventHandler.events());
        snapshot.setProtocolAdapterStatus(Map.of(
                "signal", "CODEC_READY", "vehicle", "CODEC_READY",
                "driverDesk", "SKELETON", "vision", "SKELETON",
                "maSource", movementAuthorityRegistry.getSource(),
                "maGeneration", movementAuthorityRegistry.getGeneration()));
        snapshot.setSimTimeFormatted(formatTime(simulationTimeSeconds));
        snapshot.setRunning(simulationRunning);

        // 列车状态
        List<TrainState> trainList = new ArrayList<>();
        int activeCount = 0;
        for (TrainState t : trains.values()) {
            trainList.add(copyTrainState(t));
            if (!"FINISHED".equals(t.getStatus()))
                activeCount++;
        }
        snapshot.setTotalTrains(trains.size());
        snapshot.setActiveTrains(activeCount);
        snapshot.setTrains(trainList);

        // 到站记录
        List<SimulationSnapshot.StationArrival> arrivals = new ArrayList<>();
        for (List<SimulationSnapshot.StationArrival> list : stationArrivalMap.values()) {
            arrivals.addAll(list);
        }
        snapshot.setStationArrivals(arrivals);

        // 车头时距 (方向感知)
        List<TrainState> sortedTrains = trains.values().stream()
                .filter(TrainState::occupiesTrack)
                .sorted(Comparator.comparingDouble(TrainState::getPositionMeters))
                .collect(Collectors.toList());
        List<SimulationSnapshot.HeadwayInfo> headwayList = new ArrayList<>();
        for (int i = 0; i < sortedTrains.size() - 1; i++) {
            TrainState following = sortedTrains.get(i);
            TrainState leading = sortedTrains.get(i + 1);

            int dirSign = following.getDirectionSign();
            double gap = dirSign > 0
                    ? leading.getPositionMeters() - following.getPositionMeters()
                    : following.getPositionMeters() - leading.getPositionMeters();
            double safeDist = dispatchEngine.calcSafeDistance(following.getSpeed() / 3.6);

            SimulationSnapshot.HeadwayInfo hw = new SimulationSnapshot.HeadwayInfo();
            hw.setFromTrainId(following.getTrainId());
            hw.setToTrainId(leading.getTrainId());
            hw.setDistanceMeters(gap);
            hw.setSafetyDistanceMeters(safeDist);
            hw.setTimeSeconds(following.getSpeed() > 0 ? Math.abs(gap) / (following.getSpeed() / 3.6) : 999);
            hw.setStatus(gap < safeDist * 0.5 ? "DANGER" : gap < safeDist * 0.8 ? "WARNING" : gap < safeDist ? "CAUTION" : "SAFE");
            headwayList.add(hw);
        }
        snapshot.setHeadways(headwayList);

        // 指令
        List<SimulationSnapshot.TrainCommand> cmds = new ArrayList<>(activeCommands.values());
        for (int i = commandLog.size() - 1; i >= 0 && cmds.size() < 40; i--) {
            SimulationSnapshot.TrainCommand c = commandLog.get(i);
            if (cmds.stream().noneMatch(
                    x -> x.getTrainId().equals(c.getTrainId()) && x.getCommandType().equals(c.getCommandType()))) {
                cmds.add(c);
            }
        }
        snapshot.setCommands(cmds);

        // 晚点事件
        snapshot.setDelayEvents(new ArrayList<>(delayEventLog));

        // 客流
        snapshot.setPassengerFlow(dispatchEngine.getFlowModel().getFlowInfo(simulationTimeSeconds));

        // 调度汇总
        DispatchEngine.DispatchResult dr = dispatchEngine.evaluateDispatch(simulationTimeSeconds, activeCount);
        SimulationSnapshot.DispatchInfo di = new SimulationSnapshot.DispatchInfo();
        di.setRecommendedHeadway(dr.recommendedHeadway);
        di.setActualHeadway(dr.recommendedHeadway);
        di.setOnlineTrains(activeCount);
        di.setMaxAvailableTrains(PassengerFlowModel.AVAILABLE_TRAINS);
        di.setRequiredTrains(dr.requiredTrains);
        di.setFleetSufficient(dr.fleetSufficient);
        di.setDispatchMode(dr.dispatchMode);
        snapshot.setDispatchInfo(di);

        // ── 计划运行图点位 (所有列车全时刻表 → 前端画计划线) ──
        if (dispatchEngine.getTimetable() != null) {
            List<SimulationSnapshot.TrainPositionPoint> planned = new ArrayList<>();
            for (Map.Entry<String, Map<Integer, DispatchEngine.TimetableEntry>> trainEntry : dispatchEngine
                    .getTimetable().entrySet()) {
                String tid = trainEntry.getKey();
                for (DispatchEngine.TimetableEntry te : trainEntry.getValue().values()) {
                    SimulationSnapshot.TrainPositionPoint pp = new SimulationSnapshot.TrainPositionPoint();
                    pp.setTrainId(tid);
                    pp.setTimeSeconds(te.plannedArrival);
                    pp.setPositionKm(te.stationKm);
                    planned.add(pp);
                    // 发车点
                    SimulationSnapshot.TrainPositionPoint dp = new SimulationSnapshot.TrainPositionPoint();
                    dp.setTrainId(tid);
                    dp.setTimeSeconds(te.plannedDeparture);
                    dp.setPositionKm(te.stationKm);
                    planned.add(dp);
                }
            }
            snapshot.setPlannedDiagramPoints(planned);
        }

        // ── 计划偏差汇总 ──
        List<SimulationSnapshot.StationArrival> deviations = new ArrayList<>();
        for (List<SimulationSnapshot.StationArrival> list : stationArrivalMap.values()) {
            for (SimulationSnapshot.StationArrival a : list) {
                if (a.getPlannedArrivalSeconds() > 0) {
                    deviations.add(a);
                }
            }
        }
        snapshot.setPlanDeviations(deviations);

        snapshot.setPositionHistory(new ArrayList<>(positionHistory));
        snapshot.setTotalEnergyKwh(totalEnergyKwh);

        // ── 供电分区状态 ──
        snapshot.setPowerSections(PowerSectionStatus.createDefaultSections());

        // ── 移动授权列表 ──
        List<SimulationSnapshot.MovementAuthorityInfo> maList = new ArrayList<>();
        for (TrainState t : trains.values()) {
            if (!t.occupiesTrack()) continue;
            SimulationSnapshot.MovementAuthorityInfo maInfo = new SimulationSnapshot.MovementAuthorityInfo();
            maInfo.setTrainId(t.getTrainId());
            maInfo.setDirection(t.getDirection());
            maInfo.setAuthorityEndMeters(t.getMovementAuthority());
            maInfo.setPermittedSpeedKmh(t.getMaxSpeedLimit());
            maList.add(maInfo);
        }
        snapshot.setMovementAuthorities(maList);

        // ── 能源优化数据 ──
        if (lastEnergyResult != null) {
            SimulationSnapshot.EnergyOptimizationInfo eoi = new SimulationSnapshot.EnergyOptimizationInfo();
            eoi.setCurrentPeakKw(lastEnergyResult.currentPeakKw);
            eoi.setPowerSupplyThresholdKw(lastEnergyResult.powerSupplyThresholdKw);
            eoi.setPeakRiskLevel(lastEnergyResult.peakRiskLevel);
            eoi.setTractionCount(lastEnergyResult.tractionCount);
            eoi.setMaxTractionCount(lastEnergyResult.maxTractionCount);
            eoi.setTotalRecoverableEnergyKw(lastEnergyResult.totalRecoverableEnergyKw);
            eoi.setRegenCoordinationCount(lastEnergyResult.regenCoordinations.size());
            eoi.setCoastingOpportunityCount(lastEnergyResult.coastingOpportunities.size());
            eoi.setRecommendations(new ArrayList<>(lastEnergyResult.recommendations));
            eoi.setCurrentLoadFactor(lastEnergyResult.currentLoadFactor);
            // ── 实时累计能耗 ──
            eoi.setTotalTractionEnergyKwh(totalTractionEnergyKwh);
            eoi.setTotalRegenEnergyKwh(totalRegenEnergyKwh);
            eoi.setNetEnergyKwh(totalTractionEnergyKwh - totalRegenEnergyKwh);
            eoi.setSimulationTimeSeconds(simulationTimeSeconds);
            eoi.setAuxiliaryEnergyKwh(totalAuxEnergyKwh);
            eoi.setCruisingEnergyKwh(totalCruisingEnergyKwh);
            snapshot.setEnergyOptimization(eoi);
        }

        return snapshot;
    }

    public DispatchPlan getDispatchPlan() {
        DispatchPlan plan = new DispatchPlan();
        plan.setLineId(lineProfile.getLineId());
        plan.setPlanId("PLAN-" + lineProfile.getLineId());
        double headway = dispatchEngine.getFlowModel().calculateDemandHeadway(simulationTimeSeconds);
        plan.setHeadwaySeconds((int) headway);
        plan.setTrainCount(trains.size());

        // 上线列车数约束: N_required = ceil(T_cycle / headway)
        int required = dispatchEngine.getFlowModel().calculateRequiredTrains(headway);
        int available = PassengerFlowModel.AVAILABLE_TRAINS;
        plan.setRequiredTrainCount(required);
        plan.setAvailableTrainCount(available);
        plan.setStartStationId(1);  // 郭公庄
        plan.setEndStationId(13);   // 国家图书馆
        plan.setRoutePattern("FULL");
        plan.setOperationMode(required > available ? "EMERGENCY" : "NORMAL");

        List<DispatchPlan.ScheduleEntry> schedule = new ArrayList<>();
        for (int i = 0; i < trains.size(); i++) {
            DispatchPlan.ScheduleEntry entry = new DispatchPlan.ScheduleEntry();
            entry.setTrainId("T" + (i + 1));
            entry.setStationIndex(0);
            entry.setPlannedDepartureTime(String.valueOf((int) (i * headway)));
            schedule.add(entry);
        }
        plan.setSchedule(schedule);
        return plan;
    }

    // ================================================================
    // Helpers
    // ================================================================

    private TrainState copyTrainState(TrainState src) {
        TrainState c = new TrainState();
        c.setTrainId(src.getTrainId());
        c.setTrainName(src.getTrainName());
        c.setTrainNumber(src.getTrainNumber());
        c.setDirection(src.getDirection());
        c.setRoutePattern(src.getRoutePattern());
        c.setOperationLevel(src.getOperationLevel());
        c.setSkipNextStation(src.isSkipNextStation());
        c.setTurnbackCount(src.getTurnbackCount());
        c.setPositionMeters(src.getPositionMeters());
        c.setSpeed(src.getSpeed());
        c.setStatus(src.getStatus());
        c.setCurrentStationIndex(src.getCurrentStationIndex());
        c.setNextStationIndex(src.getNextStationIndex());
        c.setNextStationKm(src.getNextStationKm());
        c.setCarCount(src.getCarCount());
        c.setCarLength(src.getCarLength());
        c.setDelaySeconds(src.getDelaySeconds());
        c.setPlannedDwellSeconds(src.getPlannedDwellSeconds());
        c.setActualDwellSeconds(src.getActualDwellSeconds());
        c.setPlannedDepartureFromDepot(src.getPlannedDepartureFromDepot());
        c.setPlannedArrivalAtStation(src.getPlannedArrivalAtStation());
        c.setActualArrivalAtStation(src.getActualArrivalAtStation());
        c.setActualDepartureFromStation(src.getActualDepartureFromStation());
        c.setMaxSpeedLimit(src.getMaxSpeedLimit());
        c.setTargetSpeed(src.getTargetSpeed());
        c.setAcceleration(src.getAcceleration());
        c.setSectionDistance(src.getSectionDistance());
        c.setSectionProgress(src.getSectionProgress());
        c.setEmergencyBraking(src.isEmergencyBraking());
        c.setMovementAuthority(src.getMovementAuthority());
        c.setTripId(src.getTripId());
        c.setTrainLengthMeters(src.getTrainLengthMeters());
        c.setLoadFactor(src.getLoadFactor());
        c.setActiveCommand(src.getActiveCommand());
        c.setStateSource(src.getStateSource());
        c.setMinDwellSeconds(src.getMinDwellSeconds());

        if (src.getCars() != null) {
            c.setCars(src.getCars().stream().map(car -> {
                TrainCar cc = new TrainCar();
                cc.setCarIndex(car.getCarIndex());
                cc.setPositionMeters(car.getPositionMeters());
                cc.setSpeed(car.getSpeed());
                cc.setMass(car.getMass());
                return cc;
            }).collect(Collectors.toList()));
        }
        return c;
    }

    private String formatTime(double seconds) {
        int h = (int) (seconds / 3600);
        int m = (int) ((seconds % 3600) / 60);
        int s = (int) (seconds % 60);
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    public boolean isRunning() {
        return simulationRunning;
    }

    /** 重置仿真 (停止并清空所有状态) */
    public void resetSimulation() {
        simulationRunning = false;
        simulationTimeSeconds = 0;
        trains.clear();
        activeCommands.clear();
        commandLog.clear();
        delayEventLog.clear();
        stationArrivalMap.clear();
        positionHistory.clear();
        totalEnergyKwh = 0;
        totalTractionEnergyKwh = 0;
        totalRegenEnergyKwh = 0;
        totalAuxEnergyKwh = 0;
        totalCruisingEnergyKwh = 0;
        simulationLogs.clear();
        lastSampleTime = -10;
        lastEnergyResult = null;
        dispatchEngine.clearLogs();
        movementAuthorityRegistry.clear();
    }
}
