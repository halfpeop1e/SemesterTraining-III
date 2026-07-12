package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.common.SimulationWebSocketHandler;
import com.bjtu.railtransit.domain.model.*;
import com.bjtu.railtransit.energy.TractionPhysics;
import com.bjtu.railtransit.energy.TractionPowerSupplyService;
import com.bjtu.railtransit.energy.TrainNetworkService;
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
    private final PredictiveController predictiveController;
    private final MultiParticleSimulationService multiParticleSimulationService;
    private final TractionPhysics physics;
    private final CommandBus integrationCommandBus;
    private final StatusFusion statusFusion;
    private final OnboardEventHandler onboardEventHandler;
    private final SignalCycleService signalCycleService;
    private final MovementAuthorityRegistry movementAuthorityRegistry;
    private final BlendingBrakeService blendingBrakeService;
    private final SimulationWebSocketHandler webSocketHandler;
    private final TractionPowerSupplyService powerSupply;
    private final TrainNetworkService networkService;
    private final RedisDataBus redisDataBus;

    private boolean simulationRunning = false;
    private double simulationTimeSeconds = 0;
    private final Map<String, TrainState> trains = new LinkedHashMap<>();
    /** Last StatusFusion revision consumed for each onboard-driven train. */
    private final Map<String, Long> fusedOnboardReportRevisions = new LinkedHashMap<>();
    /**
     * Last ATS departure authorization per direction; keeps depot departures evenly
     * spaced.
     */
    private final Map<String, Double> lastDepartureAuthorizationByDirection = new LinkedHashMap<>();
    private LineProfile lineProfile;

    // ── CBTC 执行层: 牵引/制动系统状态 ──
    private final Map<String, TractionSystemState> tractionStates = new LinkedHashMap<>();
    private final Map<String, BrakingSystemState> brakeStates = new LinkedHashMap<>();

    // ── 多质点编组: 每列车的6节车厢独立状态 ──
    private final Map<String, List<TrainCar>> consistMap = new LinkedHashMap<>();

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
    /** Minimum scheduled headway for the teaching simulation (same direction). */
    private static final double MIN_DEPARTURE_HEADWAY_SECONDS = 120.0;
    /**
     * Leading train must have cleared this distance before the next depot
     * departure.
     */
    private static final double MIN_DEPARTURE_CLEARANCE_METERS = 350.0;

    public SimulationService(LineDataService lineDataService, DispatchEngine dispatchEngine,
            EnergyOptimizer energyOptimizer, PredictiveController predictiveController,
            MultiParticleSimulationService multiParticleSimulationService,
            TractionPhysics physics, CommandBus integrationCommandBus,
            StatusFusion statusFusion, OnboardEventHandler onboardEventHandler,
            SignalCycleService signalCycleService, MovementAuthorityRegistry movementAuthorityRegistry,
            BlendingBrakeService blendingBrakeService,
            SimulationWebSocketHandler webSocketHandler,
            TractionPowerSupplyService powerSupply,
            TrainNetworkService networkService,
            RedisDataBus redisDataBus) {
        this.lineDataService = lineDataService;
        this.dispatchEngine = dispatchEngine;
        this.energyOptimizer = energyOptimizer;
        this.predictiveController = predictiveController;
        this.multiParticleSimulationService = multiParticleSimulationService;
        this.physics = physics;
        this.integrationCommandBus = integrationCommandBus;
        this.statusFusion = statusFusion;
        this.onboardEventHandler = onboardEventHandler;
        this.signalCycleService = signalCycleService;
        this.movementAuthorityRegistry = movementAuthorityRegistry;
        this.blendingBrakeService = blendingBrakeService;
        this.webSocketHandler = webSocketHandler;
        this.powerSupply = powerSupply;
        this.networkService = networkService;
        this.redisDataBus = redisDataBus;
    }

    // ================================================================
    // 初始化
    // ================================================================

    public void startSimulation() {
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
        fusedOnboardReportRevisions.clear();
        lastDepartureAuthorizationByDirection.clear();
        tractionStates.clear();
        brakeStates.clear();
        lastSampleTime = -10;

        // ── 加载线路数据 ──
        lineProfile = lineDataService.getLineProfile();
        dispatchEngine.getFlowModel().setEventMultiplier(PassengerFlowModel.EVENT_NONE);
        dispatchEngine.clearLogs();

        // 列车由独立车载仿真器（HMI）通过 POST /api/dispatch/report/status 上报后动态创建
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

            // Step 3.5: ATP 安全间隔强制 —— 软性限速（间距不足时主动降速，避免只等紧急制动）
            atpSafetyEnforce(stations);

            // Step 4: ATO 根据约束生成牵引、惰行、制动命令
            for (TrainState train : trains.values()) {
                atoTrainStep(train, stations, stationCount);
            }

            // Step 4.5: ATC (Automatic Traction Control) —— 牵引/制动能力约束 + 电空状态同步
            atcConstraintCheck();

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

    /**
     * Backend-owned authoritative clock. Frontends only start, pause or reset it.
     */
    @Scheduled(fixedRate = 1000)
    public void authoritativeTick() {
        if (!simulationRunning)
            return;
        stepSimulation(1);
        // 每步后通过 WebSocket 实时推送快照
        try {
            webSocketHandler.broadcast(getSnapshot());
        } catch (Exception e) {
            // 广播失败不影响仿真继续
        }
        // 同步发布到 Redis 数据总线（供外部系统订阅）
        try {
            publishToRedisDataBus();
        } catch (Exception e) {
            // Redis 不可用时静默降级
        }
    }

    /** 将当前仿真状态同步到 Redis 数据总线 */
    private void publishToRedisDataBus() {
        if (!redisDataBus.isConnected())
            return;
        // 发布完整快照
        redisDataBus.publishSnapshot(getSnapshot());
        // 逐列车发布状态
        for (TrainState t : trains.values()) {
            redisDataBus.putTrainState(t.getTrainId(), t);
        }
        // 发布能耗数据
        redisDataBus.putEnergyData(totalTractionEnergyKwh, totalRegenEnergyKwh,
                totalAuxEnergyKwh, totalCruisingEnergyKwh, coastingSavedKwh, totalEnergyKwh);
        // 发布移动授权
        redisDataBus.replaceMA(movementAuthorityRegistry.snapshot());
        // 发布活跃指令
        for (TrainCommand cmd : integrationCommandBus.all()) {
            if (!Set.of("COMPLETED", "REJECTED", "SUPERSEDED").contains(cmd.getStatus())) {
                redisDataBus.putCommand(cmd.getCommandId(), cmd);
            }
        }
    }

    public void pauseSimulation() {
        simulationRunning = false;
        // 广播最终快照，让前端获知 running=false
        try {
            webSocketHandler.broadcast(getSnapshot());
        } catch (Exception e) {
            // 广播失败不影响
        }
    }

    public double getSimulationTimeSeconds() {
        return simulationTimeSeconds;
    }

    public TrainState findTrain(String trainId) {
        return trains.get(trainId);
    }

    /**
     * 融合车载上报：对已有列车更新位置/速度；对新上报 trainId 动态创建列车。
     * 列车由独立车载仿真器（HMI）通过上报首次出现时创建，信号/能源/安全等算法照常运行。
     */
    private void fuseOnboardReports() {
        List<LineProfile.Station> stations = lineProfile.getStations();

        // ── 先处理 statusFusion 中的新上报，动态创建未注册的列车 ──
        for (StatusReport report : statusFusion.reports()) {
            String tid = report.getTrainId();
            if (trains.containsKey(tid))
                continue; // 已有列车，走下方更新逻辑

            // 动态创建列车（基于 HMI 上报的信息）
            TrainState train = new TrainState();
            train.setTrainId(tid);
            train.setTrainName(tid);
            train.setTrainNumber(tid);
            boolean isUp = "UP".equals(report.getDirection());
            train.setDirection(isUp ? "UP" : "DOWN");
            train.setRoutePattern("FULL");
            train.setOperationLevel(OperationLevel.NORMAL);
            train.setSkipNextStation(false);
            train.setTurnbackCount(0);
            train.setPositionMeters(report.getPositionMeters());
            train.setSpeed(report.getSpeedKmh());
            train.setAcceleration(report.getAccelerationMps2() * 3.6);
            train.setMaxSpeedLimit(CRUISE_SPEED);
            train.setTargetSpeed(CRUISE_SPEED);
            train.setStatus(report.getPhase() != null ? report.getPhase() : "DEPOT_WAITING");

            // 根据上报的 currentStationId 确定车站索引
            int startStationIdx = parseStationIndex(report.getCurrentStationId());
            int nextStationIdx = parseStationIndex(report.getNextStationId());
            if (startStationIdx < 0)
                startStationIdx = isUp ? 0 : stations.size() - 1;
            if (nextStationIdx < 0)
                nextStationIdx = isUp ? startStationIdx + 1 : startStationIdx - 1;
            train.setCurrentStationIndex(startStationIdx);
            train.setNextStationIndex(nextStationIdx);
            if (nextStationIdx >= 0 && nextStationIdx < stations.size()) {
                train.setNextStationKm(stations.get(nextStationIdx).getKm());
            }

            train.setPlannedDepartureFromDepot(simulationTimeSeconds);
            train.setDelaySeconds(0);
            train.setPlannedDwellSeconds(dispatchEngine.calcMinDwellTime(
                    stations.get(startStationIdx >= 0 ? startStationIdx : 0).getId()));
            train.setActualDwellSeconds(0);
            train.setActualArrivalAtStation(simulationTimeSeconds);
            train.setPlannedArrivalAtStation(simulationTimeSeconds);
            train.setEmergencyBraking(false);
            train.setTrainLengthMeters(6 * 19.0);
            train.setLoadFactor(0);
            train.setStateSource("ONBOARD_REPORTED");
            train.setActiveCommand("WAITING_DEPARTURE");
            train.setLastReportTimeSeconds(-1);
            train.setMinDwellSeconds(30);
            train.setTripId(tid + "-U1");
            train.setLineId(report.getLineId() != null ? report.getLineId() : "BJ-L9");

            // ── 生成该列车的完整时刻表 ──
            dispatchEngine.generateSingleTrainTimetable(lineProfile, tid, startStationIdx >= 0 ? startStationIdx : 0,
                    isUp, simulationTimeSeconds);

            // 车厢初始化（多质点模型）
            List<TrainCar> cars = multiParticleSimulationService.initConsistWithLoad(0.5);
            multiParticleSimulationService.initCarPositions(cars,
                    train.getPositionMeters(), train.getSpeed());
            train.setCars(cars);
            consistMap.put(tid, cars);

            // CBTC 执行层: 初始化牵引/制动系统状态
            TractionSystemState ts = TractionSystemState.createDefault(tid, 6);
            BrakingSystemState bs = BrakingSystemState.createDefault(tid, 6);
            train.setTractionState(ts);
            train.setBrakeState(bs);
            tractionStates.put(tid, ts);
            brakeStates.put(tid, bs);

            trains.put(tid, train);
        }

        // ── 对所有已注册列车，融合最新车载报告 ──
        for (TrainState train : trains.values()) {
            StatusReport report = statusFusion.latest(train.getTrainId());
            long revision = statusFusion.revision(train.getTrainId());
            if (report == null || revision == 0
                    || revision <= fusedOnboardReportRevisions.getOrDefault(train.getTrainId(), 0L))
                continue;

            // StatusReport.timestampSeconds belongs to the vehicle's local playback
            // timeline. It can legitimately lead, lag or reset relative to the
            // control-centre clock, so do not use it as a cross-system freshness gate.
            // The server-assigned revision above is the ordering authority.

            // 车载上报的位置/速度覆盖后端仿真值（仅HMI驱动列车）
            if ("ONBOARD_REPORTED".equals(train.getStateSource())) {
                train.setPositionMeters(report.getPositionMeters());
                train.setSpeed(Math.max(0, report.getSpeedKmh()));
                train.setAcceleration(report.getAccelerationMps2() * 3.6);
                if ("UP".equals(report.getDirection()) || "DOWN".equals(report.getDirection())) {
                    train.setDirection(report.getDirection());
                }
                train.setCurrentSegmentId(report.getCurrentSegmentId());
            }
            train.setDelaySeconds(report.getDelaySeconds());
            // P2 安全字段灌入（协议 A6/A9/A10）
            train.setFaultSpeedLimitKmh(report.getFaultSpeedLimitKmh());
            train.setPositionLost(report.isPositionLost());
            train.setIntegrityLost(report.isIntegrityLost());
            // Keep this field on the control-centre timebase for network-health
            // calculations; the raw vehicle timestamp remains available in report.
            train.setLastReportTimeSeconds(simulationTimeSeconds);
            fusedOnboardReportRevisions.put(train.getTrainId(), revision);
            // 同步到 Redis 数据总线
            redisDataBus.putStatusReport(train.getTrainId(), report);

            // 追踪车站索引变化（到站检测）
            int reportedStationIdx = parseStationIndex(report.getCurrentStationId());
            if (reportedStationIdx >= 0 && reportedStationIdx != train.getCurrentStationIndex()) {
                int oldIdx = train.getCurrentStationIndex();
                train.setCurrentStationIndex(reportedStationIdx);
                // 记录实际到站时刻（兼容HMI上报的DWELL/DWELLING/TERMINAL_DWELL）
                if (isHmiDwellPhase(report.getPhase())) {
                    train.setActualArrivalAtStation(simulationTimeSeconds);
                }
            }
            int reportedNextIdx = parseStationIndex(report.getNextStationId());
            if (reportedNextIdx >= 0 && reportedNextIdx < stations.size()) {
                train.setNextStationIndex(reportedNextIdx);
                train.setNextStationKm(stations.get(reportedNextIdx).getKm());
            }

            if (report.getPhase() != null && !report.getPhase().isBlank()) {
                String oldStatus = train.getStatus();
                String reportPhase = report.getPhase();
                // 仿真已调度发车后，不允许HMI上报的DEPOT_WAITING覆盖当前状态
                if (!"DEPOT_WAITING".equals(reportPhase) || "DEPOT_WAITING".equals(oldStatus)) {
                    train.setStatus(reportPhase);
                }
                // 到站时记录实际到达时间和更新计划到站时间（兼容HMI上报的DWELL/DWELLING）
                if (isHmiDwellPhase(report.getPhase())
                        && !report.getPhase().equals(oldStatus)) {
                    train.setActualArrivalAtStation(simulationTimeSeconds);
                    DispatchEngine.TimetableEntry tEntry = dispatchEngine.getTimetableEntry(
                            train.getTrainId(), train.getCurrentStationIndex());
                    if (tEntry != null) {
                        train.setPlannedArrivalAtStation(tEntry.plannedArrival);
                    }
                    // 补录到站记录到stationArrivalMap（HMI列车跳过atoTrainStep，需在此记录）
                    recordHmiStationArrival(train, stations);
                }

                // A departure command is complete once the onboard system has
                // actually left READY_TO_DEPART. This prevents ATS from
                // repeatedly superseding the same command every simulation tick.
                if (!"READY_TO_DEPART".equals(reportPhase) && !"PAUSED".equals(reportPhase)) {
                    integrationCommandBus.completeOpenCommands(train.getTrainId(), "DEPART", simulationTimeSeconds);
                }
            }

            // ── 车载上报车厢级状态 (多质点) ──
            if (report.getCarStatuses() != null && !report.getCarStatuses().isEmpty()) {
                // 预留: 车载系统逐车厢上报质量/载客率/车钩力给中控
                // 中控据此更新车厢质量参数, 牵引/制动系统据此调整出力分配
                var carSnaps = new ArrayList<MultiParticleSimulationService.CarStatusSnapshot>();
                for (StatusReport.CarStatus cs : report.getCarStatuses()) {
                    var snap = new MultiParticleSimulationService.CarStatusSnapshot();
                    snap.carIndex = cs.carIndex;
                    snap.carType = cs.carType;
                    snap.motored = cs.motored;
                    snap.curbMass = cs.curbMass;
                    snap.occupiedMass = cs.occupiedMass;
                    snap.passengerLoadRatio = cs.passengerLoadRatio;
                    snap.positionMeters = cs.positionMeters;
                    snap.speedKmh = cs.speedKmh;
                    snap.accelerationKmhs = cs.accelerationKmhs;
                    snap.couplerForceKN = cs.couplerForceKN;
                    snap.gradeResistance = cs.gradeResistance;
                    snap.health = cs.health;
                    carSnaps.add(snap);
                }
                List<TrainCar> syncedCars = multiParticleSimulationService.fromSnapshots(carSnaps);
                consistMap.put(train.getTrainId(), syncedCars);
                train.setCars(syncedCars);
            }
        }
    }

    // ================================================================
    // Layer 1: ATS 时刻表检查 + 发车控制
    // ================================================================

    /**
     * ATS 发车控制 + 停站管理。
     * - DEPOT_WAITING: 原有自动发车逻辑
     * - ONBOARD_REPORTED: 根据时刻表 + DEPART 指令管理发车授权和停站时间
     */
    private void atsCheckDepartures(List<LineProfile.Station> stations) {
        for (TrainState train : trains.values()) {
            String tid = train.getTrainId();
            String status = train.getStatus();

            // HOLD 指令覆盖
            SimulationSnapshot.TrainCommand holdCmd = activeCommands.get(tid);
            if (holdCmd != null && "HOLD".equals(holdCmd.getCommandType())) {
                train.setDelaySeconds(train.getDelaySeconds() + 1);
                continue;
            }

            // ── DEPOT_WAITING: 原有自动发车 ──
            if ("DEPOT_WAITING".equals(status)) {
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
                        double distMeters = Math.abs((stations.get(nextIdx).getKm()
                                - stations.get(rp.getUpStartStationIndex()).getKm()) * 1000.0);
                        train.setSectionDistance(distMeters);
                        train.setSectionProgress(0);
                    }
                    activeCommands.remove(tid);
                    commandLog.add(buildDepartLog(tid));
                    // 通过CommandBus下发DEPART指令，HMI通过 /api/onboard/{trainId}/snapshot 获取
                    integrationCommandBus.issue(tid, "DEPART", 0, "调度授权发车",
                            100, "ATS", simulationTimeSeconds);
                    train.setDelaySeconds(Math.max(0, simulationTimeSeconds - train.getPlannedDepartureFromDepot()));
                }
                continue;
            }

            // ── ONBOARD_REPORTED 列车: 时刻表驱动的发车控制 ──
            if (!"ONBOARD_REPORTED".equals(train.getStateSource()))
                continue;

            DispatchEngine.TimetableEntry tEntry = dispatchEngine.getTimetableEntry(tid,
                    train.getCurrentStationIndex());

            // READY_TO_DEPART: 检查是否可以发车
            if ("READY_TO_DEPART".equals(status)) {
                boolean timeToDepart = tEntry != null && simulationTimeSeconds >= tEntry.plannedDeparture;
                boolean hasDepartCmd = hasDepartCommand(tid);

                if ((timeToDepart || hasDepartCmd) && canAuthorizeOnboardDeparture(train)) {
                    removeDepartCommand(tid);
                    train.setActualDepartureFromStation(simulationTimeSeconds);
                    int nsIdx = train.getNextStationIndex();
                    if (nsIdx >= 0 && nsIdx < stations.size()) {
                        train.setNextStationKm(stations.get(nsIdx).getKm());
                    }
                    commandLog.add(buildDepartLog(tid));
                    issueDepartIfNeeded(tid, "ATS 发车授权：满足计划时刻、追踪间隔与前车净距");
                    train.setActiveCommand("DEPART");
                } else if (timeToDepart || hasDepartCmd) {
                    train.setActiveCommand("WAITING_DEPARTURE_HEADWAY");
                }
                continue;
            }

            // DWELLING / TERMINAL_DWELL: 停站管理
            if ("DWELLING".equals(status) || "TERMINAL_DWELL".equals(status)) {
                train.setActualDwellSeconds(train.getActualDwellSeconds() + 1);

                if (tEntry != null) {
                    train.setPlannedDwellSeconds(tEntry.plannedDwell);
                    train.setPlannedDepartureFromDepot(tEntry.plannedDeparture);
                }

                boolean minDwellMet = train.getActualDwellSeconds() >= train.getMinDwellSeconds();
                boolean plannedTimeReached = tEntry != null && simulationTimeSeconds >= tEntry.plannedDeparture;
                boolean hasDepartCmd = hasDepartCommand(tid);

                if (minDwellMet && (plannedTimeReached || hasDepartCmd)) {
                    if (canAuthorizeOnboardDeparture(train)) {
                        removeDepartCommand(tid);
                        train.setActualDepartureFromStation(simulationTimeSeconds);
                        commandLog.add(buildDepartLog(tid));
                        issueDepartIfNeeded(tid, "ATS 站台发车授权：满足停站、追踪间隔与前车净距");
                        train.setActiveCommand("DEPART");
                    } else {
                        train.setActiveCommand("WAITING_DEPARTURE_HEADWAY");
                    }
                }
                continue;
            }
        }
    }

    private boolean hasDepartCommand(String trainId) {
        SimulationSnapshot.TrainCommand cmd = activeCommands.get(trainId);
        return cmd != null && "DEPART".equals(cmd.getCommandType());
    }

    /**
     * ATS departure gate. Dispatch timing is primary; signal/ATP remains the
     * independent safety backstop after the train enters the line.
     */
    private boolean canAuthorizeOnboardDeparture(TrainState candidate) {
        String direction = "DOWN".equals(candidate.getDirection()) ? "DOWN" : "UP";
        double last = lastDepartureAuthorizationByDirection.getOrDefault(direction, Double.NEGATIVE_INFINITY);
        if (simulationTimeSeconds - last < MIN_DEPARTURE_HEADWAY_SECONDS)
            return false;

        int sign = candidate.getDirectionSign();
        boolean leaderTooClose = trains.values().stream()
                .filter(other -> !other.getTrainId().equals(candidate.getTrainId()))
                .filter(TrainState::occupiesTrack)
                .filter(other -> direction.equals(other.getDirection()))
                .anyMatch(other -> {
                    double separation = sign > 0
                            ? other.getPositionMeters() - candidate.getPositionMeters()
                            : candidate.getPositionMeters() - other.getPositionMeters();
                    return separation >= 0 && separation < MIN_DEPARTURE_CLEARANCE_METERS;
                });
        if (leaderTooClose)
            return false;

        lastDepartureAuthorizationByDirection.put(direction, simulationTimeSeconds);
        return true;
    }

    private void issueDepartIfNeeded(String trainId, String reason) {
        if (!integrationCommandBus.hasOpenCommand(trainId, "DEPART")) {
            integrationCommandBus.issue(trainId, "DEPART", 0, reason,
                    100, "ATS", simulationTimeSeconds);
        }
    }

    private void removeDepartCommand(String trainId) {
        SimulationSnapshot.TrainCommand cmd = activeCommands.get(trainId);
        if (cmd != null && "DEPART".equals(cmd.getCommandType())) {
            activeCommands.remove(trainId);
        }
    }

    private SimulationSnapshot.TrainCommand buildDepartLog(String trainId) {
        SimulationSnapshot.TrainCommand cmd = new SimulationSnapshot.TrainCommand();
        cmd.setTrainId(trainId);
        cmd.setCommandType("DEPART");
        cmd.setReason("调度授权发车");
        cmd.setIssuedTime(simulationTimeSeconds);
        return cmd;
    }

    // ================================================================
    // Layer 2.5: SafetyGuard — 超速/越过MA/异常状态二次检查
    // ================================================================

    private void safetyGuardCheck(List<LineProfile.Station> stations) {
        for (TrainState train : trains.values()) {
            if (!train.occupiesTrack())
                continue;

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
                    if (!train.getDirection().equals(other.getDirection()))
                        continue;
                    boolean isAhead = dirSign > 0
                            ? other.getPositionMeters() > train.getPositionMeters()
                            : other.getPositionMeters() < train.getPositionMeters();
                    if (!isAhead)
                        continue;

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
                train.setEmergencyBraking(false);
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
                .collect(Collectors.toList());

        // 按方向分组: 上行和下行列车各自独立做前后车安全间隔判断
        // 排序: 上行按位置升序(后方→前方), 下行按位置降序(后方→前方)
        Map<String, List<TrainState>> byDirection = active.stream()
                .collect(Collectors.groupingBy(t -> t.isUpDirection() ? "UP" : "DOWN"));

        for (Map.Entry<String, List<TrainState>> entry : byDirection.entrySet()) {
            String dir = entry.getKey();
            List<TrainState> dirTrains = entry.getValue();
            if (dirTrains.size() < 2) {
                // 单列车或无列车: 仍需要为每列车设置MA(无前车则MA=线路端点)
                for (TrainState t : dirTrains) {
                    t.setMovementAuthority("UP".equals(dir) ? 16049.0 : 0.0);
                }
                continue;
            }

            // 同向排序: 上行升序(后方→前方位置递增), 下行降序(后方→前方位置递减)
            boolean isUp = "UP".equals(dir);
            dirTrains.sort(isUp
                    ? Comparator.comparingDouble(TrainState::getPositionMeters)
                    : Comparator.comparingDouble(TrainState::getPositionMeters).reversed());

            // 同向列车做前后车配对安全检查
            for (int i = 0; i < dirTrains.size() - 1; i++) {
                TrainState following = dirTrains.get(i);
                TrainState leading = dirTrains.get(i + 1);

                // DWELLING/TERMINAL_DWELL/TURNING_BACK 列车仍占用线路, 必须参与MA计算
                // 仅跳过未上线的DEPOT_WAITING列车
                if ("DEPOT_WAITING".equals(following.getStatus()))
                    continue;

                // 计算移动授权 (CBTC Movement Authority) — 方向感知
                double ma = dispatchEngine.calcMovementAuthority(leading, following);
                following.setMovementAuthority(ma);

                // G8: 信号 runCycle 已写入权威 MA 时优先使用 Registry 值
                com.bjtu.railtransit.signal.domain.MovingAuthority sigMa = movementAuthorityRegistry
                        .get(following.getTrainId());
                if (sigMa != null) {
                    ma = sigMa.getEndOfAuthorityM();
                }

                // ── HMI列车：用HOLD/EMERGENCY_RECOVERY控制间距 ──
                // HMI列车由车载仿真器自主驱动，不响应SLOW/EMERGENCY_BRAKE指令
                // 安全间距不足时暂停播放(HOLD)，恢复时通知继续(EMERGENCY_RECOVERY)
                if ("ONBOARD_REPORTED".equals(following.getStateSource())) {
                    int dirSign = following.getDirectionSign();
                    double gap = dirSign > 0
                            ? leading.getPositionMeters() - following.getPositionMeters()
                            : following.getPositionMeters() - leading.getPositionMeters();
                    double safeDist = dispatchEngine.calcSafeDistance(following.getSpeed() / 3.6);

                    // 带滞回的间距控制: 低于30%安全距触发HOLD, 高于70%恢复
                    if (gap < safeDist * 0.3 && gap >= 0) {
                        SimulationSnapshot.TrainCommand existingHold = activeCommands.get(following.getTrainId());
                        if (existingHold == null || !"HOLD".equals(existingHold.getCommandType())) {
                            SimulationSnapshot.TrainCommand holdCmd = new SimulationSnapshot.TrainCommand();
                            holdCmd.setTrainId(following.getTrainId());
                            holdCmd.setCommandType("HOLD");
                            holdCmd.setReason(String.format(
                                    "ATP安全间距不足: 距前车%s %.0fm (安全%.0fm), HMI列车暂停",
                                    leading.getTrainId(), gap, safeDist));
                            applyCommand(holdCmd);
                        }
                    } else if (gap >= safeDist * 0.7) {
                        SimulationSnapshot.TrainCommand existingHold = activeCommands.get(following.getTrainId());
                        if (existingHold != null && "HOLD".equals(existingHold.getCommandType())) {
                            activeCommands.remove(following.getTrainId());
                            SimulationSnapshot.TrainCommand recoveryCmd = new SimulationSnapshot.TrainCommand();
                            recoveryCmd.setTrainId(following.getTrainId());
                            recoveryCmd.setCommandType("EMERGENCY_RECOVERY");
                            recoveryCmd.setReason(String.format(
                                    "ATP安全间距恢复: 距前车%s %.0fm (安全%.0fm)",
                                    leading.getTrainId(), gap, safeDist));
                            applyCommand(recoveryCmd);
                        }
                    }
                    following.setMaxSpeedLimit(CRUISE_SPEED);
                    continue; // HMI列车跳过常规ATP逻辑
                }

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
                // 核心要求: 30秒内将间距恢复到安全距离
                if (!"DWELLING".equals(following.getStatus())
                        && !"TERMINAL_DWELL".equals(following.getStatus())
                        && !"TURNING_BACK".equals(following.getStatus())) {
                    int dirSign = following.getDirectionSign();
                    double gap = dirSign > 0
                            ? leading.getPositionMeters() - following.getPositionMeters()
                            : following.getPositionMeters() - leading.getPositionMeters();
                    double safeDist = dispatchEngine.calcSafeDistance(following.getSpeed() / 3.6);

                    if (gap < safeDist && gap > 0 && !following.isEmergencyBraking()) {
                        // ── 30秒内恢复到安全距离 ──
                        // gap + (v_lead - v_follow_ltd) * 30s / 3.6 >= safeDist
                        // → v_follow_ltd <= v_lead - (safeDist - gap) * 3.6 / 30
                        double gapDeficit = safeDist - gap; // 距安全距离还差多少米
                        double leadSpeed = leading.getSpeed();
                        // 计算30秒内消除差距需要的速度差
                        double requiredSpeedDelta = gapDeficit * 3.6 / 30.0; // km/h
                        double limitedSpeed = Math.max(leadSpeed - requiredSpeedDelta, 0);

                        // 安全下限: 不低于当前速度的40%且不低于20km/h
                        double floorSpeed = Math.max(20, following.getSpeed() * 0.4);
                        if (limitedSpeed < floorSpeed) {
                            limitedSpeed = floorSpeed;
                        }
                        // 上限: 不超过当前速度 (不能加速逼近)
                        limitedSpeed = Math.min(limitedSpeed, following.getSpeed());

                        following.setMaxSpeedLimit(limitedSpeed);

                        SimulationSnapshot.TrainCommand slowCmd = activeCommands.get(following.getTrainId());
                        if (slowCmd == null || !"SLOW".equals(slowCmd.getCommandType())
                                || Math.abs(slowCmd.getTargetValue() - limitedSpeed) > 2) {
                            slowCmd = new SimulationSnapshot.TrainCommand();
                            slowCmd.setTrainId(following.getTrainId());
                            slowCmd.setCommandType("SLOW");
                            slowCmd.setTargetValue(limitedSpeed);
                            double estRecovery = gapDeficit / Math.max(0.1, leadSpeed - limitedSpeed) * 3.6;
                            slowCmd.setReason(String.format(
                                    "ATP限速 %.0fkm/h (前车%.0fkm/h), 间距%.0fm差%.0fm到安全距%.0fm, 预计%.0fs恢复",
                                    limitedSpeed, leadSpeed, gap, gapDeficit, safeDist, estRecovery));
                            applyCommand(slowCmd);
                        }
                    } else if (gap >= safeDist && !following.isEmergencyBraking()) {
                        // 间距已安全 → 清除限速
                        following.setMaxSpeedLimit(CRUISE_SPEED);
                        SimulationSnapshot.TrainCommand existingSlow = activeCommands.get(following.getTrainId());
                        if (existingSlow != null && "SLOW".equals(existingSlow.getCommandType())) {
                            activeCommands.remove(following.getTrainId());
                        }
                    }
                }
            }

            // ── HMI列车：确保同向最前方列车不被扣车，可前行拉开间距 ──
            if (!dirTrains.isEmpty()) {
                int lastIdx = dirTrains.size() - 1;
                TrainState frontmost = dirTrains.get(lastIdx);
                if ("ONBOARD_REPORTED".equals(frontmost.getStateSource())
                        && !"DEPOT_WAITING".equals(frontmost.getStatus())) {
                    SimulationSnapshot.TrainCommand existingHold = activeCommands.get(frontmost.getTrainId());
                    if (existingHold != null && "HOLD".equals(existingHold.getCommandType())) {
                        activeCommands.remove(frontmost.getTrainId());
                        SimulationSnapshot.TrainCommand recoveryCmd = new SimulationSnapshot.TrainCommand();
                        recoveryCmd.setTrainId(frontmost.getTrainId());
                        recoveryCmd.setCommandType("EMERGENCY_RECOVERY");
                        recoveryCmd.setReason("ATP队列车头: 前方无车，恢复运行以拉开间距");
                        applyCommand(recoveryCmd);
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

        // 车载上报驱动的列车：位置/速度由 fuseOnboardReports 更新，
        // 跳过运动学计算，但信号、能源、安全等环节继续运行
        if ("ONBOARD_REPORTED".equals(train.getStateSource()))
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

        // ── 多质点车厢动力学更新（替代原 syncCars）──
        double targetAccel = train.getAcceleration() / 3.6; // km/h/s → m/s²
        boolean braking = "BRAKING".equals(train.getStatus());
        stepMultiParticle(train, targetAccel, braking);

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

            // ── 巡航接近车站: 提前切断牵引，惰行滑入制动曲线，省去巡航能耗 ──
            if ("CRUISING".equals(status) && distToStationM <= brakingDistance * 2.0) {
                train.setStatus("COASTING");
            }
        }

        // ── 正常加速/巡航 ──
        double newSpeed = speed;
        double accel;

        if ("COASTING".equals(status)) {
            // 惰行: 无牵引力、无制动力，仅受阻力减速
            double tareMass = train.getCarCount() * 35000.0;
            double passengerMass = train.getLoadFactor() * 60.0 * 2200.0;
            double massKg = tareMass + passengerMass;
            double resistN = physics.totalResistanceForce(train);
            // 含回转质量修正的减速度
            double coastDecelMs2 = resistN / (massKg * (1.0 + 0.06)); // ROTARY_MASS_FACTOR = 0.06
            double coastDecelKmhPerS = coastDecelMs2 * 3.6;
            accel = -coastDecelKmhPerS;
            newSpeed = Math.max(0, speed - coastDecelKmhPerS);

            // 速度过低时退出惰行，恢复加速至巡航
            if (newSpeed < EnergyOptimizer.COAST_MIN_SPEED) {
                train.setStatus("ACCELERATING");
            }
        } else if (speed < maxSpeed) {
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

    // ── HMI列车到站记录补录 ──
    /** HMI列车跳过atoTrainStep，需在fuseOnboardReports中检测到站时补录stationArrivalMap */
    private void recordHmiStationArrival(TrainState train, List<LineProfile.Station> stations) {
        String tid = train.getTrainId();
        int stationIndex = train.getCurrentStationIndex();
        if (stationIndex < 0 || stationIndex >= stations.size())
            return;

        int stationId = stations.get(stationIndex).getId();
        double plannedDwell = dispatchEngine.calcDwellTime(stationId, simulationTimeSeconds);

        stationArrivalMap.putIfAbsent(tid, new ArrayList<>());
        SimulationSnapshot.StationArrival arrival = new SimulationSnapshot.StationArrival();
        arrival.setTrainId(tid);
        arrival.setStationIndex(stationIndex);
        arrival.setStationName(stations.get(stationIndex).getName());
        arrival.setArrivalTimeSeconds(simulationTimeSeconds);
        arrival.setDepartureTimeSeconds(simulationTimeSeconds + plannedDwell);
        arrival.setDwellSeconds(plannedDwell);

        DispatchEngine.TimetableEntry tEntry = dispatchEngine.getTimetableEntry(tid, stationIndex);
        if (tEntry != null) {
            arrival.setPlannedArrivalSeconds(tEntry.plannedArrival);
            arrival.setPlannedDepartureSeconds(tEntry.plannedDeparture);
            arrival.setPlannedDwellSeconds(tEntry.plannedDwell);
            arrival.setArrivalDeviation(simulationTimeSeconds - tEntry.plannedArrival);
            arrival.setDepartureDeviation((simulationTimeSeconds + plannedDwell) - tEntry.plannedDeparture);
        }
        stationArrivalMap.get(tid).add(arrival);
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

    /**
     * 多质点车厢状态更新 —— 替代原 syncCars()。
     *
     * <p>
     * 不再简单将车厢位置按车头位置+偏移同步，而是用多质点动力学
     * 独立计算每节车厢的加速度/速度/位置，包含:
     * - 各车厢独立质量（含载客）
     * - 各车厢独立Davis基本阻力
     * - 各车厢独立坡度阻力（位于不同坡度段）
     * - 车钩弹性-阻尼耦合
     * - 牵引力/制动力仅在动车上施加
     *
     * <p>
     * 步进后，列车整体位置/速度从车头车厢同步回 TrainState。
     */
    private void stepMultiParticle(TrainState train, double targetAccelMps2, boolean inBraking) {
        List<TrainCar> cars = consistMap.get(train.getTrainId());
        if (cars == null || cars.size() != 6) {
            // 未初始化多质点编组 → 回退到原 syncCars
            syncCarsLegacy(train);
            return;
        }

        // ── 更新坡度阻力（各车厢质心位置取不同坡度）──
        multiParticleSimulationService.updateGradeResistance(cars,
                pos -> (double) lineDataService.getGradientAtKm(pos / 1000.0) / 1000.0); // ‰ → 小数

        // ── 多质点步进 ──
        multiParticleSimulationService.stepConsist(cars, targetAccelMps2, 1.0, inBraking);

        // ── 同步回 TrainState ──
        TrainCar headCar = cars.get(0);
        train.setPositionMeters(headCar.getHeadPositionMeters());
        train.setSpeed(headCar.getSpeedKmh());
        train.setAcceleration(headCar.getAccelerationKmhs());
        train.setTrainLengthMeters(multiParticleSimulationService.getConsistLength(cars));
        train.setCars(cars);

        // 整列车载客率 = 加权平均
        double totalOccMass = 0, totalCurbMass = 0;
        for (TrainCar c : cars) {
            totalOccMass += c.getOccupiedMass();
            totalCurbMass += c.getCurbMass();
        }
        if (totalCurbMass > 0) {
            train.setLoadFactor(totalOccMass / totalCurbMass - 1.0); // 满载率
        }
    }

    /** 原 syncCars 作为回退方案 */
    private void syncCarsLegacy(TrainState train) {
        if (train.getCars() != null) {
            for (TrainCar car : train.getCars()) {
                double offset = car.getCarIndex() * train.getCarLength();
                car.setPositionMeters(train.isUpDirection()
                        ? train.getPositionMeters() - offset
                        : train.getPositionMeters() + offset);
                car.setSpeedKmh(train.getSpeed());
            }
        }
    }

    // ================================================================
    // Layer 4: 能源优化 —— 惰行窗口 + 再生协同 + 峰值监控
    // ================================================================

    /** 最近一次能源优化结果 (供快照使用) */
    private EnergyOptimizer.EnergyOptimizationResult lastEnergyResult;

    /** 最近一次 MPC 优化结果 (供快照使用) */
    private Map<String, PredictiveController.StrategyEvaluation> lastMpcResults;

    /** 能耗历史趋势 (每5秒记录一次) */
    private final List<SimulationSnapshot.EnergyDataPoint> energyHistory = new ArrayList<>();
    private double coastingSavedKwh = 0;
    /** 上次记录能耗趋势的时间 */
    private double lastEnergyHistoryTime = -10;

    private void energyOptimizeStep() {
        // 每20仿真秒评估一次 (避免每步都计算)
        if (simulationTimeSeconds % 20 != 0)
            return;

        lastEnergyResult = energyOptimizer.evaluate(trains, simulationTimeSeconds, dispatchEngine);

        // ── 策略1: 应用惰行窗口 ──
        for (EnergyOptimizer.CoastingDecision cd : lastEnergyResult.coastingOpportunities) {
            TrainState t = trains.get(cd.trainId);
            if (t != null && t.getSpeed() > EnergyOptimizer.COAST_MIN_SPEED) {
                // 惰行: 将列车状态切换为惰行，无牵引力无制动力，仅受阻力自然减速
                t.setStatus("COASTING");

                // 记录惰行指令
                SimulationSnapshot.TrainCommand coastCmd = new SimulationSnapshot.TrainCommand();
                coastCmd.setTrainId(cd.trainId);
                coastCmd.setCommandType("COAST");
                coastCmd.setTargetValue(t.getSpeed());
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

    /** 计算惰行节省能耗: 惰行期间若用牵引需耗能，减去惰行实际暗耗，差值即为节省 */
    private double computeCoastingSaved() {
        double saved = 0;
        for (TrainState t : trains.values()) {
            String status = t.getStatus();
            if (status != null && (status.equals("COAST") || status.equals("COASTING"))
                    && t.getSpeed() > EnergyOptimizer.COAST_MIN_SPEED) {
                // 惰行期间若用牵引: Davis阻力做功 = 牵引能耗
                double hypoKwh = physics.tractionStepEnergyKwh(t);
                saved += hypoKwh * 0.85; // 85%的牵引能耗被节省 (惰行暗耗约15%)
            }
        }
        return saved;
    }

    // ================================================================
    // Layer 5: 指令执行 + 晚点传播
    // ================================================================

    private void commandExecuteDelays() {
        // ── MPC 预测优化 (每30秒评估，对晚点>30s的列车) ──
        if (simulationTimeSeconds % 30 == 0) {
            List<LineProfile.Station> stations = lineProfile.getStations();

            // Step 1: MPC 优化（对晚点超过 MPC 阈值的列车）
            lastMpcResults = predictiveController.optimizeAll(trains, simulationTimeSeconds, stations);

            // Step 2: 应用 MPC 最优策略
            if (lastMpcResults != null && !lastMpcResults.isEmpty()) {
                for (Map.Entry<String, PredictiveController.StrategyEvaluation> entry : lastMpcResults.entrySet()) {
                    String tid = entry.getKey();
                    PredictiveController.StrategyEvaluation eval = entry.getValue();
                    SimulationSnapshot.TrainCommand mpcCmd = predictiveController.toTrainCommand(
                            eval, tid, simulationTimeSeconds);
                    applyCommand(mpcCmd);

                    // 记录 MPC 决策到日志
                    SimulationSnapshot.DelayEvent mpcEvent = new SimulationSnapshot.DelayEvent();
                    mpcEvent.setTimeSeconds(simulationTimeSeconds);
                    mpcEvent.setTrainId(tid);
                    mpcEvent.setDelaySeconds(eval.finalDelaySec);
                    mpcEvent.setCause("[MPC] " + eval.candidate.label
                            + " | 预计最终晚点" + String.format("%.0f", eval.finalDelaySec) + "s"
                            + " | 代价=" + String.format("%.1f", eval.totalCost));
                    mpcEvent.setPositionKm(trains.get(tid) != null
                            ? trains.get(tid).getPositionMeters() / 1000.0
                            : 0);
                    mpcEvent.setEventType("PRIMARY_DELAY");
                    delayEventLog.add(mpcEvent);
                }
            }

            // Step 3: 剩余列车(晚点<MPC阈值) 使用原有规则触发
            DispatchEngine.DelayResult delayResult = dispatchEngine.evaluateAllDelays(
                    new ArrayList<>(trains.values()), simulationTimeSeconds);

            Set<String> speedUpTrains = new java.util.HashSet<>();
            // 跳过已被 MPC 处理的列车
            Set<String> mpcHandled = lastMpcResults != null ? lastMpcResults.keySet() : Collections.emptySet();

            for (SimulationSnapshot.TrainCommand cmd : delayResult.commands) {
                if ("SPEED_UP".equals(cmd.getCommandType()) && !mpcHandled.contains(cmd.getTrainId())) {
                    speedUpTrains.add(cmd.getTrainId());
                    applyCommand(cmd);
                }
            }

            // 清理已恢复列车的 SPEED_UP（仅对非 MPC 管理的列车）
            for (Map.Entry<String, SimulationSnapshot.TrainCommand> entry : activeCommands.entrySet()) {
                if ("SPEED_UP".equals(entry.getValue().getCommandType())
                        && !speedUpTrains.contains(entry.getKey())
                        && !mpcHandled.contains(entry.getKey())) {
                    activeCommands.remove(entry.getKey());
                }
            }

            for (SimulationSnapshot.DelayEvent evt : delayResult.events) {
                delayEventLog.removeIf(
                        e -> e.getTrainId().equals(evt.getTrainId()) && e.getEventType().equals(evt.getEventType()));
                delayEventLog.add(evt);
            }
            dispatchEngine.logDelayEvents(delayResult.events);
        }

        // ── 晚点传播评估 (每步执行, 按方向分组) ──
        Map<String, List<TrainState>> byDir = trains.values().stream()
                .filter(TrainState::occupiesTrack)
                .filter(t -> !"DWELLING".equals(t.getStatus()))
                .filter(t -> !"TERMINAL_DWELL".equals(t.getStatus()))
                .filter(t -> !"TURNING_BACK".equals(t.getStatus()))
                .collect(Collectors.groupingBy(t -> t.isUpDirection() ? "UP" : "DOWN"));

        for (List<TrainState> dirTrains : byDir.values()) {
            if (dirTrains.size() < 2)
                continue;
            boolean isUp = dirTrains.get(0).isUpDirection();
            // 同向排序: 上行升序(后方→前方), 下行降序(后方→前方)
            dirTrains.sort(isUp
                    ? Comparator.comparingDouble(TrainState::getPositionMeters)
                    : Comparator.comparingDouble(TrainState::getPositionMeters).reversed());

            DispatchEngine.DelayResult propResult = dispatchEngine.evaluateDelayPropagation(dirTrains,
                    simulationTimeSeconds);
            for (SimulationSnapshot.TrainCommand cmd : propResult.commands) {
                applyCommand(cmd);
            }
            for (SimulationSnapshot.DelayEvent evt : propResult.events) {
                delayEventLog.add(evt);
            }
            dispatchEngine.logDelayEvents(propResult.events);
        }
    }

    // ================================================================
    // Layer 4.5: ATC 牵引/制动能力约束 + 电空状态同步
    // ================================================================

    private void atcConstraintCheck() {
        for (TrainState train : trains.values()) {
            if ("FINISHED".equals(train.getStatus()) || "DEPOT_WAITING".equals(train.getStatus()))
                continue;

            TractionSystemState ts = train.getTractionState();
            BrakingSystemState bs = train.getBrakeState();

            if (ts == null || bs == null)
                continue;

            // ── 牵引系统健康检查 ──
            if ("FAULT".equals(ts.getHealth())) {
                // 牵引系统完全故障 → 强制紧急制动
                train.setEmergencyBraking(true);
                train.setActiveCommand("ATC_TRACTION_FAULT");
                continue;
            }

            if ("DEGRADED".equals(ts.getHealth())) {
                // 牵引能力下降 → 基于真实牵引曲线和可用电机比例削减加速度
                int totalMotors = train.getCarCount() * 4;
                double ratio = ts.getAvailableMotors() / (double) totalMotors;
                double maxForceN = physics.maxTractiveForceAtSpeed(train.getSpeed(),
                        ts.getAvailableMotors(), totalMotors);
                double massKg = train.getCarCount() * 35000.0;
                double maxAccelMs2 = maxForceN / (massKg * (1.0 + 0.06)); // 含回转质量修正
                train.setAcceleration(Math.min(train.getAcceleration(), maxAccelMs2 * 3.6));
                train.setMaxSpeedLimit(Math.min(train.getMaxSpeedLimit(),
                        CRUISE_SPEED * ratio * 0.8));
                ts.setMaxTractiveForceN(maxForceN);
            }

            // ── 制动系统健康检查 ──
            if ("FAULT".equals(bs.getHealth())) {
                train.setEmergencyBraking(true);
                train.setActiveCommand("ATC_BRAKE_FAULT");
                continue;
            }

            if ("DEGRADED".equals(bs.getHealth())) {
                // 空气制动能力衰减 — 标记降至AIR_ONLY模式(全部依靠剩余空气制动)
                bs.setBlendingMode("AIR_ONLY");
            }

            // ── 电制动可用性同步 ──
            if (!ts.isElectricBrakeAvailable()) {
                bs.setBlendingMode("AIR_ONLY");
            }
        }
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

            // ── 动态更新满载率: 基于客流模型 ──
            double peakFlow = dispatchEngine.getFlowModel().getPeakSectionFlow(simulationTimeSeconds);
            double loadFactor = Math.min(1.0, peakFlow / (2200.0 * 0.85)); // 2200人/列 × 85% 目标满载率
            t.setLoadFactor(loadFactor);

            // 根据位置估算当前Seg编号
            log.setCurrentSegId(estimateSegId(t.getPositionMeters()));

            // 解析 trainId 中的数字部分: "T1"→1, "OB1"→1
            try {
                log.setTrainId(Integer.parseInt(t.getTrainId().replaceAll("[^0-9]", "")));
            } catch (NumberFormatException e) {
                log.setTrainId(0);
            }

            // ── 基于 TB/T 1407.2 牵引物理模型计算能耗 ──
            double accelMs2 = t.getAcceleration() / 3.6;
            // 总质量 = 空车质量 + 乘客质量(满载率 × 60kg/人 × 2200人/列)
            double tareMass = t.getCarCount() * 35000.0;
            double passengerMass = t.getLoadFactor() * 60.0 * 2200.0;
            double mass = tareMass + passengerMass;
            double resistanceN = physics.totalResistanceForce(t);
            double inertiaForceN = physics.inertiaForce(mass, accelMs2);
            TractionSystemState ts = t.getTractionState();
            BrakingSystemState bs = t.getBrakeState();

            if (t.getSpeed() <= 0.5) {
                // 停站中
                log.setTractiveBrakeCmd("coast");
                log.setTractiveBrakePercent(0);
                log.setTractionForce(0);
                log.setBrakeForce(0);
                log.setElectricBrakeForce(0);
                log.setAirBrakeForce(0);
            } else if ("COASTING".equals(t.getStatus())) {
                // 惰行: 无牵引力、无制动力，仅受阻力自然减速（不消耗能量）
                log.setTractiveBrakeCmd("coast");
                log.setTractiveBrakePercent(0);
                log.setTractionForce(0);
                log.setBrakeForce(0);
                log.setElectricBrakeForce(0);
                log.setAirBrakeForce(0);
            } else if (accelMs2 > 0.05) {
                // 牵引工况 — Davis阻力 + 惯性力 + 坡道阻力
                double tractionForceN = resistanceN + Math.max(0, inertiaForceN);
                // ── 牵引能力约束: 不超过 VVVF 特性曲线 ──
                double maxAvailN = physics.maxTractiveForceAtSpeed(t.getSpeed(),
                        ts != null ? ts.getAvailableMotors() : 24, t.getCarCount() * 4);
                double cappedForceN = Math.min(tractionForceN, maxAvailN);
                log.setTractiveBrakeCmd("traction");
                log.setTractiveBrakePercent(Math.min(100, Math.abs(accelMs2) / 1.0 * 100));
                log.setTractionForce(cappedForceN);
                log.setBrakeForce(0);
                log.setElectricBrakeForce(0);
                log.setAirBrakeForce(0);
                // 保存牵引能力到 TractionSystemState
                if (ts != null) {
                    ts.setMaxTractiveForceN(maxAvailN);
                }

                double stepKwh = physics.tractionStepEnergyKwh(t);
                totalEnergyKwh += stepKwh;
                totalTractionEnergyKwh += stepKwh;
                totalCruisingEnergyKwh += physics.cruisingStepEnergyKwh(t);
            } else if (accelMs2 < -0.05) {
                // ── 制动工况 (CBTC 执行层: 电空配合分离) ──
                double totalBrakeForceN = Math.abs(inertiaForceN);
                log.setTractiveBrakeCmd("brake");
                log.setTractiveBrakePercent(Math.min(100, Math.abs(accelMs2) / 1.0 * 100));
                log.setTractionForce(0);
                log.setBrakeForce(totalBrakeForceN);

                // ── 电空配合分配 ──
                BlendingBrakeService.BrakeAllocation allocation = blendingBrakeService.allocate(
                        totalBrakeForceN, t.getSpeed(),
                        t.getTractionState(), t.getBrakeState());
                log.setElectricBrakeForce(allocation.electricBrakeN());
                log.setAirBrakeForce(allocation.airBrakeN());

                // ── 写入电空配合状态到制动系统（闭合反馈回路）──
                if (bs != null) {
                    bs.setBlendingMode(allocation.electricBrakeN() > 0 && allocation.airBrakeN() > 0
                            ? "BLEND"
                            : allocation.electricBrakeN() > 0 ? "ELEC_ONLY" : "AIR_ONLY");
                    bs.setElectricBrakeRequestN(allocation.electricBrakeN());
                }
                if (ts != null) {
                    ts.setElectricBrakeAppliedN(allocation.electricBrakeN());
                }

                // ── 电制动再生回收 (仅电制动分量计入) ──
                if (allocation.electricBrakeN() > 0) {
                    // 制动时阻力帮助减速, 可回收部分扣除阻力
                    double speedMs = t.getSpeed() / 3.6;
                    double resistanceHelp = physics.basicResistanceNewton(mass, speedMs);
                    double netElecBrakeKw = Math.max(0,
                            (allocation.electricBrakeN() - resistanceHelp) * speedMs / 1000.0);
                    totalRegenEnergyKwh += netElecBrakeKw * 0.65 / 3600.0;
                }
            } else {
                // 巡航: 牵引力刚好克服阻力，维持恒速
                log.setTractiveBrakeCmd("traction");
                log.setTractiveBrakePercent(5);
                log.setTractionForce(resistanceN);
                log.setBrakeForce(0);
                log.setElectricBrakeForce(0);
                log.setAirBrakeForce(0);

                if (t.getSpeed() > 5) {
                    double stepKwh = physics.cruisingStepEnergyKwh(t);
                    totalEnergyKwh += stepKwh;
                    totalCruisingEnergyKwh += stepKwh;
                }
            }

            // ── CBTC 执行层: 系统健康状态写入日志 ──
            log.setTractionHealth(ts != null ? ts.getHealth() : "NORMAL");
            log.setBrakingHealth(bs != null ? bs.getHealth() : "NORMAL");
            log.setAvailableMotors(ts != null ? ts.getAvailableMotors() : t.getCarCount() * 4);
            log.setElectricBrakeAvailable(ts != null && ts.isElectricBrakeAvailable());

            // ── 辅助能耗 (空调/照明, 在线列车均计入) ──
            totalAuxEnergyKwh += physics.auxiliaryStepEnergyKwh(t.getCarCount());

            simulationLogs.add(log);
            redisDataBus.appendLog(log);
        }

        // ── DC1500V 牵引供电仿真 ──
        powerSupply.stepPowerSupply(trains.values(), totalTractionEnergyKwh, totalRegenEnergyKwh);

        // ── 虚拟列车网络系统 ──
        networkService.stepNetworkUpdate(trains.values(), simulationTimeSeconds);

        // ── 能耗趋势记录 (每5秒) ──
        if (simulationTimeSeconds - lastEnergyHistoryTime >= 5) {
            energyHistory.add(new SimulationSnapshot.EnergyDataPoint(
                    simulationTimeSeconds, totalEnergyKwh, totalTractionEnergyKwh,
                    totalRegenEnergyKwh, totalAuxEnergyKwh));
            lastEnergyHistoryTime = simulationTimeSeconds;
        }

        // ── 计算惰行节省能耗 ──
        coastingSavedKwh = computeCoastingSaved();
    }

    /** 根据位置估算所属Seg编号（按约1000m一个Seg粗略划分） */
    private int estimateSegId(double positionMeters) {
        return (int) (positionMeters / 1000) + 1;
    }

    /** 解析车站ID为0-based索引 */
    private int parseStationIndex(String stationId) {
        if (stationId == null || stationId.isBlank())
            return -1;
        try {
            return Integer.parseInt(stationId) - 1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** HMI上报的到站阶段（兼容DWELL/DWELLING/TERMINAL_DWELL/STOPPED） */
    private boolean isHmiDwellPhase(String phase) {
        if (phase == null)
            return false;
        return "DWELLING".equals(phase) || "TERMINAL_DWELL".equals(phase)
                || "DWELL".equals(phase) || "STOPPED".equals(phase);
    }

    // ================================================================
    // 指令管理
    // ================================================================

    private void applyCommand(SimulationSnapshot.TrainCommand cmd) {
        cmd.setIssuedTime(simulationTimeSeconds);
        cmd.setStatus("EXECUTING");
        activeCommands.put(cmd.getTrainId(), cmd);
        commandLog.add(cmd);
        // 同步写入CommandBus，统一指令总线，HMI通过 /api/onboard/{trainId}/snapshot 可获取
        integrationCommandBus.issue(
                cmd.getTrainId(),
                cmd.getCommandType(),
                cmd.getTargetValue(),
                cmd.getReason(),
                100,
                "ATS",
                simulationTimeSeconds);
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

        // 车头时距 (方向感知, 按方向分组计算)
        Map<String, List<TrainState>> hwByDir = trains.values().stream()
                .filter(TrainState::occupiesTrack)
                .collect(Collectors.groupingBy(t -> t.isUpDirection() ? "UP" : "DOWN"));
        List<SimulationSnapshot.HeadwayInfo> headwayList = new ArrayList<>();
        for (List<TrainState> dirTrains : hwByDir.values()) {
            if (dirTrains.size() < 2)
                continue;
            boolean isUp = dirTrains.get(0).isUpDirection();
            dirTrains.sort(isUp
                    ? Comparator.comparingDouble(TrainState::getPositionMeters)
                    : Comparator.comparingDouble(TrainState::getPositionMeters).reversed());
            for (int i = 0; i < dirTrains.size() - 1; i++) {
                TrainState following = dirTrains.get(i);
                TrainState leading = dirTrains.get(i + 1);

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
                hw.setStatus(gap < safeDist * 0.5 ? "DANGER"
                        : gap < safeDist * 0.8 ? "WARNING" : gap < safeDist ? "CAUTION" : "SAFE");
                headwayList.add(hw);
            }
        }
        snapshot.setHeadways(headwayList);

        // 指令（按时间顺序排列）
        List<SimulationSnapshot.TrainCommand> cmds = new ArrayList<>(activeCommands.values());
        for (int i = commandLog.size() - 1; i >= 0 && cmds.size() < 40; i--) {
            SimulationSnapshot.TrainCommand c = commandLog.get(i);
            if (cmds.stream().noneMatch(
                    x -> x.getTrainId().equals(c.getTrainId()) && x.getCommandType().equals(c.getCommandType()))) {
                cmds.add(c);
            }
        }
        cmds.sort(Comparator.comparingDouble(c -> c.getIssuedTime()));
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
        snapshot.setTotalTractionKwh(totalTractionEnergyKwh);
        snapshot.setTotalRegenKwh(totalRegenEnergyKwh);
        snapshot.setTotalAuxKwh(totalAuxEnergyKwh);
        snapshot.setTotalCruisingKwh(totalCruisingEnergyKwh);
        snapshot.setCoastingSavedKwh(coastingSavedKwh);
        snapshot.setEnergyHistory(new ArrayList<>(energyHistory));
        // ── 供电状态 ──
        List<SimulationSnapshot.PowerSupplyData> psList = new ArrayList<>();
        Map<String, TractionPowerSupplyService.PowerState> psMap = powerSupply.getPowerStates();
        for (var entry : psMap.entrySet()) {
            TractionPowerSupplyService.PowerState ps = entry.getValue();
            // find nearest substation from substations list
            String nearestSub = "";
            double minDist = Double.MAX_VALUE;
            TrainState t = trains.get(entry.getKey());
            if (t != null) {
                double posKm = t.getPositionMeters() / 1000.0;
                for (var sub : powerSupply.getSubstations()) {
                    double d = Math.abs(posKm - sub.km());
                    if (d < minDist) {
                        minDist = d;
                        nearestSub = sub.name();
                    }
                }
            }
            psList.add(new SimulationSnapshot.PowerSupplyData(
                    entry.getKey(), ps.voltage(), ps.current(), ps.powerKw(),
                    nearestSub, ps.voltageOK()));
        }
        snapshot.setPowerSupplyStatus(psList);

        // ── 供电分区状态 ──
        snapshot.setPowerSections(PowerSectionStatus.createDefaultSections());

        // ── 移动授权列表 (G8: 优先读 Registry 权威 MA，fallback 读 runtime 字段) ──
        List<SimulationSnapshot.MovementAuthorityInfo> maList = new ArrayList<>();
        for (TrainState t : trains.values()) {
            if (!t.occupiesTrack())
                continue;
            SimulationSnapshot.MovementAuthorityInfo maInfo = new SimulationSnapshot.MovementAuthorityInfo();
            maInfo.setTrainId(t.getTrainId());
            maInfo.setDirection(t.getDirection());
            com.bjtu.railtransit.signal.domain.MovingAuthority regMa = movementAuthorityRegistry.get(t.getTrainId());
            maInfo.setAuthorityEndMeters(regMa != null ? regMa.getEndOfAuthorityM() : t.getMovementAuthority());
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

        // ── MPC 预测优化数据 ──
        if (lastMpcResults != null && !lastMpcResults.isEmpty()) {
            List<Map<String, Object>> mpcSummary = new ArrayList<>();
            for (Map.Entry<String, PredictiveController.StrategyEvaluation> entry : lastMpcResults.entrySet()) {
                PredictiveController.StrategyEvaluation eval = entry.getValue();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("trainId", entry.getKey());
                item.put("strategy", eval.candidate.label);
                item.put("predictedFinalDelay", Math.round(eval.finalDelaySec * 10) / 10.0);
                item.put("totalCost", Math.round(eval.totalCost * 10) / 10.0);
                item.put("recoveryLevel", eval.recoveryLevel);
                item.put("trajectory", predictiveController.getTrajectoryPreview(eval));
                mpcSummary.add(item);
            }
            snapshot.setMpcOptimizations(mpcSummary);
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
        plan.setStartStationId(1); // 郭公庄
        plan.setEndStationId(13); // 国家图书馆
        plan.setRoutePattern("FULL");
        plan.setOperationMode(required > available ? "EMERGENCY" : "NORMAL");

        List<DispatchPlan.ScheduleEntry> schedule = new ArrayList<>();
        for (int i = 0; i < trains.size(); i++) {
            DispatchPlan.ScheduleEntry entry = new DispatchPlan.ScheduleEntry();
            entry.setTrainId("OB" + (i + 1));
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
                cc.setSpeedKmh(car.getSpeedKmh());
                cc.setCurbMass(car.getCurbMass());
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
    // ================================================================
    // CBTC 执行层: 故障注入 (对应 doc.md §1 多车司控台故障注入功能)
    // ================================================================

    public void injectFault(String trainId, String faultType, int severity) {
        TractionSystemState ts = tractionStates.get(trainId);
        BrakingSystemState bs = brakeStates.get(trainId);
        TrainState train = trains.get(trainId);
        if (train == null)
            return;

        switch (faultType) {
            case "MOTOR_FAILURE":
                if (ts != null) {
                    ts.setAvailableMotors(Math.max(1, ts.getAvailableMotors() - severity));
                    ts.setHealth(ts.getAvailableMotors() < ts.getAvailableMotors() * 0.5 ? "FAULT" : "DEGRADED");
                    ts.setFaultCode("MOTOR_FAILURE:" + severity);
                }
                break;
            case "INVERTER_FAULT":
                if (ts != null) {
                    ts.setMaxTractiveForceN(ts.getMaxTractiveForceN() * 0.5);
                    ts.setHealth("DEGRADED");
                    ts.setFaultCode("INVERTER_FAULT");
                }
                break;
            case "ELECTRIC_BRAKE_LOSS":
                if (ts != null) {
                    ts.setElectricBrakeAvailable(false);
                    ts.setMaxElectricBrakeForceN(0);
                    ts.setFaultCode("ELEC_BRAKE_LOSS");
                }
                break;
            case "AIR_BRAKE_DEGRADED":
                if (bs != null) {
                    bs.setMaxAirBrakeForceN(bs.getMaxAirBrakeForceN() * (1.0 - severity / 100.0));
                    bs.setHealth(severity > 50 ? "FAULT" : "DEGRADED");
                    bs.setFaultCode("AIR_BRAKE_DEGRADED:" + severity);
                }
                break;
            case "TCU_COMM_LOSS":
                if (ts != null) {
                    ts.setHealth("FAULT");
                    ts.setFaultCode("TCU_COMM_LOSS");
                }
                break;
        }
    }

    public void clearFault(String trainId, String faultType) {
        TractionSystemState ts = tractionStates.get(trainId);
        BrakingSystemState bs = brakeStates.get(trainId);
        if (ts == null && bs == null)
            return;

        switch (faultType) {
            case "MOTOR_FAILURE":
            case "INVERTER_FAULT":
            case "TCU_COMM_LOSS":
                if (ts != null) {
                    TrainState train = trains.get(trainId);
                    int carCount = train != null ? train.getCarCount() : 6;
                    ts.setAvailableMotors(carCount * 4);
                    ts.setMaxTractiveForceN(carCount * 4 * 12_900.0);
                    ts.setHealth("NORMAL");
                    ts.setFaultCode(null);
                }
                break;
            case "ELECTRIC_BRAKE_LOSS":
                if (ts != null) {
                    TrainState train = trains.get(trainId);
                    int carCount = train != null ? train.getCarCount() : 6;
                    ts.setElectricBrakeAvailable(true);
                    ts.setMaxElectricBrakeForceN(carCount * 4 * 12_900.0 * 0.8);
                    ts.setFaultCode(null);
                }
                break;
            case "AIR_BRAKE_DEGRADED":
                if (bs != null) {
                    TrainState train = trains.get(trainId);
                    int carCount = train != null ? train.getCarCount() : 6;
                    bs.setMaxAirBrakeForceN(carCount * 35_000.0 * 1.2 * 0.85);
                    bs.setHealth("NORMAL");
                    bs.setFaultCode(null);
                }
                break;
            case "ALL":
                if (ts != null) {
                    TrainState train = trains.get(trainId);
                    int carCount = train != null ? train.getCarCount() : 6;
                    ts.setAvailableMotors(carCount * 4);
                    ts.setMaxTractiveForceN(carCount * 4 * 12_900.0);
                    ts.setHealth("NORMAL");
                    ts.setElectricBrakeAvailable(true);
                    ts.setMaxElectricBrakeForceN(carCount * 4 * 12_900.0 * 0.8);
                    ts.setFaultCode(null);
                }
                if (bs != null) {
                    TrainState train = trains.get(trainId);
                    int carCount = train != null ? train.getCarCount() : 6;
                    bs.setMaxAirBrakeForceN(carCount * 35_000.0 * 1.2 * 0.85);
                    bs.setHealth("NORMAL");
                    bs.setFaultCode(null);
                }
                break;
        }
    }

    public Map<String, TractionSystemState> getTractionStates() {
        return tractionStates;
    }

    public Map<String, BrakingSystemState> getBrakeStates() {
        return brakeStates;
    }

    public Map<String, Object> getPowerStates() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("substations", powerSupply.getSubstations());
        result.put("trainStates", powerSupply.stepPowerSupply(
                trains.values(), totalTractionEnergyKwh, totalRegenEnergyKwh));
        return result;
    }

    public Map<String, Object> getNetworkStates() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("trainNetworks",
                networkService.stepNetworkUpdate(trains.values(), simulationTimeSeconds));
        return result;
    }

    public void resetSimulation() {
        simulationRunning = false;
        simulationTimeSeconds = 0;
        trains.clear();
        fusedOnboardReportRevisions.clear();
        lastDepartureAuthorizationByDirection.clear();
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
        tractionStates.clear();
        brakeStates.clear();
        lastSampleTime = -10;
        lastEnergyResult = null;
        lastMpcResults = null;
        consistMap.clear();
        energyHistory.clear();
        coastingSavedKwh = 0;
        lastEnergyHistoryTime = -10;
        dispatchEngine.clearLogs();
        movementAuthorityRegistry.clear();
        redisDataBus.clearSimulationData();
    }
}
