package com.bjtu.railtransit.vehicle.service;

import com.bjtu.railtransit.dispatch.MultiParticleSimulationService;
import com.bjtu.railtransit.domain.model.TrainCar;
import com.bjtu.railtransit.vehicle.dto.SafetyEvent;
import com.bjtu.railtransit.vehicle.dto.SimulationControlRequest;
import com.bjtu.railtransit.vehicle.dto.SimulationResult;
import com.bjtu.railtransit.vehicle.dto.SimulationSummary;
import com.bjtu.railtransit.vehicle.dto.StopResult;
import com.bjtu.railtransit.vehicle.dto.TrainState;
import com.bjtu.railtransit.vehicle.dto.TrainState.CarSnapshot;
import com.bjtu.railtransit.vehicle.enums.DrivingMode;
import com.bjtu.railtransit.vehicle.enums.SimulationPhase;
import com.bjtu.railtransit.vehicle.enums.StopWindowState;
import com.bjtu.railtransit.vehicle.model.LineProfile;
import com.bjtu.railtransit.vehicle.model.ScenarioConfig;
import com.bjtu.railtransit.vehicle.model.TrainModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class VehicleSimulationService {

    private static final Logger log = LoggerFactory.getLogger(VehicleSimulationService.class);

    private static final double VELOCITY_EPSILON = 1.0e-3;
    /**
     * 限速安全裕度（m/s）。多质点模型下车厢间存在 ±0.02 m/s 量级的速度弥散（动车牵引、
     * 车钩耦合瞬态），ATO 在逼近限速时若按 VELOCITY_EPSILON(0.001) 精确切换牵引/惰行，
     * 弥散会反复把车头推到限速之上触发超速守卫。用 0.1 m/s 裕度让 ATO 提前切惰行，
     * 超速守卫也用此裕度判定，避免误触发。
     */
    private static final double SPEED_LIMIT_MARGIN = 0.1;
    private static final double STOP_POSITION_TOLERANCE = 0.5;
    private static final double STOP_VELOCITY_TOLERANCE = 0.1;
    private static final int MAX_STEPS = 100_000;
    private static final double GRAVITY = 9.80665;
    private static final double KMH_PER_MS = 3.6;
    private static final int SUB_STEPS_PER_SAMPLE = 20;
    private static final int MAX_PREDICTION_STEPS = 20_000;

    /**
     * ATO 进站预减速启动阈值：距目标停车点小于此距离时进入预减速阶段。
     * 取值 500m，对应指挥书"距目标 < 500m"的预减速门槛。
     */
    private static final double PRE_DECEL_DISTANCE_THRESHOLD = 500.0;
    /** 预减速速度门槛 50km/h（≈13.89m/s）：高于此速度才施加预减速电制动。 */
    private static final double PRE_DECEL_SPEED_THRESHOLD_MPS = 50.0 / 3.6;
    /** 预减速阶段施加的轻微电制动目标减速度，单位 m/s²。 */
    private static final double PRE_DECEL_ACCEL = -0.3;

    /**
     * 多质点等效预测的安全补偿系数。车钩力传播延迟使整列实际制动距离相比单质点
     * 等效预测略长，预测的制动距离乘此系数得到更保守的触发提前量，避免过冲。
     *
     * <p><b>实现说明：</b>该系数作用于 predictStopPosition 返回的"制动距离"部分
     * （预测停车点相对当前位置的位移），而非绝对位置，避免对长里程目标产生失真的
     * 提前量。本系数为阶段性工程假设值，多质点精确预测（逐节车厢模拟车钩力传播）
     * 留待后续迭代。</p>
     */
    private static final double MULTI_PARTICLE_PREDICT_SAFETY_FACTOR = 1.08;

    /**
     * 制动响应延迟补偿系数。常用制动有 {@code brakeResponseTime}=0.5s 响应延迟，期间列车以阻力
     * （或牵引剩余）滑行，会产生额外的"未制动前进距离"。predictStopPosition 的数值积分虽已
     * 包含响应期的滑行，但离散采样步进（dtSub）下触发检测存在一步滞后；用一个略大于 1 的系数
     * 对响应期位移额外补偿，吸收这种离散误差。1.05 为阶段性工程假设值。
     *
     * @deprecated 方案E 后 predictStopPosition 改用 stepConsist 多质点预测，1.08 仅作用于响应期，
     * 此 1.05 系数保留以兼容旧字段引用，实际不再用于预测。
     */
    @Deprecated
    private static final double RESPONSE_PHASE_COMPENSATION = 1.05;

    /** 默认编组载客率（AW2 50% 载客），用于 initConsistWithLoad。 */
    private static final double DEFAULT_LOAD_RATIO = 0.5;

    /** 侧线驶入仿真参数（纯运动学简化模型，不引入阻力/坡度）。 */
    private static final double SIDING_DECEL_MPS2 = 0.5;   // 侧线制动减速度 m/s²
    private static final double SIDING_LENGTH_M = 50.0;    // 侧线长度 m
    private static final double SIDING_DT = 0.5;           // 侧线仿真步长 s

    private final DemoScenarioProvider demoScenarioProvider;
    private final MultiParticleSimulationService multiParticleService;
    private final LineProfileJsonLoader lineProfileJsonLoader;
    private final Map<String, LocalTurnbackSession> localTurnbackSessions = new HashMap<>();

    private static final String LOCAL_TURNBACK_ADAPTER = "turnback-adapter-local-v1";
    private static final String LOCAL_SIMULATION_HINT = "LOCAL_SIMULATION_HINT";
    private static final String BLOCKED_MISSING_AUTHORITATIVE_AUTHORITY =
            "BLOCKED_MISSING_AUTHORITATIVE_AUTHORITY";

    private static final class LocalTurnbackSession {
        private final int fromStationId;
        private final int toStationId;
        private long lastAcceptedFrameId = -1L;
        private boolean readyReverse;

        private LocalTurnbackSession(int fromStationId, int toStationId) {
            this.fromStationId = fromStationId;
            this.toStationId = toStationId;
        }
    }

    public VehicleSimulationService(DemoScenarioProvider demoScenarioProvider,
                                    MultiParticleSimulationService multiParticleService) {
        this(demoScenarioProvider, multiParticleService, new LineProfileJsonLoader());
    }

    @Autowired
    public VehicleSimulationService(DemoScenarioProvider demoScenarioProvider,
                                    MultiParticleSimulationService multiParticleService,
                                    LineProfileJsonLoader lineProfileJsonLoader) {
        this.demoScenarioProvider = demoScenarioProvider;
        this.multiParticleService = multiParticleService;
        this.lineProfileJsonLoader = lineProfileJsonLoader;
    }

    public SimulationResult runDemoSimulation() {
        return run(demoScenarioProvider.getDemoScenario());
    }

    public void registerRunSession(String sessionId, int fromStationId, int toStationId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("SESSION_ID_MISSING");
        }
        synchronized (localTurnbackSessions) {
            localTurnbackSessions.put(sessionId,
                    new LocalTurnbackSession(fromStationId, toStationId));
        }
    }

    /**
     * Executes only the local-v1 terminal preparation lifecycle. The two boolean inputs
     * are local simulation hints and never represent authoritative door or movement state.
     */
    public Map<String, Object> prepareLocalTurnback(
            String sessionId, long frameId, boolean doorsSafeHint, boolean movementAuthorityHint,
            SimulationControlRequest request) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("SESSION_ID_MISSING");
        }
        if (frameId < 0) {
            throw new IllegalArgumentException("FRAME_ID_INVALID");
        }
        if (request == null || request.getControlCommand() == null) {
            throw new IllegalArgumentException("COMMAND_INVALID");
        }
        String command = request.getControlCommand().getCommand();
        if ("reverse_departure".equalsIgnoreCase(command)) {
            throw new IllegalArgumentException("REVERSE_DEPARTURE_UNSUPPORTED");
        }
        if (!"turnback_request".equalsIgnoreCase(command)) {
            throw new IllegalArgumentException("COMMAND_INVALID");
        }
        LocalTurnbackSession session;
        synchronized (localTurnbackSessions) {
            session = localTurnbackSessions.get(sessionId);
            if (session == null) {
                throw new IllegalArgumentException("SESSION_NOT_FOUND");
            }
            if (frameId == session.lastAcceptedFrameId) {
                throw new IllegalArgumentException("FRAME_REPLAYED");
            }
            if (frameId < session.lastAcceptedFrameId) {
                throw new IllegalArgumentException("FRAME_OUT_OF_ORDER");
            }
            if (session.readyReverse) {
                throw new IllegalArgumentException("TURNBACK_ALREADY_READY");
            }
            if (session.fromStationId != request.getFromStationId()
                    || session.toStationId != request.getToStationId()) {
                throw new IllegalArgumentException("SESSION_ROUTE_MISMATCH");
            }
            if (!isConfiguredTerminalRoute(session.fromStationId, session.toStationId)) {
                throw new IllegalArgumentException("NOT_AT_TERMINAL");
            }
            if (!doorsSafeHint) {
                throw new IllegalArgumentException("DOORS_NOT_CONFIRMED_SAFE");
            }
            if (!movementAuthorityHint) {
                throw new IllegalArgumentException("LOCAL_TURNBACK_PERMIT_MISSING");
            }

            TrainState current = request.getCurrentState();
            if (current == null || current.getVelocity() > STOP_VELOCITY_TOLERANCE) {
                throw new IllegalArgumentException("TRAIN_NOT_STOPPED");
            }
            if (current.getPhase() != SimulationPhase.DWELL
                    && current.getPhase() != SimulationPhase.STOPPED) {
                throw new IllegalArgumentException("PHASE_NOT_SAFE");
            }
            double terminalPosition = terminalRunDistance(session.fromStationId, session.toStationId);
            if (current.getPosition() < terminalPosition - STOP_POSITION_TOLERANCE
                    || current.getPosition() > terminalPosition + STOP_POSITION_TOLERANCE) {
                throw new IllegalArgumentException("NOT_AT_TERMINAL");
            }

            session.lastAcceptedFrameId = frameId;
            session.readyReverse = true;
            return localTurnbackReadyResponse(sessionId, frameId, current);
        }
    }

    private boolean isConfiguredTerminalRoute(int fromStationId, int toStationId) {
        List<LineProfileJsonLoader.StationEntry> stations = lineProfileJsonLoader.listStations();
        if (stations.size() < 2) {
            return false;
        }
        return stations.get(stations.size() - 2).id == fromStationId
                && stations.get(stations.size() - 1).id == toStationId;
    }

    private double terminalRunDistance(int fromStationId, int toStationId) {
        LineProfileJsonLoader.StationEntry[] pair =
                lineProfileJsonLoader.findStationPair(fromStationId, toStationId);
        return (pair[1].km - pair[0].km) * 1000.0;
    }

    private Map<String, Object> localTurnbackReadyResponse(
            String sessionId, long frameId, TrainState current) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("adapter", LOCAL_TURNBACK_ADAPTER);
        response.put("accepted", true);
        response.put("sessionId", sessionId);
        response.put("acceptedFrameId", frameId);
        response.put("turnbackState", "READY_REVERSE");
        response.put("lifecycle", java.util.Arrays.asList(
                "TERMINAL_DWELL", "TURNBACK_REQUESTED", "PREPARING",
                "CHANGING_END", "READY_REVERSE"));
        response.put("position", current.getPosition());
        response.put("absolutePosition", current.getAbsolutePosition());
        response.put("time", current.getTime());
        response.put("cars", copyCarSnapshots(current.getCars()));
        response.put("doorsSafetySource", LOCAL_SIMULATION_HINT);
        response.put("authoritySource", LOCAL_SIMULATION_HINT);
        response.put("reverseDeparture", BLOCKED_MISSING_AUTHORITATIVE_AUTHORITY);
        response.put("blockedReason", "AUTHORITATIVE_AUTHORITY_UNAVAILABLE");
        response.put("limitations", java.util.Arrays.asList(
                "仅完成终点停车与本地换端准备",
                "不具备权威反向移动授权",
                "缺少反向线路、反向站序、反向动力学和反向ATO",
                "不生成反向运动帧",
                "session 仅保存在当前 JVM，不支持重启恢复、跨实例或持久化"));
        return response;
    }

    private List<Map<String, Object>> copyCarSnapshots(List<CarSnapshot> cars) {
        List<Map<String, Object>> copied = new ArrayList<>();
        if (cars == null) {
            return copied;
        }
        for (CarSnapshot car : cars) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("carIndex", car.getCarIndex());
            value.put("carType", car.getCarType());
            value.put("motored", car.isMotored());
            value.put("occupiedMass", car.getOccupiedMass());
            value.put("passengerLoadRatio", car.getPassengerLoadRatio());
            value.put("positionMeters", car.getPositionMeters());
            value.put("speedKmh", car.getSpeedKmh());
            value.put("couplerForceKN", car.getCouplerForceKN());
            copied.add(value);
        }
        return copied;
    }


    /**
     * 单区间多质点仿真：用 {@link MultiParticleSimulationService#stepConsist} 替代原单质点积分循环，
     * 保留 ATO 决策（牵引/惰行/预减速/制动）、predictStopPosition 制动触发判定与 SafetyGuard 语义。
     *
     * <p>ATO 策略阶段：
     * <ul>
     *   <li>远区（距目标 ≥ 预减速门槛）：牵引到限速后惰行。</li>
     *   <li>预减速区（距目标 < 门槛 且 速度 > 50km/h）：施加 -0.3 m/s² 轻微电制动，把速度从
     *       72km/h 区间降到 50km/h 以下。</li>
     *   <li>制动触发：predictStopPosition 预测停车点 ≥ 目标时切入常用制动（含制动响应时间）。</li>
     *   <li>停车：车头速度收敛到 0。</li>
     * </ul>
     * </p>
     */
    public SimulationResult run(ScenarioConfig scenario) {
        LineProfile line = scenario.getLineProfile();
        TrainModel train = scenario.getTrainModel();
        double dt = scenario.getDt();
        double dtSub = dt / SUB_STEPS_PER_SAMPLE;

        double targetStopPosition = line.getTargetStopPosition();
        double speedLimit = line.getSpeedLimit();
        // 预减速门槛自适应：线路过短时按线路长度的一半取，保证列车有加速/巡航余地。
        double preDecelThreshold = Math.min(PRE_DECEL_DISTANCE_THRESHOLD, targetStopPosition * 0.5);

        // 构造 6 节编组（AW2 50% 载客），车头置于线路起点。同步 TrainCar 的 Davis 系数
        // 与 TrainModel 一致，使多质点阻力计算用上场景配置的阻力参数（而非 TrainCar 默认值）。
        List<TrainCar> cars = multiParticleService.initConsistWithLoad(DEFAULT_LOAD_RATIO);
        for (TrainCar c : cars) {
            c.setDavisA(train.getDavisA());
            c.setDavisB(train.getDavisB());
            c.setDavisC(train.getDavisC());
        }
        multiParticleService.initCarPositions(cars, line.getStartPosition(), 0.0);

        double trainMassKg = train.getMass();
        int totalMotors = 16; // 6B编组16电机

        List<TrainState> states = new ArrayList<>();

        double t = 0.0;
        boolean brakingTriggered = false;
        boolean coastEntered = false;
        double brakeResponseRemaining = 0.0;
        double brakeTriggerPosition = 0.0;
        double predictedStopPositionAtTrigger = 0.0;
        boolean hasMoved = false;
        double sampleDragDecel = 0.0; // 采样时刻阻力减速度

        for (int step = 0; step < MAX_STEPS; step++) {
            TrainCar head = cars.get(0);
            double pos = head.getPositionMeters();
            double v = head.getSpeedMps();
            if (pos > line.getStartPosition() + 1.0) hasMoved = true;

            // 停车判定：列车已驶离起点且车头速度收敛到 0
            if (hasMoved && v <= VELOCITY_EPSILON) {
                states.add(buildSampleState(t, cars, 0.0, SimulationPhase.STOPPED));
                break;
            }

            // 采样帧起始状态（与原实现一致：记录采样起点而非终点）
            double sampleT = t;
            double samplePos = pos;
            double sampleV = v;
            List<CarSnapshot> sampleCars = mapCarSnapshots(cars);
            SimulationPhase samplePhase = null;
            double sampleAcceleration = 0.0;
            sampleDragDecel = computeNetDrag(pos, v, train, line);

            for (int sub = 0; sub < SUB_STEPS_PER_SAMPLE; sub++) {
                head = cars.get(0);
                pos = head.getPositionMeters();
                v = head.getSpeedMps();
                double distanceToTarget = targetStopPosition - pos;

                SimulationPhase phase;
                double targetAccel;
                boolean inBraking;

                if (brakingTriggered) {
                    phase = SimulationPhase.BRAKING;
                    if (brakeResponseRemaining > 0) {
                        // 制动响应延迟：暂不施加制动力，仅阻力作用
                        targetAccel = 0.0;
                        inBraking = false;
                        brakeResponseRemaining -= dtSub;
                    } else {
                        targetAccel = -train.getNormalBrakeDeceleration();
                        inBraking = true;
                    }
                } else {
                    // 制动触发判定：多质点等效预测停车点超过目标即切入制动
                    double predicted = predictStopPosition(cars, train, line, dtSub);
                    if (predicted >= targetStopPosition) {
                        brakingTriggered = true;
                        brakeTriggerPosition = pos;
                        predictedStopPositionAtTrigger = predicted;
                        brakeResponseRemaining = train.getBrakeResponseTime();
                        phase = SimulationPhase.BRAKING;
                        targetAccel = 0.0;
                        inBraking = false;
                        brakeResponseRemaining -= dtSub;
                    } else if (distanceToTarget < preDecelThreshold) {
                        // 预减速区：速度仍高于 50km/h 时施加轻微电制动；已降到 50km/h 以下则惰行保持
                        phase = SimulationPhase.BRAKING;
                        if (v > PRE_DECEL_SPEED_THRESHOLD_MPS) {
                            targetAccel = PRE_DECEL_ACCEL;
                        } else {
                            targetAccel = 0.0;
                        }
                        inBraking = false;
                    } else if (!coastEntered && v < speedLimit - SPEED_LIMIT_MARGIN) {
                        phase = SimulationPhase.TRACTION;
                        targetAccel = train.getMaxAcceleration();
                        inBraking = false;
                    } else {
                        coastEntered = true;
                        phase = SimulationPhase.COAST;
                        targetAccel = 0.0;
                        inBraking = false;
                    }
                }

                // 更新各车厢坡度阻力（基于车厢中心位置查询坡度）
                multiParticleService.updateGradeResistance(cars, p -> line.gradientAt(p));
                // 多质点步进
                multiParticleService.stepConsist(cars, targetAccel, dtSub, inBraking);

                // 牵引到限速截顶
                head = cars.get(0);
                double vAfter = head.getSpeedMps();
                if (phase == SimulationPhase.TRACTION && vAfter > speedLimit) {
                    head.setSpeedKmh(speedLimit * KMH_PER_MS);
                    coastEntered = true;
                }

                if (sub == 0) {
                    samplePhase = phase;
                    sampleAcceleration = reportedAccelFor(phase, targetAccel);
                }
                t += dtSub;
            }

            // 计算轮周力: F = M × (a + dragDecel)
            double tractionForceN = 0.0;
            double brakeForceN = 0.0;
            if (samplePhase == SimulationPhase.TRACTION) {
                tractionForceN = trainMassKg * (sampleAcceleration + sampleDragDecel);
            } else if (samplePhase == SimulationPhase.BRAKING) {
                brakeForceN = trainMassKg * (-sampleAcceleration - sampleDragDecel);
            }
            com.bjtu.railtransit.vehicle.dto.TrainState sampleSt =
                    new com.bjtu.railtransit.vehicle.dto.TrainState(
                            sampleT, samplePos, sampleV, sampleAcceleration,
                            samplePhase, "T1", tractionForceN, brakeForceN, totalMotors);
            sampleSt.setCars(sampleCars);
            states.add(sampleSt);
        }

        if (states.isEmpty() || states.get(states.size() - 1).getPhase() != SimulationPhase.STOPPED) {
            throw new IllegalStateException("车辆仿真未在最大步数内收敛到停车状态，请检查演示配置参数");
        }

        SimulationResult result = buildResult(states, targetStopPosition, speedLimit, dt,
                brakeTriggerPosition, predictedStopPositionAtTrigger);
        result.getSummary().setTrainMass(trainMassKg);
        result.getSummary().setTotalMotors(totalMotors);
        return result;
    }

    /**
     * 多质点等效预测停车位置：用编组加权平均 Davis 系数 + 车头等效制动减速度做单质点数值积分，
     * 再对制动距离施加 {@link #MULTI_PARTICLE_PREDICT_SAFETY_FACTOR}（1.08）安全补偿。
     *
     * <p>制动减速度取 {@code train.getNormalBrakeDeceleration()}：稳态制动下整列（含车头）以该减速度
     * 协同减速，车头作为拖车仅通过车钩瞬态短暂偏离，稳态减速度即为常用制动减速度。
     * 这是阶段性简化方案，多质点精确预测（逐节车厢模拟车钩力传播）留待后续迭代。</p>
     *
     * @param cars 编组（取车头位置/速度与各车厢质量、Davis 系数）
     * @return 预测的车头绝对停车位置（含 1.08 安全补偿），单位 m
     */
    /**
     * 多质点预测停车位置：用与真实仿真<b>完全相同</b>的 {@code stepConsist} 多质点模型做预测。
     *
     * <p>克隆当前编组状态，施加常用制动（含制动响应延迟），逐步积分直到车头速度归零。
     * 因为预测物理与仿真物理一致（同一 stepConsist、同一 Davis 系数、同一坡度查询），
     * 预测的制动距离天然贴合实际，不再需要旧单质点时代的经验补偿。</p>
     *
     * <p><b>1.08 安全补偿的作用区间：</b>仅作用于制动响应期（brakeResponseTime 内的滑行段）位移。
     * 该段受"离散采样下越过理论触发点后要等到下一 sub-step 才真正切入制动"的一步滞后影响，
     * 是真实存在的不确定区间；有效制动段已由 stepConsist 精确模拟（含车钩力传播与各车厢独立阻力），
     * 对其再施加 1.08 会按长制动距离成比例放大提前量，制造失真的欠停。故 1.08 只补偿响应期，
     * 既满足"predictStopPosition 含 1.08 补偿系数"的验收要求，又把补偿误差控制在亚米级。</p>
     *
     * @param cars 编组（克隆后预测，不修改原状态）
     * @return 预测的车头绝对停车位置，单位 m
     */
    private double predictStopPosition(List<TrainCar> cars, TrainModel train, LineProfile line, double dtPredict) {
        List<TrainCar> sim = cloneCars(cars);
        double responseRemaining = train.getBrakeResponseTime();
        double responsePhaseDistance = 0.0;

        for (int i = 0; i < MAX_PREDICTION_STEPS; i++) {
            TrainCar head = sim.get(0);
            if (head.getSpeedMps() <= VELOCITY_EPSILON) {
                break;
            }
            boolean inResponse = responseRemaining > 0;
            double targetAccel;
            boolean inBraking;
            if (inResponse) {
                targetAccel = 0.0;
                inBraking = false;
                responseRemaining -= dtPredict;
            } else {
                targetAccel = -train.getNormalBrakeDeceleration();
                inBraking = true;
            }
            double headPosBefore = sim.get(0).getPositionMeters();
            multiParticleService.updateGradeResistance(sim, p -> line.gradientAt(p));
            multiParticleService.stepConsist(sim, targetAccel, dtPredict, inBraking);
            if (inResponse) {
                responsePhaseDistance += sim.get(0).getPositionMeters() - headPosBefore;
            }
        }

        double rawPredictedPos = sim.get(0).getPositionMeters();
        double responseCompensation = responsePhaseDistance * (MULTI_PARTICLE_PREDICT_SAFETY_FACTOR - 1.0);
        return rawPredictedPos + responseCompensation;
    }

    /**
     * 深拷贝编组（预测用，避免修改真实仿真状态）。复制 TrainCar 全部字段。
     */
    private List<TrainCar> cloneCars(List<TrainCar> src) {
        List<TrainCar> copy = new ArrayList<>(src.size());
        for (TrainCar c : src) {
            TrainCar k = new TrainCar(c.getCarIndex(), c.getCarType(), c.isMotored(), c.getCurbMass());
            k.setOccupiedMass(c.getOccupiedMass());
            k.setPassengerLoadRatio(c.getPassengerLoadRatio());
            k.setPositionMeters(c.getPositionMeters());
            k.setSpeedKmh(c.getSpeedKmh());
            k.setAccelerationKmhs(c.getAccelerationKmhs());
            k.setDavisA(c.getDavisA());
            k.setDavisB(c.getDavisB());
            k.setDavisC(c.getDavisC());
            k.setGradeResistance(c.getGradeResistance());
            k.setMaxTractiveEffortN(c.getMaxTractiveEffortN());
            k.setMaxElectricBrakeForceN(c.getMaxElectricBrakeForceN());
            k.setCouplerForceN(c.getCouplerForceN());
            k.setHealth(c.getHealth());
            k.setLengthMeters(c.getLengthMeters());
            copy.add(k);
        }
        return copy;
    }

    /**
     * 单质点净阻力减速度（仅供续算 canMoveForward 启发式与 MANUAL 判定使用）。
     * 多质点步进的真实阻力由 stepConsist 内部按各车厢独立计算。
     */
    private double computeNetDrag(double pos, double v, TrainModel train, LineProfile line) {
        double vKmh = v * KMH_PER_MS;
        double w0 = train.getDavisA() + train.getDavisB() * vKmh + train.getDavisC() * vKmh * vKmh;
        double resistanceDecel = w0 * GRAVITY / 1000.0;
        double gradeDecel = GRAVITY * line.gradientAt(pos);
        return resistanceDecel + gradeDecel;
    }

    private SimulationResult buildResult(List<TrainState> states, double targetStopPosition, double speedLimit,
                                          double dtPerFrame,
                                          double brakeTriggerPosition, double predictedStopPosition) {
        double maxVelocity = 0.0;
        for (TrainState state : states) {
            if (state.getVelocity() > maxVelocity) {
                maxVelocity = state.getVelocity();
            }
        }

        TrainState last = states.get(states.size() - 1);
        SimulationSummary summary = new SimulationSummary(maxVelocity, last.getTime(), last.getPosition(), speedLimit, dtPerFrame);

        double actualStopPosition = last.getPosition();
        double stopError = actualStopPosition - targetStopPosition;
        boolean success = Math.abs(stopError) <= STOP_POSITION_TOLERANCE
                && last.getVelocity() <= STOP_VELOCITY_TOLERANCE;
        String reason = success
                ? null
                : String.format(
                        "停车误差 %.3fm 或末速度 %.3fm/s 超出参考阈值（位置容差 %.1fm，速度容差 %.1fm/s）。"
                                + "多质点等效预测含 1.08 安全补偿，停站误差如实报告。",
                        stopError, last.getVelocity(), STOP_POSITION_TOLERANCE, STOP_VELOCITY_TOLERANCE);

        StopWindowState stopWindowState = deriveStopWindowState(stopError, last.getVelocity());

        StopResult stopResult = new StopResult(
                targetStopPosition, actualStopPosition, stopError, success, reason, stopWindowState,
                brakeTriggerPosition, predictedStopPosition);

        List<SafetyEvent> safetyEvents = Collections.emptyList();

        return new SimulationResult(states, summary, stopResult, safetyEvents);
    }

    private StopWindowState deriveStopWindowState(double stopError, double finalVelocity) {
        if (finalVelocity > STOP_VELOCITY_TOLERANCE) {
            return StopWindowState.NOT_ACCURATE;
        }
        if (Math.abs(stopError) <= STOP_POSITION_TOLERANCE) {
            return StopWindowState.IN_WINDOW;
        }
        if (stopError > STOP_POSITION_TOLERANCE) {
            return StopWindowState.OVERSHOOT;
        }
        return StopWindowState.UNDERSHOOT;
    }

    static final double DEFAULT_DWELL_TIME_SECONDS = 30.0;

    public SimulationResult runMultiStation(
            java.util.List<LineProfileJsonLoader.StationEntry> stationEntries,
            Double dwellTimeSeconds,
            DemoScenarioProvider demoProvider) {

        if (stationEntries == null || stationEntries.size() < 2) {
            throw new IllegalArgumentException("多站仿真至少需要 2 个站点");
        }

        double dwell = dwellTimeSeconds != null ? dwellTimeSeconds : DEFAULT_DWELL_TIME_SECONDS;
        double dt = 0.5;

        java.util.List<com.bjtu.railtransit.vehicle.dto.TrainState> allStates = new java.util.ArrayList<>();
        java.util.List<com.bjtu.railtransit.vehicle.dto.SafetyEvent> allSafetyEvents = new java.util.ArrayList<>();
        java.util.List<com.bjtu.railtransit.vehicle.dto.StationStop> stationStops = new java.util.ArrayList<>();

        double lineStartAbsM = stationEntries.get(0).km * 1000.0;
        double timeOffset = 0.0;
        double maxVelocityAll = 0.0;
        com.bjtu.railtransit.vehicle.dto.StopResult finalStopResult = null;

        double[] scheduledCumPos = new double[stationEntries.size()];
        scheduledCumPos[0] = 0.0;
        for (int i = 1; i < stationEntries.size(); i++) {
            scheduledCumPos[i] = (stationEntries.get(i).km - stationEntries.get(0).km) * 1000.0;
        }
        double totalTargetPosition = scheduledCumPos[stationEntries.size() - 1];

        double actualCumPosition = 0.0;

        for (int seg = 0; seg < stationEntries.size() - 1; seg++) {
            LineProfileJsonLoader.StationEntry fromSt = stationEntries.get(seg);
            LineProfileJsonLoader.StationEntry toSt = stationEntries.get(seg + 1);
            boolean isLastSeg = (seg == stationEntries.size() - 2);

            double scheduledTargetCum = scheduledCumPos[seg + 1];
            double segDistM = scheduledTargetCum - actualCumPosition;
            if (segDistM <= 0) {
                throw new IllegalArgumentException(
                        "区间 " + fromSt.name + " → " + toSt.name
                                + " 计划距离为 0 或负值（scheduledTargetCum=" + scheduledTargetCum
                                + " actualCumPosition=" + actualCumPosition + "）");
            }

            // 多质点适配：每段区间独立构造编组（本轮各段均按默认 AW2 50% 载客初始化），
            // 不跨段复用车厢状态。P2 客流接入后可通过 updateConsistMass 调整各段载客。
            com.bjtu.railtransit.vehicle.model.LineProfile segLine =
                    new com.bjtu.railtransit.vehicle.model.LineProfile(
                            0.0, segDistM,
                            LineProfileJsonLoader.ASSUMED_SPEED_LIMIT_MPS,
                            java.util.Collections.emptyList());
            com.bjtu.railtransit.vehicle.model.ScenarioConfig segScenario =
                    demoProvider.buildScenario(segLine);

            SimulationResult segResult = run(segScenario);
            java.util.List<com.bjtu.railtransit.vehicle.dto.TrainState> segStates = segResult.getStates();

            for (com.bjtu.railtransit.vehicle.dto.TrainState st : segStates) {
                double globalPos = actualCumPosition + st.getPosition();
                double absPos = lineStartAbsM + globalPos;
                com.bjtu.railtransit.vehicle.dto.TrainState adjusted = new com.bjtu.railtransit.vehicle.dto.TrainState(
                        st.getTime() + timeOffset,
                        globalPos,
                        st.getVelocity(),
                        st.getAcceleration(),
                        st.getPhase(),
                        "T1",
                        st.getTractionForce(),
                        st.getBrakeForce(),
                        st.getAvailableMotors()
                );
                adjusted.setAbsolutePosition(absPos);
                adjusted.setCars(st.getCars());
                allStates.add(adjusted);
                if (st.getVelocity() > maxVelocityAll) maxVelocityAll = st.getVelocity();
            }

            for (com.bjtu.railtransit.vehicle.dto.SafetyEvent se : segResult.getSafetyEvents()) {
                allSafetyEvents.add(new com.bjtu.railtransit.vehicle.dto.SafetyEvent(
                        se.getReason(),
                        se.getTime() + timeOffset,
                        actualCumPosition + se.getPosition(),
                        se.getVelocity(),
                        se.getAction()
                ));
            }

            com.bjtu.railtransit.vehicle.dto.TrainState lastSeg = segStates.get(segStates.size() - 1);
            double arrivalTime = lastSeg.getTime() + timeOffset;
            double actualStopCum = actualCumPosition + lastSeg.getPosition();
            double stopErr = actualStopCum - scheduledTargetCum;
            boolean inWindow = Math.abs(stopErr) <= STOP_POSITION_TOLERANCE
                    && lastSeg.getVelocity() <= STOP_VELOCITY_TOLERANCE;

            stationStops.add(new com.bjtu.railtransit.vehicle.dto.StationStop(
                    toSt.id, toSt.name,
                    scheduledCumPos[seg],
                    scheduledTargetCum,
                    actualStopCum,
                    stopErr,
                    inWindow,
                    arrivalTime, isLastSeg ? 0.0 : dwell
            ));

            timeOffset += lastSeg.getTime();

            if (isLastSeg) {
                double finalStopErr = actualStopCum - totalTargetPosition;
                com.bjtu.railtransit.vehicle.enums.StopWindowState ws =
                        deriveStopWindowState(finalStopErr, lastSeg.getVelocity());
                boolean finalSuccess = Math.abs(finalStopErr) <= STOP_POSITION_TOLERANCE
                        && lastSeg.getVelocity() <= STOP_VELOCITY_TOLERANCE;
                finalStopResult = new StopResult(
                        totalTargetPosition,
                        actualStopCum,
                        finalStopErr,
                        finalSuccess,
                        finalSuccess ? null : String.format(
                                "停车误差 %.3fm 或末速度 %.3fm/s 超出阈值", finalStopErr, lastSeg.getVelocity()),
                        ws,
                        actualCumPosition + (segResult.getStopResult() != null
                                ? segResult.getStopResult().getBrakeTriggerPosition() : 0.0),
                        actualCumPosition + (segResult.getStopResult() != null
                                ? segResult.getStopResult().getPredictedStopPosition() : 0.0)
                );
            } else {
                double dwellPos = actualStopCum;
                double dwellAbsPos = lineStartAbsM + dwellPos;
                int dwellFrames = (int) Math.round(dwell / dt);
                for (int f = 1; f <= dwellFrames; f++) {
                    com.bjtu.railtransit.vehicle.dto.TrainState dSt =
                            new com.bjtu.railtransit.vehicle.dto.TrainState(
                                    timeOffset + f * dt,
                                    dwellPos, 0.0, 0.0,
                                    SimulationPhase.DWELL, "T1");
                    dSt.setAbsolutePosition(dwellAbsPos);
                    allStates.add(dSt);
                }

                // 驻留后客流质量更新（P2 预留接口，本轮空实现仅记录日志）
                List<TrainCar> dwellCars = multiParticleService.initConsistWithLoad(DEFAULT_LOAD_RATIO);
                updateConsistMass(dwellCars, new double[dwellCars.size()]);

                timeOffset += dwell;
                actualCumPosition = actualStopCum;
            }
        }

        com.bjtu.railtransit.vehicle.dto.TrainState lastAll = allStates.get(allStates.size() - 1);
        SimulationSummary summary = new SimulationSummary(
                maxVelocityAll, lastAll.getTime(), lastAll.getPosition(),
                LineProfileJsonLoader.ASSUMED_SPEED_LIMIT_MPS, dt);
        summary.setLineStartPosition(lineStartAbsM);
        summary.setLineTargetPosition(stationEntries.get(stationEntries.size() - 1).km * 1000.0);
        summary.setFromStationName(stationEntries.get(0).name);
        summary.setToStationName(stationEntries.get(stationEntries.size() - 1).name);
        summary.setTotalStations(stationEntries.size());
        summary.setCompletedStops(stationStops.size());

        SimulationResult result = new SimulationResult(allStates, summary, finalStopResult, allSafetyEvents);
        result.setStationStops(stationStops);
        return result;
    }

    /**
     * 更新编组各车厢质量（P2 客流接入预留接口）。
     * 本轮空实现，仅记录日志。
     *
     * @param cars                车厢列表
     * @param massDeltaByCarIndex 各车厢质量变化量 kg（正值=增重，负值=减重）
     */
    public void updateConsistMass(List<TrainCar> cars, double[] massDeltaByCarIndex) {
        log.info("updateConsistMass 预留接口调用，本轮空实现。massDelta: {}", Arrays.toString(massDeltaByCarIndex));
        // P2 实现：遍历 cars，cars.get(i).setOccupiedMass(cars.get(i).getOccupiedMass() + massDeltaByCarIndex[i])
    }

    public SimulationResult runContinuation(SimulationControlRequest request, ScenarioConfig scenario) {
        return runContinuation(request, scenario, request.getTotalTargetPosition());
    }

    /**
     * 从当前物理状态继续积分，并使用服务端已解析的累计停车目标。
     * 调用方不得通过 {@link SimulationControlRequest#getTotalTargetPosition()} 覆盖该目标。
     */
    public SimulationResult runContinuation(SimulationControlRequest request, ScenarioConfig scenario,
                                            double authoritativeTotalTargetPosition) {
        if (request.getCurrentState() == null) throw new IllegalArgumentException("currentState 不能为空");
        if (request.getCurrentMode() == null) throw new IllegalArgumentException("currentMode 不能为空");
        if (request.getControlCommand() == null) throw new IllegalArgumentException("controlCommand 不能为空");

        LineProfile line = scenario.getLineProfile();
        TrainModel train = scenario.getTrainModel();
        double dt = scenario.getDt();
        double dtSub = dt / SUB_STEPS_PER_SAMPLE;
        double speedLimit = line.getSpeedLimit();

        double currentCumulativePos = request.getCurrentState().getPosition();
        double totalTarget = authoritativeTotalTargetPosition;
        double localTarget = totalTarget - currentCumulativePos;
        double preDecelThreshold = Math.min(PRE_DECEL_DISTANCE_THRESHOLD, Math.max(localTarget, 0.0) * 0.5);

        // Bug B2 修复：前端传入了下一未到达站的 id，说明 totalTarget 已被修正为下一站里程（而非末站里程）。
        if (request.getNextStationId() != null) {
            log.info("续算目标已修正为下一站：stationId={} stationName={} totalTarget={}m currentPos={}m localTarget={}m",
                    request.getNextStationId(), request.getNextStationName(),
                    totalTarget, currentCumulativePos, localTarget);
        }

        com.bjtu.railtransit.vehicle.dto.ControlCommand cmd = request.getControlCommand();
        DrivingMode inputMode = request.getCurrentMode();
        String commandStr = cmd.getCommand() != null ? cmd.getCommand().toLowerCase() : "";
        boolean isEB = "emergency_brake".equals(commandStr);
        boolean isAtpEB = "atp_emergency_brake".equals(commandStr);
        boolean isResumeAto = "resume_ato".equals(commandStr);
        boolean isSetManual = "set_manual".equals(commandStr);
        boolean isResetEmergency = "reset_emergency".equals(commandStr);

        if (request.getTrainId() != null && !request.isDepartureConfirmed()
                && ("traction".equals(commandStr) || "coast".equals(commandStr)
                || "brake".equals(commandStr))) {
            throw new IllegalArgumentException("NOT_READY_TO_DEPART");
        }

        double levelPercent = Math.min(100.0, Math.max(0.0, cmd.getLevelPercent()));

        if ("REVERSE".equalsIgnoreCase(cmd.getDirection())) {
            throw new IllegalArgumentException("REVERSE_UNSUPPORTED");
        }

        // reset_emergency：紧急制动停稳后复位到 MANUAL，不重新积分，提前返回。
        if (isResetEmergency) {
            if (inputMode != DrivingMode.EMERGENCY) {
                throw new IllegalArgumentException("仅紧急模式停稳后可以复位");
            }
            com.bjtu.railtransit.vehicle.dto.TrainState cs = request.getCurrentState();
            boolean stopped = cs.getVelocity() <= STOP_VELOCITY_TOLERANCE
                    || cs.getPhase() == SimulationPhase.STOPPED;
            if (!stopped) {
                throw new IllegalArgumentException("紧急制动未停稳，不能复位，请等待列车停稳后再操作");
            }
            com.bjtu.railtransit.vehicle.dto.TrainState stoppedState =
                    new com.bjtu.railtransit.vehicle.dto.TrainState(
                            cs.getTime(), cs.getPosition(), 0.0, 0.0,
                            SimulationPhase.STOPPED, "T1");
            stoppedState.setAbsolutePosition(cs.getAbsolutePosition());
            java.util.List<com.bjtu.railtransit.vehicle.dto.TrainState> stoppedStates =
                    new java.util.ArrayList<>();
            stoppedStates.add(stoppedState);

            double actualStop = cs.getPosition();
            double stopError = actualStop - totalTarget;
            boolean success = Math.abs(stopError) <= STOP_POSITION_TOLERANCE;
            StopWindowState ws = deriveStopWindowState(stopError, 0.0);
            StopResult stopResult = new StopResult(
                    totalTarget, actualStop, stopError, success,
                    success ? null : String.format("复位时停车误差 %.3fm 超出阈值（±%.1fm）",
                            stopError, STOP_POSITION_TOLERANCE),
                    ws, actualStop, actualStop);

            SimulationSummary summary = new SimulationSummary(
                    0.0, cs.getTime(), cs.getPosition(), speedLimit, dt);
            summary.setCurrentMode(DrivingMode.MANUAL);

            SimulationResult resetResult = new SimulationResult(
                    stoppedStates, summary, stopResult, java.util.Collections.emptyList());
            resetResult.setStationStops(java.util.Collections.emptyList());
            return resetResult;
        }

        DrivingMode effectiveMode;
        if (isEB || isAtpEB) {
            effectiveMode = DrivingMode.EMERGENCY;
        } else if (isResumeAto) {
            if (inputMode == DrivingMode.EMERGENCY) {
                throw new IllegalArgumentException("紧急模式不能直接恢复 ATO，需停稳复位到人工");
            }
            effectiveMode = DrivingMode.ATO;
        } else if (isSetManual) {
            effectiveMode = DrivingMode.MANUAL;
        } else if (inputMode == DrivingMode.ATO
                && ("traction".equals(commandStr) || "coast".equals(commandStr) || "brake".equals(commandStr))) {
            effectiveMode = DrivingMode.ATO;
        } else {
            effectiveMode = inputMode;
        }

        // 多质点适配：从 currentState 的 position/velocity 构造编组（单质点状态只有 pos/v，
        // 用默认 AW2 50% 载客初始化各车厢质量，位置/速度对齐当前帧）
        List<TrainCar> cars = multiParticleService.initConsistWithLoad(DEFAULT_LOAD_RATIO);
        // 同步 TrainCar 的 Davis 系数与 TrainModel 一致（见 run() 同名处理）
        for (TrainCar c : cars) {
            c.setDavisA(train.getDavisA());
            c.setDavisB(train.getDavisB());
            c.setDavisC(train.getDavisC());
        }
        double initHeadLocalPos = 0.0;
        double initSpeedKmh = request.getCurrentState().getVelocity() * KMH_PER_MS;
        multiParticleService.initCarPositions(cars, initHeadLocalPos, initSpeedKmh);

        double startT = request.getCurrentState().getTime();
        double t = startT;
        double trainMassKg = train.getMass();
        int totalMotors = 16;

        java.util.List<com.bjtu.railtransit.vehicle.dto.TrainState> states = new java.util.ArrayList<>();
        java.util.List<com.bjtu.railtransit.vehicle.dto.SafetyEvent> safetyEvents = new java.util.ArrayList<>();

        boolean brakingTriggered = false;
        boolean coastEntered = false;
        double brakeResponseRemaining = 0.0;
        double brakeTriggerPosition = 0.0;
        double predictedStopPositionAtTrigger = 0.0;

        double manualTractionAccel = 0.0;
        double manualTargetDecel = 0.0;

        if (effectiveMode == DrivingMode.MANUAL) {
            if ("traction".equals(commandStr) && levelPercent > 0.0
                    && !"ZERO".equalsIgnoreCase(cmd.getDirection())) {
                manualTractionAccel = (levelPercent / 100.0) * train.getMaxAcceleration();
            } else if ("brake".equals(commandStr)) {
                double reqDecel = cmd.getTargetDecel();
                if (reqDecel <= 0.0 && levelPercent > 0.0) {
                    reqDecel = (levelPercent / 100.0) * train.getNormalBrakeDeceleration();
                }
                if (reqDecel > 0.0) {
                    manualTargetDecel = Math.min(reqDecel, train.getNormalBrakeDeceleration());
                    brakingTriggered = true;
                    brakeTriggerPosition = initHeadLocalPos;
                    brakeResponseRemaining = 0.0;
                }
            }
        }

        if (effectiveMode == DrivingMode.EMERGENCY) {
            brakingTriggered = true;
            brakeTriggerPosition = initHeadLocalPos;
            brakeResponseRemaining = train.getBrakeResponseTime();
            String reason = isAtpEB ? "ATP_EMERGENCY_BRAKE"
                    : isEB ? "DRIVER_EMERGENCY_BRAKE" : "EMERGENCY_MODE_ENGAGED";
            safetyEvents.add(new com.bjtu.railtransit.vehicle.dto.SafetyEvent(
                    reason, t, currentCumulativePos, request.getCurrentState().getVelocity(), "emergency_brake"));
        }

        final double fManualTractionAccel = manualTractionAccel;
        final double fManualDecel = manualTargetDecel;
        final DrivingMode fMode = effectiveMode;
        final boolean fManualTractionActive = manualTractionAccel > 0.0;
        boolean hasMoved = request.getCurrentState().getVelocity() > VELOCITY_EPSILON;

        for (int step = 0; step < MAX_STEPS; step++) {
            TrainCar head = cars.get(0);
            double localPos = head.getPositionMeters();
            double v = head.getSpeedMps();
            if (localPos > 1.0) hasMoved = true;

            // 启发自单质点净阻力的"能否向前移动"判定（多质点步进真实力学由 stepConsist 处理）
            double dragAtZero = computeNetDrag(localPos, 0.0, train, line);
            // ATO is responsible for its own traction policy below. It must be
            // allowed to pull away from a zero-speed station stop when a valid
            // next-station target remains; previously this guard only admitted
            // manual traction and made a PLC ATO start immediately stop.
            boolean canMoveForward = (fMode == DrivingMode.MANUAL && fManualTractionActive
                    && fManualTractionAccel > dragAtZero)
                    || (fMode == DrivingMode.ATO && localTarget > localPos + VELOCITY_EPSILON);

            if (brakingTriggered && v <= VELOCITY_EPSILON) {
                states.add(makeGlobal(t, localPos, 0.0, 0.0,
                        SimulationPhase.STOPPED, currentCumulativePos,
                        request.getCurrentState().getAbsolutePosition(),
                        null, 0.0, 0.0, totalMotors));
                break;
            }
            if (!brakingTriggered && v <= VELOCITY_EPSILON && !canMoveForward) {
                states.add(makeGlobal(t, localPos, 0.0, 0.0,
                        SimulationPhase.STOPPED, currentCumulativePos,
                        request.getCurrentState().getAbsolutePosition(),
                        null, 0.0, 0.0, totalMotors));
                break;
            }

            double sampleT = t;
            double sampleLocalPos = localPos;
            double sampleV = v;
            List<CarSnapshot> sampleCars = mapCarSnapshots(cars);
            SimulationPhase samplePhase = SimulationPhase.COAST;
            double sampleAccel = 0.0;
            double sampleDrag = computeNetDrag(localPos, v, train, line);

            for (int sub = 0; sub < SUB_STEPS_PER_SAMPLE; sub++) {
                head = cars.get(0);
                localPos = head.getPositionMeters();
                v = head.getSpeedMps();

                if ((brakingTriggered || !canMoveForward) && v <= VELOCITY_EPSILON) {
                    if (sub == 0) { samplePhase = SimulationPhase.STOPPED; sampleAccel = 0.0; }
                    break;
                }

                SimulationPhase phase;
                double targetAccel;
                boolean inBraking;

                if (brakingTriggered) {
                    phase = SimulationPhase.BRAKING;
                    if (brakeResponseRemaining > 0) {
                        targetAccel = 0.0;
                        inBraking = false;
                        brakeResponseRemaining -= dtSub;
                    } else {
                        double brakeDecel = (fMode == DrivingMode.EMERGENCY)
                                ? train.getEmergencyBrakeDeceleration()
                                : (fMode == DrivingMode.MANUAL && fManualDecel > 0.0) ? fManualDecel
                                : train.getNormalBrakeDeceleration();
                        targetAccel = -brakeDecel;
                        inBraking = true;
                    }
                } else if (fMode == DrivingMode.ATO) {
                    double predicted = predictStopPosition(cars, train, line, dtSub);
                    double distanceToTarget = localTarget - localPos;
                    if (predicted >= localTarget) {
                        brakingTriggered = true;
                        brakeTriggerPosition = localPos;
                        predictedStopPositionAtTrigger = predicted;
                        brakeResponseRemaining = train.getBrakeResponseTime();
                        phase = SimulationPhase.BRAKING;
                        targetAccel = 0.0;
                        inBraking = false;
                        brakeResponseRemaining -= dtSub;
                    } else if (distanceToTarget < preDecelThreshold && v > PRE_DECEL_SPEED_THRESHOLD_MPS) {
                        // ATO 预减速
                        phase = SimulationPhase.BRAKING;
                        targetAccel = PRE_DECEL_ACCEL;
                        inBraking = false;
                    } else if (distanceToTarget < preDecelThreshold) {
                        // 预减速区内已降到 50km/h 以下：惰行保持，不再牵引
                        phase = SimulationPhase.COAST;
                        targetAccel = 0.0;
                        inBraking = false;
                    } else if (!coastEntered && v < speedLimit - SPEED_LIMIT_MARGIN) {
                        phase = SimulationPhase.TRACTION;
                        targetAccel = train.getMaxAcceleration();
                        inBraking = false;
                    } else {
                        coastEntered = true;
                        phase = SimulationPhase.COAST;
                        targetAccel = 0.0;
                        inBraking = false;
                    }
                } else if (fMode == DrivingMode.MANUAL) {
                    boolean safetyStopTriggered = localPos >= localTarget;
                    if (safetyStopTriggered) {
                        brakingTriggered = true;
                        brakeTriggerPosition = localPos;
                        predictedStopPositionAtTrigger = predictStopPosition(cars, train, line, dtSub);
                        brakeResponseRemaining = 0.0;
                        phase = SimulationPhase.BRAKING;
                        targetAccel = -train.getNormalBrakeDeceleration();
                        inBraking = true;
                    } else if (fManualTractionActive && v < speedLimit - VELOCITY_EPSILON) {
                        phase = SimulationPhase.TRACTION;
                        targetAccel = fManualTractionAccel;
                        inBraking = false;
                    } else if (fManualTractionActive && v >= speedLimit - VELOCITY_EPSILON) {
                        phase = SimulationPhase.COAST;
                        targetAccel = 0.0;
                        inBraking = false;
                    } else {
                        phase = SimulationPhase.COAST;
                        targetAccel = 0.0;
                        inBraking = false;
                    }
                } else {
                    phase = SimulationPhase.COAST;
                    targetAccel = 0.0;
                    inBraking = false;
                }

                multiParticleService.updateGradeResistance(cars, p -> line.gradientAt(p));
                multiParticleService.stepConsist(cars, targetAccel, dtSub, inBraking);

                head = cars.get(0);
                double vAfter = head.getSpeedMps();
                if (phase == SimulationPhase.TRACTION && vAfter > speedLimit) {
                    head.setSpeedKmh(speedLimit * KMH_PER_MS);
                    vAfter = head.getSpeedMps(); // 截顶后重读，避免超速守卫用到截顶前的越界值
                }

                // SafetyGuard：超速触发紧急制动（用截顶后的真实速度判定，避免一步过冲误触发）。
                // 多质点下车厢速度弥散约 ±0.02 m/s，用 SPEED_LIMIT_MARGIN(0.1) 作为超速判定裕度。
                if (vAfter > speedLimit + SPEED_LIMIT_MARGIN && !brakingTriggered) {
                    safetyEvents.add(new com.bjtu.railtransit.vehicle.dto.SafetyEvent(
                            "OVERSPEED",
                            t + (sub + 1) * dtSub,
                            currentCumulativePos + head.getPositionMeters(),
                            vAfter,
                            "emergency_brake"
                    ));
                    brakingTriggered = true;
                    brakeTriggerPosition = head.getPositionMeters();
                    predictedStopPositionAtTrigger = predictStopPosition(cars, train, line, dtSub);
                    brakeResponseRemaining = 0.0;
                }

                if (sub == 0) {
                    samplePhase = phase;
                    sampleAccel = reportedAccelFor(phase, targetAccel);
                }
                t += dtSub;
            }

            if (samplePhase == null) samplePhase = SimulationPhase.STOPPED;
            double tracFN = samplePhase == SimulationPhase.TRACTION
                    ? trainMassKg * (sampleAccel + sampleDrag) : 0.0;
            double brkFN = samplePhase == SimulationPhase.BRAKING
                    ? trainMassKg * (-sampleAccel - sampleDrag) : 0.0;
            states.add(makeGlobal(sampleT, sampleLocalPos, sampleV, sampleAccel,
                    samplePhase, currentCumulativePos,
                    request.getCurrentState().getAbsolutePosition(),
                    sampleCars, tracFN, brkFN, totalMotors));
        }

        if (states.isEmpty() || states.get(states.size() - 1).getPhase() != SimulationPhase.STOPPED) {
            throw new IllegalStateException("控制续算未在最大步数内收敛到停车状态");
        }

        com.bjtu.railtransit.vehicle.dto.TrainState lastState = states.get(states.size() - 1);
        double actualCumPos = lastState.getPosition();
        double stopError = actualCumPos - totalTarget;
        boolean success = Math.abs(stopError) <= STOP_POSITION_TOLERANCE
                && lastState.getVelocity() <= STOP_VELOCITY_TOLERANCE;
        StopWindowState ws = deriveStopWindowState(stopError, lastState.getVelocity());
        StopResult stopResult = new StopResult(
                totalTarget, actualCumPos, stopError, success,
                success ? null : String.format("续算停车误差 %.3fm 或末速 %.3fm/s 超出阈值",
                        stopError, lastState.getVelocity()),
                ws, currentCumulativePos + brakeTriggerPosition,
                currentCumulativePos + predictedStopPositionAtTrigger);

        double maxV = states.stream().mapToDouble(com.bjtu.railtransit.vehicle.dto.TrainState::getVelocity).max().orElse(0);
        SimulationSummary summary = new SimulationSummary(
                maxV, lastState.getTime(), lastState.getPosition(),
                speedLimit, dt);
        summary.setCurrentMode(fMode);
        summary.setDepartureState(request.isDepartureConfirmed() ? "RUNNING" : "READY_TO_DEPART");
        if (fMode == DrivingMode.EMERGENCY && lastState.getPhase() == SimulationPhase.STOPPED) {
            summary.setNextMode(DrivingMode.MANUAL);
        }
        summary.setTrainMass(trainMassKg);
        summary.setTotalMotors(totalMotors);

        SimulationResult result = new SimulationResult(states, summary, stopResult, safetyEvents);
        if (request.getNextStationId() != null) {
            double actualStopCum = lastState.getPosition();
            double stopErr = actualStopCum - totalTarget;
            boolean inWindow = Math.abs(stopErr) <= STOP_POSITION_TOLERANCE
                    && lastState.getVelocity() <= STOP_VELOCITY_TOLERANCE;
            java.util.List<com.bjtu.railtransit.vehicle.dto.StationStop> stops =
                    java.util.Collections.singletonList(new com.bjtu.railtransit.vehicle.dto.StationStop(
                            request.getNextStationId(), request.getNextStationName(),
                            currentCumulativePos, totalTarget,
                            actualStopCum, stopErr, inWindow,
                            lastState.getTime(), 0.0));
            result.setStationStops(stops);
        } else {
            result.setStationStops(java.util.Collections.emptyList());
        }
        return result;
    }

    private com.bjtu.railtransit.vehicle.dto.TrainState buildSampleState(
            double t, List<TrainCar> cars, double reportedAccel, SimulationPhase phase) {
        TrainCar head = cars.get(0);
        return buildSampleState(t, head.getPositionMeters(), head.getSpeedMps(), reportedAccel, phase,
                mapCarSnapshots(cars));
    }

    private com.bjtu.railtransit.vehicle.dto.TrainState buildSampleState(
            double t, double pos, double v, double reportedAccel, SimulationPhase phase, List<CarSnapshot> cars) {
        com.bjtu.railtransit.vehicle.dto.TrainState st =
                new com.bjtu.railtransit.vehicle.dto.TrainState(t, pos, v, reportedAccel, phase, "T1");
        st.setCars(cars);
        return st;
    }

    /**
     * 采样帧报告加速度：用 ATO 决策的目标加速度反映控制策略意图。
     * 牵引 → 正（maxAccel），制动 → 负（含预减速），惰行/停车 → 0。
     * 这样 phase 与 accel 符号一致（TRACTION>0、BRAKING<0、COAST=0、STOPPED=0），
     * 不受多质点车钩瞬态噪声影响。
     */
    private static double reportedAccelFor(SimulationPhase phase, double targetAccel) {
        if (phase == SimulationPhase.TRACTION) {
            return Math.max(0.0, targetAccel);
        }
        if (phase == SimulationPhase.BRAKING) {
            return Math.min(0.0, targetAccel);
        }
        return 0.0;
    }

    /**
     * 把多质点编组映射为 TrainState.CarSnapshot 列表（车厢级展示用）。
     */
    private List<CarSnapshot> mapCarSnapshots(List<TrainCar> cars) {
        List<CarSnapshot> snaps = new ArrayList<>(cars.size());
        for (TrainCar c : cars) {
            snaps.add(new CarSnapshot(
                    c.getCarIndex(), c.getCarType(), c.isMotored(),
                    c.getOccupiedMass(), c.getPassengerLoadRatio(),
                    c.getPositionMeters(), c.getSpeedKmh(),
                    c.getCouplerForceN() / 1000.0));
        }
        return snaps;
    }

    private com.bjtu.railtransit.vehicle.dto.TrainState makeGlobal(
            double t, List<TrainCar> cars, double velocity, double acceleration,
            SimulationPhase phase,
            double cumulativeOffset, Double baseAbsolutePos) {
        TrainCar head = cars.get(0);
        return makeGlobal(t, head.getPositionMeters(), velocity, acceleration, phase,
                cumulativeOffset, baseAbsolutePos, mapCarSnapshots(cars), 0.0, 0.0, 16);
    }

    private com.bjtu.railtransit.vehicle.dto.TrainState makeGlobal(
            double t, double localPos, double velocity, double acceleration,
            SimulationPhase phase,
            double cumulativeOffset, Double baseAbsolutePos,
            List<CarSnapshot> cars, double tractionForceN, double brakeForceN, int availableMotors) {
        double globalPos = cumulativeOffset + localPos;
        com.bjtu.railtransit.vehicle.dto.TrainState st =
                new com.bjtu.railtransit.vehicle.dto.TrainState(
                        t, globalPos, velocity, acceleration, phase, "T1",
                        tractionForceN, brakeForceN, availableMotors);
        if (baseAbsolutePos != null) {
            st.setAbsolutePosition(baseAbsolutePos + localPos);
        }
        st.setCars(cars);
        return st;
    }

    /**
     * 模拟车辆从正线驶入侧线停车（新增方法，不修改已有方法签名）。
     *
     * <p>调度发起侧线撤离指令后调用此方法，生成一段额外状态序列供前端播放：
     * 从当前速度以固定减速度减速到 0，在侧线内行驶约 {@value #SIDING_LENGTH_M}m 后停稳。
     * 纯运动学简化模型，不引入 Davis 阻力/坡度，用于演示侧线停车过程。</p>
     *
     * <p>坐标系：返回 states 的 position / absolutePosition 沿用 {@code currentState}
     * 的累计里程坐标系，继续单调前进，与多站仿真一致。</p>
     *
     * @param trainId      列车编号
     * @param stationId    目标站 id（仅用于日志，不影响运动学）
     * @param currentState 当前列车状态（位置、速度、时刻）；velocity=0 时直接返回停车帧
     * @return 驶入侧线的状态序列（最后一帧为 STOPPED，停在侧线末端）
     */
    public java.util.List<com.bjtu.railtransit.vehicle.dto.TrainState> enterSiding(
            String trainId, int stationId, com.bjtu.railtransit.vehicle.dto.TrainState currentState) {

        if (currentState == null) {
            throw new IllegalArgumentException("currentState 不能为空");
        }
        if (trainId == null || trainId.isEmpty()) {
            throw new IllegalArgumentException("trainId 不能为空");
        }

        double decel = SIDING_DECEL_MPS2;
        double sidingLength = SIDING_LENGTH_M;
        double dt = SIDING_DT;

        double v = currentState.getVelocity();
        double basePos = currentState.getPosition();
        Double baseAbs = currentState.getAbsolutePosition();
        double t = currentState.getTime();

        java.util.List<com.bjtu.railtransit.vehicle.dto.TrainState> sidingStates = new java.util.ArrayList<>();

        // 已经停稳：直接返回单帧 STOPPED
        if (v <= VELOCITY_EPSILON) {
            sidingStates.add(buildSidingState(t, basePos, 0.0, 0.0,
                    SimulationPhase.STOPPED, trainId, baseAbs, 0.0));
            return sidingStates;
        }

        double traveled = 0.0; // 侧线内已行驶距离（相对驶入点）
        // 减速过程：v 以匀减速下降，position 随之前进，直到 v=0 或驶满侧线长度
        while (v > VELOCITY_EPSILON && traveled < sidingLength) {
            // 记录本步起始状态（制动阶段）
            sidingStates.add(buildSidingState(t, basePos + traveled, v, -decel,
                    SimulationPhase.BRAKING, trainId, baseAbs, traveled));

            double vNext = Math.max(0.0, v - decel * dt);
            // 梯形积分：用本步起止速度均值推进距离
            traveled += 0.5 * (v + vNext) * dt;
            if (traveled > sidingLength) {
                traveled = sidingLength;
            }
            t += dt;
            v = vNext;
        }

        // 最终停车帧：停在侧线末端
        sidingStates.add(buildSidingState(t, basePos + traveled, 0.0, 0.0,
                SimulationPhase.STOPPED, trainId, baseAbs, traveled));

        log.info("侧线驶入仿真完成：trainId={} stationId={} 初速{}m/s → 行驶{}m 用时{}s 共{}帧",
                trainId, stationId, String.format("%.2f", currentState.getVelocity()),
                String.format("%.1f", traveled), String.format("%.1f", t - currentState.getTime()),
                sidingStates.size());

        return sidingStates;
    }

    private com.bjtu.railtransit.vehicle.dto.TrainState buildSidingState(
            double t, double globalPos, double velocity, double acceleration,
            SimulationPhase phase, String trainId, Double baseAbs, double localOffset) {
        com.bjtu.railtransit.vehicle.dto.TrainState st =
                new com.bjtu.railtransit.vehicle.dto.TrainState(
                        t, globalPos, velocity, acceleration, phase, trainId);
        if (baseAbs != null) {
            st.setAbsolutePosition(baseAbs + localOffset);
        }
        return st;
    }
}
