package com.bjtu.railtransit.vehicle.service;

import com.bjtu.railtransit.vehicle.dto.ControlCommand;
import com.bjtu.railtransit.vehicle.dto.SafetyEvent;
import com.bjtu.railtransit.vehicle.dto.SimulationControlRequest;
import com.bjtu.railtransit.vehicle.dto.SimulationResult;
import com.bjtu.railtransit.vehicle.dto.SimulationSummary;
import com.bjtu.railtransit.vehicle.dto.StopResult;
import com.bjtu.railtransit.vehicle.dto.TrainState;
import com.bjtu.railtransit.vehicle.enums.DrivingMode;
import com.bjtu.railtransit.vehicle.enums.SimulationPhase;
import com.bjtu.railtransit.vehicle.enums.StopWindowState;
import com.bjtu.railtransit.vehicle.model.LineProfile;
import com.bjtu.railtransit.vehicle.model.ScenarioConfig;
import com.bjtu.railtransit.vehicle.model.TrainModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 车辆纵向运动仿真核心逻辑（阶段 2：车辆动力学真实化）。
 *
 * <p>阶段 1 使用的是理想三段式解析解（匀加速牵引 -&gt; 匀速惰行 -&gt; 匀减速制动，
 * 制动触发点由 {@code v^2/(2*decel)} 反推为一个固定常量），本质上是运动学模型：
 * 先假设运动状态的形状，再套用运动学公式积分，不涉及"力"的概念。</p>
 *
 * <p>阶段 2 改为真正的单质点纵向动力学：每一步先算合加速度
 * {@code a = 控制加速度 - Davis基本阻力对应减速度 - 坡度阻力对应减速度}，再用数值积分
 * （小步长梯形积分）把加速度积分成速度、位置。因为 Davis 阻力含 v² 项、坡度阻力依赖
 * 位置，含阻力后的运动方程一般没有闭式解，因此不能再用阶段 1 的解析公式反推制动点，
 * 改为"预测式"触发：每一步预测"若此刻起按常用制动减速度制动，会停在何处"，一旦预测
 * 停车位置到达或超过目标停车点就切入制动，这与真实 ATO 系统的滚动预测逻辑一致。</p>
 *
 * <p>不做的事（越界即停，本轮明确排除）：多质点/车钩耦合动力学、牵引-速度特性曲线、
 * 能耗计算、SafetyGuard 超速保护、704 收发。停站精度不追求收敛到 0.5m（阶段3任务），
 * 引入真实阻力后停站误差如实报告，不做任何静默对齐。</p>
 */
@Service
public class VehicleSimulationService {

    /** 速度收敛判定阈值，单位 m/s：低于该值视为已停车。数值积分收敛阈值，非业务停车判定阈值。业务停车判定使用 STOP_VELOCITY_TOLERANCE(0.1)。 */
    private static final double VELOCITY_EPSILON = 1.0e-3;

    /** 停站成功阈值（位置误差），单位 m，取自指挥书阶段 3 的成功标准，本轮先复用作参考判定。 */
    private static final double STOP_POSITION_TOLERANCE = 0.5;

    /** 停站成功阈值（末速度），单位 m/s。 */
    private static final double STOP_VELOCITY_TOLERANCE = 0.1;

    /** 循环步数上限（对外采样点个数），避免配置异常导致死循环。 */
    private static final int MAX_STEPS = 100_000;

    /** 重力加速度，单位 m/s2，用于 Davis 阻力（N/kN -> m/s2）与坡度阻力换算。 */
    private static final double GRAVITY = 9.80665;

    /** m/s 换算到 km/h 的系数，Davis 公式的速度基准是 km/h（教材通用形式）。 */
    private static final double KMH_PER_MS = 3.6;

    /**
     * 每个对外采样间隔（{@code dt}）内部拆分的数值积分子步数。
     *
     * <p>Davis 阻力含 v² 项、坡度阻力依赖位置，净加速度不再是分段常数，用较大的 dt
     * （如 0.5s）直接做单步梯形积分会引入明显误差，因此在两个对外采样点之间用更小的
     * 子步长 {@code dt/SUB_STEPS_PER_SAMPLE} 做数值积分，采样输出频率（states 的时间
     * 间隔）保持不变，不影响前端播放节奏。</p>
     */
    private static final int SUB_STEPS_PER_SAMPLE = 20;

    /** 预测式制动触发内部前向模拟的步数上限，避免异常参数导致预测本身死循环。 */
    private static final int MAX_PREDICTION_STEPS = 20_000;

    private final DemoScenarioProvider demoScenarioProvider;

    public VehicleSimulationService(DemoScenarioProvider demoScenarioProvider) {
        this.demoScenarioProvider = demoScenarioProvider;
    }

    /**
     * 使用后端内置演示配置运行一次完整仿真，一次性返回完整结果。
     */
    public SimulationResult runDemoSimulation() {
        return run(demoScenarioProvider.getDemoScenario());
    }

    /**
     * 使用给定场景配置运行一次完整仿真。
     *
     * @param scenario 线路 + 车型 + 步长配置
     * @return 包含 states/summary/stopResult/safetyEvents 四段字段的完整仿真结果
     */
    public SimulationResult run(ScenarioConfig scenario) {
        LineProfile line = scenario.getLineProfile();
        TrainModel train = scenario.getTrainModel();
        double dt = scenario.getDt();
        double dtSub = dt / SUB_STEPS_PER_SAMPLE;

        double targetStopPosition = line.getTargetStopPosition();
        double speedLimit = line.getSpeedLimit();

        List<TrainState> states = new ArrayList<>();

        double t = 0.0;
        double pos = line.getStartPosition();
        double v = 0.0;

        // 是否已切入制动（一旦为 true 永不回退，制动/停车之后不会再回到牵引或惰行）。
        boolean brakingTriggered = false;
        // 是否已经"进入过惰行"（一旦速度达到限速切入惰行，即使后续因阻力掉速也不会
        // 自动切回牵引重新加速——阶段2只做最小控制策略，不做牵引/惰行反复切换，
        // 那属于牵引-速度特性曲线/能耗优化范畴，本轮明确排除）。
        boolean coastEntered = false;
        // 制动响应剩余时间，单位 s。阶段3B新增：制动触发后先递减此值，期间只受阻力。
        double brakeResponseRemaining = 0.0;
        // 制动触发时的列车位置，单位 m。阶段3B新增。
        double brakeTriggerPosition = 0.0;
        // 触发制动时 predictStopPosition 预测的停车位置，单位 m。阶段3B新增。
        double predictedStopPositionAtTrigger = 0.0;

        for (int step = 0; step < MAX_STEPS; step++) {
            if (brakingTriggered && v <= VELOCITY_EPSILON) {
                states.add(new TrainState(t, pos, 0.0, 0.0, SimulationPhase.STOPPED, "T1"));
                break;
            }

            double sampleT = t;
            double samplePos = pos;
            double sampleV = v;
            SimulationPhase samplePhase = null;
            double sampleAcceleration = 0.0;

            for (int sub = 0; sub < SUB_STEPS_PER_SAMPLE; sub++) {
                SimulationPhase phase;
                double acceleration;
                double vNext;
                double posNext;

                if (brakingTriggered && v <= VELOCITY_EPSILON) {
                    // 本采样间隔内已经停稳，剩余子步不再移动，避免阻力/坡度在 v=0 时
                    // 继续改变速度（真实车辆停稳后由制动保持，不建模溜车）。
                    phase = SimulationPhase.STOPPED;
                    acceleration = 0.0;
                    vNext = 0.0;
                    posNext = pos;
                } else if (!brakingTriggered) {
                    // 预测式制动触发：预测"若此刻起按常用制动减速度制动，会停在何处"，
                    // 一旦预测停车位置到达或超过目标停车点，立即切入制动。这取代了阶段1
                    // "解析公式反推固定阈值"的做法——阻力/坡度介入后 v^2/(2a) 不再准确。
                    double predictedStop = predictStopPosition(pos, v, train, line, dtSub);
                    if (predictedStop >= targetStopPosition) {
                        brakingTriggered = true;
                        brakeTriggerPosition = pos;
                        predictedStopPositionAtTrigger = predictedStop;
                        brakeResponseRemaining = train.getBrakeResponseTime();
                    }

                    if (brakingTriggered) {
                        phase = SimulationPhase.BRAKING;
                        double drag = computeNetDrag(pos, v, train, line);
                        if (brakeResponseRemaining > 0) {
                            // 响应时间内：只有阻力，制动尚未真正生效
                            acceleration = -drag;
                            brakeResponseRemaining -= dtSub;
                        } else {
                            // 制动真正生效
                            acceleration = -train.getNormalBrakeDeceleration() - drag;
                        }
                    } else if (!coastEntered && v < speedLimit - VELOCITY_EPSILON) {
                        phase = SimulationPhase.TRACTION;
                        double drag = computeNetDrag(pos, v, train, line);
                        acceleration = train.getMaxAcceleration() - drag;
                    } else {
                        coastEntered = true;
                        phase = SimulationPhase.COAST;
                        double drag = computeNetDrag(pos, v, train, line);
                        acceleration = -drag;
                    }

                    vNext = v + acceleration * dtSub;
                    if (vNext < 0.0) {
                        vNext = 0.0;
                    }
                    if (phase == SimulationPhase.TRACTION && vNext >= speedLimit) {
                        vNext = speedLimit;
                        coastEntered = true;
                    }
                    posNext = pos + 0.5 * (v + vNext) * dtSub;
                } else {
                    // brakingTriggered == true 且 v > VELOCITY_EPSILON：持续制动。
                    phase = SimulationPhase.BRAKING;
                    double drag = computeNetDrag(pos, v, train, line);
                    if (brakeResponseRemaining > 0) {
                        // 响应时间内：只有阻力，制动尚未真正生效
                        acceleration = -drag;
                        brakeResponseRemaining -= dtSub;
                    } else {
                        // 制动真正生效
                        acceleration = -train.getNormalBrakeDeceleration() - drag;
                    }

                    vNext = v + acceleration * dtSub;
                    if (vNext < 0.0) {
                        vNext = 0.0;
                    }
                    posNext = pos + 0.5 * (v + vNext) * dtSub;
                }

                if (sub == 0) {
                    samplePhase = phase;
                    sampleAcceleration = acceleration;
                }

                t += dtSub;
                pos = posNext;
                v = vNext;
            }

            states.add(new TrainState(sampleT, samplePos, sampleV, sampleAcceleration, samplePhase, "T1"));
        }

        if (states.isEmpty() || states.get(states.size() - 1).getPhase() != SimulationPhase.STOPPED) {
            throw new IllegalStateException("车辆仿真未在最大步数内收敛到停车状态，请检查演示配置参数");
        }

        return buildResult(states, targetStopPosition, speedLimit, dt, brakeTriggerPosition, predictedStopPositionAtTrigger);
    }

    /**
     * 预测"若从 (pos, v) 状态起立即按常用制动减速度制动，会停在何处"。
     *
     * <p>用与主循环相同的梯形积分方式、相同的阻力/坡度模型向前模拟，直到速度收敛到 0，
     * 返回预测的停车位置。这是预测式制动触发的核心：每一步都基于当前真实动力学重新
     * 计算一次，而不是像阶段1那样依赖"惰行匀速、制动匀减速"下才成立的解析公式。</p>
     *
     * <p>TODO(阶段3技术债)：预测式制动触发在每个子步调用 predictStopPosition，复杂度
     * O(步数²)。当前 1.2km 演示配置无性能问题。若线路拉长到 16km+ 需考虑优化。</p>
     */
    private double predictStopPosition(double pos, double v, TrainModel train, LineProfile line, double dtPredict) {
        double p = pos;
        double vel = v;
        double responseRemaining = train.getBrakeResponseTime();
        for (int i = 0; i < MAX_PREDICTION_STEPS; i++) {
            if (vel <= VELOCITY_EPSILON) {
                return p;
            }
            double drag = computeNetDrag(p, vel, train, line);
            double a;
            if (responseRemaining > 0) {
                // 阶段A（制动响应时间内）：只受阻力，制动尚未真正生效
                a = -drag;
                responseRemaining -= dtPredict;
            } else {
                // 阶段B（响应时间已过）：制动 + 阻力
                a = -train.getNormalBrakeDeceleration() - drag;
            }
            double velNext = vel + a * dtPredict;
            if (velNext < 0.0) {
                velNext = 0.0;
            }
            double pNext = p + 0.5 * (vel + velNext) * dtPredict;
            p = pNext;
            vel = velNext;
        }
        return p;
    }

    /**
     * 计算给定位置、速度下的合成阻力对应减速度（正值表示对运动的阻碍）：
     * Davis 基本阻力 + 坡度阻力。
     *
     * <p>Davis 基本阻力公式 {@code w0 = a + b*v_kmh + c*v_kmh^2 (N/kN)} 是城轨/铁道
     * 列车牵引计算教材的通用形式，系数具体数值为工程假设值（见
     * {@link DemoScenarioProvider} 类注释），换算成加速度：
     * {@code resistanceDecel = w0 * g / 1000}（该换算与车辆质量无关，重量以 kN 为基准
     * 的比阻力本身已经归一化掉了质量）。</p>
     *
     * <p>坡度阻力 {@code gradeDecel = g * gradient(pos)}，上坡（gradient&gt;0）增大阻力，
     * 下坡（gradient&lt;0）减小阻力甚至变为助力，取自 {@link LineProfile#gradientAt(double)}。</p>
     */
    private double computeNetDrag(double pos, double v, TrainModel train, LineProfile line) {
        double vKmh = v * KMH_PER_MS;
        double w0 = train.getDavisA() + train.getDavisB() * vKmh + train.getDavisC() * vKmh * vKmh;
        double resistanceDecel = w0 * GRAVITY / 1000.0;
        double gradeDecel = GRAVITY * line.gradientAt(pos);
        return resistanceDecel + gradeDecel;
    }

    // ==================== 驾驶员控制续算（本轮新增）====================

    /**
     * 驾驶员控制续算：从请求中携带的 currentState 开始，根据 currentMode 和 controlCommand
     * 重新积分后续状态序列，返回与 {@link #run} 相同格式的 {@link SimulationResult}。
     *
     * <p>驾驶模式状态机（本项目为课程仿真原型，不代表真实 CBTC/ATP/ATO 系统）：</p>
     * <ul>
     *   <li>ATO 模式下：普通 brake 指令被拒绝（保持 ATO 续算）；EB 直接进入 EMERGENCY。</li>
     *   <li>MANUAL 模式下：brake 指令真实参与续算，targetDecel 不超过 normalBrakeDeceleration；EB 直接进入 EMERGENCY。</li>
     *   <li>EMERGENCY 模式下：使用 emergencyBrakeDeceleration 续算到停车，ATO 指令冻结。</li>
     * </ul>
     *
     * <p>SafetyGuard（最小实现）：续算每一步检查速度是否超过区间限速，超速则生成
     * SafetyEvent 并强制施加 emergencyBrakeDeceleration 保护（ATP 保护层）。</p>
     *
     * @param request  包含 currentState、fromStationId、toStationId、currentMode、controlCommand
     * @param scenario 由控制器基于 fromStationId/toStationId 构造的场景配置
     * @return 续算结果，summary.currentMode 携带续算后模式，summary.nextMode 给出建议的后续模式
     * @throws IllegalArgumentException 当请求参数缺失或非法时
     */
    public SimulationResult runContinuation(SimulationControlRequest request, ScenarioConfig scenario) {
        if (request.getCurrentState() == null) {
            throw new IllegalArgumentException("controlRequest.currentState 不能为空");
        }
        if (request.getCurrentMode() == null) {
            throw new IllegalArgumentException("controlRequest.currentMode 不能为空");
        }
        if (request.getControlCommand() == null) {
            throw new IllegalArgumentException("controlRequest.controlCommand 不能为空");
        }

        LineProfile line = scenario.getLineProfile();
        TrainModel train = scenario.getTrainModel();
        double dt = scenario.getDt();
        double dtSub = dt / SUB_STEPS_PER_SAMPLE;

        double targetStopPosition = line.getTargetStopPosition();
        double speedLimit = line.getSpeedLimit();

        ControlCommand cmd = request.getControlCommand();
        DrivingMode inputMode = request.getCurrentMode();

        // ---- 解析控制指令，确定实际执行模式 ----
        DrivingMode effectiveMode;
        String commandStr = cmd.getCommand() != null ? cmd.getCommand().toLowerCase() : "";
        boolean isEmergencyBrake = "emergency_brake".equals(commandStr);

        if (isEmergencyBrake) {
            // EB 任意模式直接进入 EMERGENCY
            effectiveMode = DrivingMode.EMERGENCY;
        } else if (inputMode == DrivingMode.ATO && "brake".equals(commandStr)) {
            // ATO 模式下普通 brake 被拒绝，保持 ATO 续算（使用 ATO 自动策略）
            effectiveMode = DrivingMode.ATO;
        } else {
            effectiveMode = inputMode;
        }

        // 初始条件来自 currentState
        TrainState initState = request.getCurrentState();
        double startT = initState.getTime();
        double pos = initState.getPosition();
        double v = initState.getVelocity();

        List<TrainState> states = new ArrayList<>();
        List<SafetyEvent> safetyEvents = new ArrayList<>();

        double t = startT;
        boolean brakingTriggered = false;
        boolean coastEntered = false;
        double brakeResponseRemaining = 0.0;
        double brakeTriggerPosition = pos;
        double predictedStopPositionAtTrigger = pos;

        // MANUAL 模式下的目标减速度（不超过 normalBrakeDeceleration）
        double manualTargetDecel = 0.0;
        if (effectiveMode == DrivingMode.MANUAL && "brake".equals(commandStr)) {
            double requestedDecel = cmd.getTargetDecel();
            manualTargetDecel = Math.min(
                    Math.max(requestedDecel, 0.0),
                    train.getNormalBrakeDeceleration()
            );
            // MANUAL brake 直接触发制动
            brakingTriggered = true;
            brakeTriggerPosition = pos;
            brakeResponseRemaining = train.getBrakeResponseTime();
        }

        // EMERGENCY 模式：直接触发紧急制动
        if (effectiveMode == DrivingMode.EMERGENCY) {
            brakingTriggered = true;
            brakeTriggerPosition = pos;
            brakeResponseRemaining = train.getBrakeResponseTime();
            // 生成 EB SafetyEvent（司机主动 EB 或 ATP 保护）
            String ebReason = isEmergencyBrake ? "DRIVER_EMERGENCY_BRAKE" : "EMERGENCY_MODE_ENGAGED";
            safetyEvents.add(new SafetyEvent(ebReason, t, pos, v, "emergency_brake"));
        }

        final double finalManualTargetDecel = manualTargetDecel;
        final DrivingMode finalEffectiveMode = effectiveMode;

        for (int step = 0; step < MAX_STEPS; step++) {
            if (brakingTriggered && v <= VELOCITY_EPSILON) {
                states.add(new TrainState(t, pos, 0.0, 0.0, SimulationPhase.STOPPED, "T1"));
                break;
            }

            double sampleT = t;
            double samplePos = pos;
            double sampleV = v;
            SimulationPhase samplePhase = null;
            double sampleAcceleration = 0.0;

            for (int sub = 0; sub < SUB_STEPS_PER_SAMPLE; sub++) {
                if (brakingTriggered && v <= VELOCITY_EPSILON) {
                    if (samplePhase == null) {
                        samplePhase = SimulationPhase.STOPPED;
                        sampleAcceleration = 0.0;
                    }
                    break;
                }

                SimulationPhase phase;
                double acceleration;
                double drag = computeNetDrag(pos, v, train, line);

                if (brakingTriggered) {
                    // ---- 制动中（MANUAL / EMERGENCY / ATO-EB 都走这里）----
                    phase = SimulationPhase.BRAKING;
                    if (brakeResponseRemaining > 0) {
                        acceleration = -drag;
                        brakeResponseRemaining -= dtSub;
                    } else {
                        double brakeDecel = (finalEffectiveMode == DrivingMode.EMERGENCY)
                                ? train.getEmergencyBrakeDeceleration()
                                : (finalEffectiveMode == DrivingMode.MANUAL)
                                        ? finalManualTargetDecel
                                        : train.getNormalBrakeDeceleration();
                        acceleration = -brakeDecel - drag;
                    }
                } else if (finalEffectiveMode == DrivingMode.ATO) {
                    // ATO 自动策略（复用 run() 中的预测式触发逻辑）
                    double predictedStop = predictStopPosition(pos, v, train, line, dtSub);
                    if (predictedStop >= targetStopPosition) {
                        brakingTriggered = true;
                        brakeTriggerPosition = pos;
                        predictedStopPositionAtTrigger = predictedStop;
                        brakeResponseRemaining = train.getBrakeResponseTime();
                        phase = SimulationPhase.BRAKING;
                        acceleration = -drag; // 响应时间第一步
                    } else if (!coastEntered && v < speedLimit - VELOCITY_EPSILON) {
                        phase = SimulationPhase.TRACTION;
                        acceleration = train.getMaxAcceleration() - drag;
                    } else {
                        coastEntered = true;
                        phase = SimulationPhase.COAST;
                        acceleration = -drag;
                    }
                } else {
                    // MANUAL coast/traction（无制动指令）
                    if (!coastEntered && v < speedLimit - VELOCITY_EPSILON) {
                        phase = SimulationPhase.TRACTION;
                        acceleration = train.getMaxAcceleration() - drag;
                    } else {
                        coastEntered = true;
                        phase = SimulationPhase.COAST;
                        acceleration = -drag;
                    }
                }

                double vNext = v + acceleration * dtSub;
                if (vNext < 0.0) vNext = 0.0;
                if (phase == SimulationPhase.TRACTION && vNext >= speedLimit) {
                    vNext = speedLimit;
                    coastEntered = true;
                }
                double posNext = pos + 0.5 * (v + vNext) * dtSub;

                // ---- SafetyGuard：超速检查 ----
                if (vNext > speedLimit && !brakingTriggered) {
                    safetyEvents.add(new SafetyEvent(
                            "OVERSPEED",
                            t + (sub + 1) * dtSub,
                            posNext,
                            vNext,
                            "emergency_brake"
                    ));
                    // ATP 保护层：强制紧急制动
                    brakingTriggered = true;
                    brakeTriggerPosition = posNext;
                    predictedStopPositionAtTrigger = predictStopPosition(posNext, vNext, train, line, dtSub);
                    brakeResponseRemaining = 0.0; // ATP 保护无响应延迟
                    phase = SimulationPhase.BRAKING;
                }

                if (sub == 0) {
                    samplePhase = phase;
                    sampleAcceleration = acceleration;
                }

                t += dtSub;
                pos = posNext;
                v = vNext;
            }

            if (samplePhase == null) {
                samplePhase = SimulationPhase.STOPPED;
            }
            states.add(new TrainState(sampleT, samplePos, sampleV, sampleAcceleration, samplePhase, "T1"));
        }

        if (states.isEmpty() || states.get(states.size() - 1).getPhase() != SimulationPhase.STOPPED) {
            throw new IllegalStateException("驾驶员控制续算未在最大步数内收敛到停车状态");
        }

        // ---- 计算续算结果 ----
        SimulationResult result = buildResult(
                states, targetStopPosition, speedLimit, dt,
                brakeTriggerPosition, predictedStopPositionAtTrigger
        );

        // 填充 safetyEvents（可能来自 EB 触发或 SafetyGuard 超速）
        result.setSafetyEvents(safetyEvents);

        // 确定续算后的模式及建议后续模式
        boolean stopped = states.get(states.size() - 1).getPhase() == SimulationPhase.STOPPED;
        DrivingMode resultMode = finalEffectiveMode;
        DrivingMode resultNextMode = null;
        if (finalEffectiveMode == DrivingMode.EMERGENCY && stopped) {
            // EMERGENCY 停稳后建议 MANUAL（不自动恢复 ATO）
            resultNextMode = DrivingMode.MANUAL;
        }
        result.getSummary().setCurrentMode(resultMode);
        result.getSummary().setNextMode(resultNextMode);

        return result;
    }

    // ==================== 多站连续仿真（本轮新增）====================

    /**
     * 默认驻留时间，单位 s。运行图下发时由 {@link com.bjtu.railtransit.vehicle.dto.SimulationRunRequest#resolvedDwellTime()} 覆盖。
     */
    static final double DEFAULT_DWELL_TIME_SECONDS = 30.0;

    /**
     * 多站连续仿真：从 stationEntries[0] 出发，经若干中间站，到达最后一站。
     *
     * <p>实现方式：复用 {@link #run(ScenarioConfig)} 的单区间逻辑，循环串联每个相邻区间：</p>
     * <ol>
     *   <li>对每段 fromSeg→toSeg 调用 {@link #run(ScenarioConfig)} 得到区间 states。</li>
     *   <li>把区间 states 的 time 平移（加上已累积时间），absolutePosition 加上起站绝对里程。</li>
     *   <li>如果不是最后一段，在停车后插入 dwellTimeSeconds 的 DWELL 状态帧。</li>
     *   <li>所有区间 states 拼接成全程 states，safetyEvents 汇总。</li>
     * </ol>
     *
     * @param stationEntries 按 id 顺序的站点列表（至少 2 个）
     * @param dwellTimeSeconds 中间站驻留时间，单位 s（null 时使用默认 30s）
     * @param demoProvider 场景配置工厂
     * @return 全程连续 SimulationResult
     */
    public SimulationResult runMultiStation(
            List<com.bjtu.railtransit.vehicle.service.LineProfileJsonLoader.StationEntry> stationEntries,
            Double dwellTimeSeconds,
            DemoScenarioProvider demoProvider) {

        if (stationEntries == null || stationEntries.size() < 2) {
            throw new IllegalArgumentException("多站仿真至少需要 2 个站点");
        }

        double dwell = dwellTimeSeconds != null ? dwellTimeSeconds : DEFAULT_DWELL_TIME_SECONDS;
        double dt = 0.5; // 与 DemoScenarioProvider.DT 保持一致（0.5s），不改 dtPerFrame

        List<TrainState> allStates = new ArrayList<>();
        List<SafetyEvent> allSafetyEvents = new ArrayList<>();
        List<com.bjtu.railtransit.vehicle.dto.StationStop> stationStops = new ArrayList<>();

        double timeOffset = 0.0;    // 全程累积时间，单位 s
        double absOffset = 0.0;     // 全程累积绝对里程，单位 m（= 第一站的绝对里程）
        double firstStationAbsM = stationEntries.get(0).km * 1000.0;
        double maxVelocityAll = 0.0;

        StopResult finalStopResult = null;

        for (int seg = 0; seg < stationEntries.size() - 1; seg++) {
            com.bjtu.railtransit.vehicle.service.LineProfileJsonLoader.StationEntry fromSt = stationEntries.get(seg);
            com.bjtu.railtransit.vehicle.service.LineProfileJsonLoader.StationEntry toSt = stationEntries.get(seg + 1);
            boolean isLastSeg = (seg == stationEntries.size() - 2);

            double segFromAbsM = fromSt.km * 1000.0;
            double segToAbsM = toSt.km * 1000.0;
            double segDistM = segToAbsM - segFromAbsM;
            if (segDistM <= 0) {
                throw new IllegalArgumentException(
                        "区间 " + fromSt.name + " -> " + toSt.name + " 距离为 0 或负值，无法仿真");
            }

            com.bjtu.railtransit.vehicle.model.LineProfile lineProfile =
                    new com.bjtu.railtransit.vehicle.model.LineProfile(
                            0.0,
                            segDistM,
                            LineProfileJsonLoader.ASSUMED_SPEED_LIMIT_MPS,
                            java.util.Collections.emptyList()
                    );
            com.bjtu.railtransit.vehicle.model.ScenarioConfig scenario = demoProvider.buildScenario(lineProfile);

            SimulationResult segResult = run(scenario);
            List<TrainState> segStates = segResult.getStates();

            // 追加区间 states，修正 time / absolutePosition / segmentIndex / stationId
            for (TrainState st : segStates) {
                TrainState adjusted = new TrainState(
                        st.getTime() + timeOffset,
                        st.getPosition(),
                        st.getVelocity(),
                        st.getAcceleration(),
                        st.getPhase(),
                        "T1"
                );
                // absolutePosition = 起站绝对里程 + 区间内相对位置
                adjusted.setAbsolutePosition(segFromAbsM + st.getPosition());
                adjusted.setSegmentIndex(seg);
                adjusted.setStationId(toSt.id);
                adjusted.setStationName(toSt.name);
                allStates.add(adjusted);
                if (st.getVelocity() > maxVelocityAll) {
                    maxVelocityAll = st.getVelocity();
                }
            }

            // 追加区间 safetyEvents（time 平移）
            for (SafetyEvent se : segResult.getSafetyEvents()) {
                allSafetyEvents.add(new SafetyEvent(
                        se.getReason(),
                        se.getTime() + timeOffset,
                        se.getPosition(),
                        se.getVelocity(),
                        se.getAction()
                ));
            }

            // 记录本站停车
            TrainState lastSeg = segStates.get(segStates.size() - 1);
            double arrivalTime = lastSeg.getTime() + timeOffset;
            double actualAbsM = segFromAbsM + lastSeg.getPosition();
            double segStopError = lastSeg.getPosition() - segDistM;
            boolean inWindow = Math.abs(segStopError) <= STOP_POSITION_TOLERANCE
                    && lastSeg.getVelocity() <= STOP_VELOCITY_TOLERANCE;

            if (isLastSeg) {
                // 最终站：构造 stopResult（与单区间语义保持一致）
                StopWindowState wsState = deriveStopWindowState(segStopError, lastSeg.getVelocity());
                finalStopResult = new StopResult(
                        segDistM, lastSeg.getPosition(), segStopError,
                        inWindow, inWindow ? null : "停站误差或末速度超出阈值",
                        wsState,
                        segResult.getStopResult() != null ? segResult.getStopResult().getBrakeTriggerPosition() : 0.0,
                        segResult.getStopResult() != null ? segResult.getStopResult().getPredictedStopPosition() : 0.0
                );
                stationStops.add(new com.bjtu.railtransit.vehicle.dto.StationStop(
                        toSt.id, toSt.name,
                        arrivalTime, arrivalTime,  // 终点站不驻留
                        0.0, segStopError, inWindow,
                        segToAbsM, actualAbsM
                ));
            } else {
                stationStops.add(new com.bjtu.railtransit.vehicle.dto.StationStop(
                        toSt.id, toSt.name,
                        arrivalTime, arrivalTime + dwell,
                        dwell, segStopError, inWindow,
                        segToAbsM, actualAbsM
                ));

                // 更新 timeOffset 到停车时刻
                timeOffset += lastSeg.getTime();

                // 插入 dwell 帧：velocity=0, acceleration=0, position 不变，time 递增
                double dwellPos = lastSeg.getPosition();
                double dwellAbsPos = segFromAbsM + dwellPos;
                int dwellFrames = (int) Math.round(dwell / dt);
                for (int f = 1; f <= dwellFrames; f++) {
                    TrainState dSt = new TrainState(
                            timeOffset + f * dt,
                            dwellPos,
                            0.0, 0.0,
                            SimulationPhase.DWELL,
                            "T1"
                    );
                    dSt.setAbsolutePosition(dwellAbsPos);
                    dSt.setSegmentIndex(seg);
                    dSt.setStationId(toSt.id);
                    dSt.setStationName(toSt.name);
                    allStates.add(dSt);
                }

                // 更新偏移（dwell 结束后的 timeOffset 是下一区间起点）
                timeOffset += dwell;
                absOffset = segToAbsM;
            }
        }

        // 构造汇总 summary
        TrainState lastAll = allStates.get(allStates.size() - 1);
        SimulationSummary summary = new SimulationSummary(
                maxVelocityAll,
                lastAll.getTime(),
                lastAll.getPosition(),
                LineProfileJsonLoader.ASSUMED_SPEED_LIMIT_MPS,
                dt
        );
        summary.setLineStartPosition(firstStationAbsM);
        summary.setLineTargetPosition(stationEntries.get(stationEntries.size() - 1).km * 1000.0);
        summary.setFromStationName(stationEntries.get(0).name);
        summary.setToStationName(stationEntries.get(stationEntries.size() - 1).name);
        summary.setFromStationId(stationEntries.get(0).id);
        summary.setToStationId(stationEntries.get(stationEntries.size() - 1).id);
        summary.setTotalStations(stationEntries.size());
        summary.setCompletedStops(stationStops.size());
        double totalDwell = stationStops.stream().mapToDouble(com.bjtu.railtransit.vehicle.dto.StationStop::getDwellTime).sum();
        summary.setTotalDwellTime(totalDwell);

        SimulationResult result = new SimulationResult(allStates, summary, finalStopResult, allSafetyEvents);
        result.setStationStops(stationStops);
        return result;
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
        // speedLimit（阶段 1.6 704 语义对齐新增字段）：原样取自 LineProfile。
        // dtPerFrame：原样取自 scenario.getDt()，供前端计算播放 interval，不改变积分逻辑。
        SimulationSummary summary = new SimulationSummary(maxVelocity, last.getTime(), last.getPosition(), speedLimit, dtPerFrame);

        double actualStopPosition = last.getPosition();
        double stopError = actualStopPosition - targetStopPosition;
        boolean success = Math.abs(stopError) <= STOP_POSITION_TOLERANCE
                && last.getVelocity() <= STOP_VELOCITY_TOLERANCE;
        String reason = success
                ? null
                : String.format(
                        "停车误差 %.3fm 或末速度 %.3fm/s 超出参考阈值（位置容差 %.1fm，速度容差 %.1fm/s）。"
                                + "阶段2引入真实阻力/坡度后停站误差如实报告，重新收敛到该阈值留待阶段3实现",
                        stopError, last.getVelocity(), STOP_POSITION_TOLERANCE, STOP_VELOCITY_TOLERANCE);

        // stopWindowState（阶段 1.6 704 语义对齐新增字段）：依据 stopError 与末速度
        // 派生，与 success 判定保持等价，不引入新的判定阈值来源。
        StopWindowState stopWindowState = deriveStopWindowState(stopError, last.getVelocity());

        StopResult stopResult = new StopResult(
                targetStopPosition, actualStopPosition, stopError, success, reason, stopWindowState,
                brakeTriggerPosition, predictedStopPosition);

        List<SafetyEvent> safetyEvents = Collections.emptyList();

        return new SimulationResult(states, summary, stopResult, safetyEvents);
    }

    /**
     * 依据停站误差与末速度派生停车窗到位状态（对应 704 表13「窗内/冲标/欠标/未停准」）。
     *
     * <p>判定规则（与本轮任务要求一致，不写死枚举值，只是把已有的 stopError/末速度
     * 数值路由到对应的语义分支）：</p>
     * <ul>
     *   <li>末速度 &gt; 0.1 m/s -&gt; NOT_ACCURATE（未停准，车辆尚未停稳）；</li>
     *   <li>否则若 |stopError| &le; 0.5 m -&gt; IN_WINDOW（窗内）；</li>
     *   <li>否则若 stopError &gt; 0.5 m -&gt; OVERSHOOT（冲标）；</li>
     *   <li>否则（stopError &lt; -0.5 m）-&gt; UNDERSHOOT（欠标）。</li>
     * </ul>
     */
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
}
