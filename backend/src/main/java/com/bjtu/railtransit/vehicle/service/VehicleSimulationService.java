package com.bjtu.railtransit.vehicle.service;

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
     * <p>预测式制动触发在每个子步调用 predictStopPosition，复杂度
     * O(步数²)。当前 1.2km 演示配置无性能问题。</p>
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

    // ==================== 多站连续仿真（本轮新增）====================

    /**
     * 默认驻留时间，单位 s。
     */
    static final double DEFAULT_DWELL_TIME_SECONDS = 30.0;

    /**
     * 多站连续仿真：从 stationEntries[0] 出发，经若干中间站，到达最后一站。
     *
     * <p><b>坐标规则</b>：返回的所有 {@link com.bjtu.railtransit.vehicle.dto.TrainState}
     * 的 {@code position} 均从 fromStation 开始连续累积，跨越中间站不归零。
     * {@link com.bjtu.railtransit.vehicle.dto.StopResult#getTargetStopPosition()}
     * 等于 fromStation 到最终 toStation 的总距离，与 states 末态 position 同坐标系。</p>
     *
     * <p>实现方式：对每段区间调用 {@link #run(com.bjtu.railtransit.vehicle.model.ScenarioConfig)}
     * 得到局部 states（position 0→segDist），然后对每个局部 state 加上 positionOffset
     * 得到全程累积坐标。absolutePosition = lineStartAbsM + 全程累积 position。</p>
     */
    public SimulationResult runMultiStation(
            java.util.List<LineProfileJsonLoader.StationEntry> stationEntries,
            Double dwellTimeSeconds,
            DemoScenarioProvider demoProvider) {

        if (stationEntries == null || stationEntries.size() < 2) {
            throw new IllegalArgumentException("多站仿真至少需要 2 个站点");
        }

        double dwell = dwellTimeSeconds != null ? dwellTimeSeconds : DEFAULT_DWELL_TIME_SECONDS;
        double dt = 0.5; // DemoScenarioProvider.DT，不改 dtPerFrame

        java.util.List<com.bjtu.railtransit.vehicle.dto.TrainState> allStates = new java.util.ArrayList<>();
        java.util.List<com.bjtu.railtransit.vehicle.dto.SafetyEvent> allSafetyEvents = new java.util.ArrayList<>();
        java.util.List<com.bjtu.railtransit.vehicle.dto.StationStop> stationStops = new java.util.ArrayList<>();

        double lineStartAbsM = stationEntries.get(0).km * 1000.0;
        double positionOffset = 0.0;  // 本次仿真已走过的累积里程（= 前几段之和）
        double timeOffset = 0.0;      // 全程累积时间
        double maxVelocityAll = 0.0;
        com.bjtu.railtransit.vehicle.dto.StopResult finalStopResult = null;

        double totalTargetPosition = 0.0;
        for (int seg = 0; seg < stationEntries.size() - 1; seg++) {
            totalTargetPosition += (stationEntries.get(seg + 1).km - stationEntries.get(seg).km) * 1000.0;
        }

        for (int seg = 0; seg < stationEntries.size() - 1; seg++) {
            LineProfileJsonLoader.StationEntry fromSt = stationEntries.get(seg);
            LineProfileJsonLoader.StationEntry toSt = stationEntries.get(seg + 1);
            boolean isLastSeg = (seg == stationEntries.size() - 2);

            double segDistM = (toSt.km - fromSt.km) * 1000.0;
            if (segDistM <= 0) {
                throw new IllegalArgumentException(
                        "区间 " + fromSt.name + " → " + toSt.name + " 距离为 0 或负值");
            }

            com.bjtu.railtransit.vehicle.model.LineProfile segLine =
                    new com.bjtu.railtransit.vehicle.model.LineProfile(
                            0.0, segDistM,
                            LineProfileJsonLoader.ASSUMED_SPEED_LIMIT_MPS,
                            java.util.Collections.emptyList());
            com.bjtu.railtransit.vehicle.model.ScenarioConfig segScenario =
                    demoProvider.buildScenario(segLine);

            SimulationResult segResult = run(segScenario);
            java.util.List<com.bjtu.railtransit.vehicle.dto.TrainState> segStates = segResult.getStates();

            // 把局部 states 加偏移，写入全程连续坐标
            for (com.bjtu.railtransit.vehicle.dto.TrainState st : segStates) {
                double globalPos = positionOffset + st.getPosition();
                double absPos = lineStartAbsM + globalPos;
                com.bjtu.railtransit.vehicle.dto.TrainState adjusted = new com.bjtu.railtransit.vehicle.dto.TrainState(
                        st.getTime() + timeOffset,
                        globalPos,
                        st.getVelocity(),
                        st.getAcceleration(),
                        st.getPhase(),
                        "T1"
                );
                adjusted.setAbsolutePosition(absPos);
                allStates.add(adjusted);
                if (st.getVelocity() > maxVelocityAll) maxVelocityAll = st.getVelocity();
            }

            // 追加区间 safetyEvents（time 平移，position 加偏移）
            for (com.bjtu.railtransit.vehicle.dto.SafetyEvent se : segResult.getSafetyEvents()) {
                allSafetyEvents.add(new com.bjtu.railtransit.vehicle.dto.SafetyEvent(
                        se.getReason(),
                        se.getTime() + timeOffset,
                        positionOffset + se.getPosition(),
                        se.getVelocity(),
                        se.getAction()
                ));
            }

            // 记录本站停车信息
            com.bjtu.railtransit.vehicle.dto.TrainState lastSeg = segStates.get(segStates.size() - 1);
            double arrivalTime = lastSeg.getTime() + timeOffset;
            double targetSegPos = positionOffset + segDistM;        // 累积目标
            double actualSegPos = positionOffset + lastSeg.getPosition(); // 累积实际
            double segStopError = lastSeg.getPosition() - segDistM;
            boolean inWindow = Math.abs(segStopError) <= STOP_POSITION_TOLERANCE
                    && lastSeg.getVelocity() <= STOP_VELOCITY_TOLERANCE;

            stationStops.add(new com.bjtu.railtransit.vehicle.dto.StationStop(
                    toSt.id, toSt.name,
                    positionOffset, targetSegPos, actualSegPos,
                    segStopError, inWindow,
                    arrivalTime, isLastSeg ? 0.0 : dwell
            ));

            // 更新时间偏移（含本段运行时间）
            timeOffset += lastSeg.getTime();

            if (isLastSeg) {
                // 最终站：构造全程 stopResult（targetStopPosition = 全程总距离）
                com.bjtu.railtransit.vehicle.enums.StopWindowState ws =
                        deriveStopWindowState(segStopError, lastSeg.getVelocity());
                finalStopResult = new StopResult(
                        totalTargetPosition,       // 全程总目标距离
                        actualSegPos,              // 实际停车绝对累积里程
                        actualSegPos - totalTargetPosition,
                        inWindow,
                        inWindow ? null : String.format("停车误差 %.3fm 或末速度 %.3fm/s 超出阈值",
                                segStopError, lastSeg.getVelocity()),
                        ws,
                        positionOffset + (segResult.getStopResult() != null
                                ? segResult.getStopResult().getBrakeTriggerPosition() : 0.0),
                        positionOffset + (segResult.getStopResult() != null
                                ? segResult.getStopResult().getPredictedStopPosition() : 0.0)
                );
            } else {
                // 中间站：插入 dwell 帧（position 不变，time 递增）
                double dwellPos = actualSegPos;    // 累积相对里程，不变
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

                // 更新偏移（下一段起点）
                timeOffset += dwell;
                positionOffset = actualSegPos;
            }
        }

        // 构造汇总 summary
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

    // ==================== 驾驶员控制续算（本轮新增）====================

    /**
     * 驾驶员控制续算：从请求携带的当前帧状态开始，根据 currentMode 和 controlCommand
     * 重新续算后续状态序列。
     *
     * <p><b>坐标规则</b>：{@code request.currentState.position} 是全程累积相对里程，
     * {@code request.totalTargetPosition} 是本次仿真从 fromStation 到 toStation 的总距离。
     * 续算在"从当前位置到总目标"的剩余距离上进行，返回的 states.position 以
     * currentState.position 为起点继续累积（不归零）。</p>
     *
     * <p><b>模式语义</b>：</p>
     * <ul>
     *   <li>ATO + 普通 brake → 拒绝，保持 ATO 自动续算（不切换模式）。</li>
     *   <li>MANUAL + brake → targetDecel 约束在 [0, normalBrakeDeceleration]，真实续算。</li>
     *   <li>任意模式 + emergency_brake → EMERGENCY，使用 emergencyBrakeDeceleration，中断多站任务。</li>
     *   <li>ATO → MANUAL 模式切换（coast 指令）→ 切换模式，但按 ATO 自动策略续算轨迹。</li>
     * </ul>
     */
    public SimulationResult runContinuation(SimulationControlRequest request, ScenarioConfig scenario) {
        if (request.getCurrentState() == null) throw new IllegalArgumentException("currentState 不能为空");
        if (request.getCurrentMode() == null) throw new IllegalArgumentException("currentMode 不能为空");
        if (request.getControlCommand() == null) throw new IllegalArgumentException("controlCommand 不能为空");

        LineProfile line = scenario.getLineProfile();
        TrainModel train = scenario.getTrainModel();
        double dt = scenario.getDt();
        double dtSub = dt / SUB_STEPS_PER_SAMPLE;
        double speedLimit = line.getSpeedLimit();

        // 续算的"本地目标停车位置"= 全程总目标 - 当前累积位置
        // 使用局部坐标跑物理积分，结果再加回偏移
        double currentCumulativePos = request.getCurrentState().getPosition();
        double totalTarget = request.getTotalTargetPosition();
        double localTarget = totalTarget - currentCumulativePos; // 剩余距离

        com.bjtu.railtransit.vehicle.dto.ControlCommand cmd = request.getControlCommand();
        DrivingMode inputMode = request.getCurrentMode();
        String commandStr = cmd.getCommand() != null ? cmd.getCommand().toLowerCase() : "";
        boolean isEB = "emergency_brake".equals(commandStr);

        // 确定实际执行模式
        DrivingMode effectiveMode;
        if (isEB) {
            effectiveMode = DrivingMode.EMERGENCY;
        } else if (inputMode == DrivingMode.ATO && "brake".equals(commandStr)) {
            // ATO 下普通 brake 被拒绝，保持 ATO
            effectiveMode = DrivingMode.ATO;
        } else {
            effectiveMode = inputMode;
        }

        double startT = request.getCurrentState().getTime();
        double localPos = 0.0;     // 续算时的局部位置（从 0 开始）
        double v = request.getCurrentState().getVelocity();
        double t = startT;

        java.util.List<com.bjtu.railtransit.vehicle.dto.TrainState> states = new java.util.ArrayList<>();
        java.util.List<com.bjtu.railtransit.vehicle.dto.SafetyEvent> safetyEvents = new java.util.ArrayList<>();

        boolean brakingTriggered = false;
        boolean coastEntered = false;
        double brakeResponseRemaining = 0.0;
        double brakeTriggerPosition = 0.0;
        double predictedStopPositionAtTrigger = 0.0;

        // MANUAL brake：立即触发
        double manualTargetDecel = 0.0;
        if (effectiveMode == DrivingMode.MANUAL && "brake".equals(commandStr)) {
            manualTargetDecel = Math.min(Math.max(cmd.getTargetDecel(), 0.0),
                    train.getNormalBrakeDeceleration());
            brakingTriggered = true;
            brakeTriggerPosition = localPos;
            brakeResponseRemaining = train.getBrakeResponseTime();
        }

        // EMERGENCY：立即触发，生成 SafetyEvent
        if (effectiveMode == DrivingMode.EMERGENCY) {
            brakingTriggered = true;
            brakeTriggerPosition = localPos;
            brakeResponseRemaining = train.getBrakeResponseTime();
            String reason = isEB ? "DRIVER_EMERGENCY_BRAKE" : "EMERGENCY_MODE_ENGAGED";
            safetyEvents.add(new com.bjtu.railtransit.vehicle.dto.SafetyEvent(
                    reason, t, currentCumulativePos, v, "emergency_brake"));
        }

        final double fManualDecel = manualTargetDecel;
        final DrivingMode fMode = effectiveMode;

        // 续算积分（局部坐标，0 → localTarget）
        for (int step = 0; step < MAX_STEPS; step++) {
            if (brakingTriggered && v <= VELOCITY_EPSILON) {
                // 写入全程累积坐标
                states.add(makeGlobal(t, localPos, 0.0, 0.0,
                        SimulationPhase.STOPPED, currentCumulativePos,
                        request.getCurrentState().getAbsolutePosition()));
                break;
            }

            double sampleT = t;
            double sampleLocalPos = localPos;
            double sampleV = v;
            SimulationPhase samplePhase = null;
            double sampleAccel = 0.0;

            for (int sub = 0; sub < SUB_STEPS_PER_SAMPLE; sub++) {
                if (brakingTriggered && v <= VELOCITY_EPSILON) {
                    if (samplePhase == null) { samplePhase = SimulationPhase.STOPPED; sampleAccel = 0.0; }
                    break;
                }

                SimulationPhase phase;
                double acceleration;
                double drag = computeNetDrag(localPos, v, train, line);

                if (brakingTriggered) {
                    phase = SimulationPhase.BRAKING;
                    if (brakeResponseRemaining > 0) {
                        acceleration = -drag;
                        brakeResponseRemaining -= dtSub;
                    } else {
                        double brakeDecel = (fMode == DrivingMode.EMERGENCY)
                                ? train.getEmergencyBrakeDeceleration()
                                : (fMode == DrivingMode.MANUAL) ? fManualDecel
                                : train.getNormalBrakeDeceleration();
                        acceleration = -brakeDecel - drag;
                    }
                } else if (fMode == DrivingMode.ATO || fMode == DrivingMode.MANUAL) {
                    // ATO 或 MANUAL（无 brake 指令）：使用 ATO 自动策略
                    double predicted = predictStopPosition(localPos, v, train, line, dtSub);
                    if (predicted >= localTarget) {
                        brakingTriggered = true;
                        brakeTriggerPosition = localPos;
                        predictedStopPositionAtTrigger = predicted;
                        brakeResponseRemaining = train.getBrakeResponseTime();
                        phase = SimulationPhase.BRAKING;
                        acceleration = -drag;
                    } else if (!coastEntered && v < speedLimit - VELOCITY_EPSILON) {
                        phase = SimulationPhase.TRACTION;
                        acceleration = train.getMaxAcceleration() - drag;
                    } else {
                        coastEntered = true;
                        phase = SimulationPhase.COAST;
                        acceleration = -drag;
                    }
                } else {
                    phase = SimulationPhase.COAST;
                    acceleration = -drag;
                }

                double vNext = v + acceleration * dtSub;
                if (vNext < 0.0) vNext = 0.0;
                if (phase == SimulationPhase.TRACTION && vNext >= speedLimit) {
                    vNext = speedLimit; coastEntered = true;
                }
                double posNext = localPos + 0.5 * (v + vNext) * dtSub;

                // SafetyGuard：超速检查
                if (vNext > speedLimit + VELOCITY_EPSILON && !brakingTriggered) {
                    safetyEvents.add(new com.bjtu.railtransit.vehicle.dto.SafetyEvent(
                            "OVERSPEED",
                            t + (sub + 1) * dtSub,
                            currentCumulativePos + posNext,
                            vNext,
                            "emergency_brake"
                    ));
                    brakingTriggered = true;
                    brakeTriggerPosition = posNext;
                    predictedStopPositionAtTrigger = predictStopPosition(posNext, vNext, train, line, dtSub);
                    brakeResponseRemaining = 0.0;
                    phase = SimulationPhase.BRAKING;
                }

                if (sub == 0) { samplePhase = phase; sampleAccel = acceleration; }
                t += dtSub; localPos = posNext; v = vNext;
            }

            if (samplePhase == null) samplePhase = SimulationPhase.STOPPED;
            states.add(makeGlobal(sampleT, sampleLocalPos, sampleV, sampleAccel,
                    samplePhase, currentCumulativePos,
                    request.getCurrentState().getAbsolutePosition()));
        }

        if (states.isEmpty() || states.get(states.size() - 1).getPhase() != SimulationPhase.STOPPED) {
            throw new IllegalStateException("控制续算未在最大步数内收敛到停车状态");
        }

        // 构造结果：targetStopPosition 仍使用全程总目标距离
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

        // 续算时只有这一段 states，不保留旧的 stationStops（EB 中断多站任务）
        double maxV = states.stream().mapToDouble(com.bjtu.railtransit.vehicle.dto.TrainState::getVelocity).max().orElse(0);
        SimulationSummary summary = new SimulationSummary(
                maxV, lastState.getTime(), lastState.getPosition(),
                speedLimit, dt);
        summary.setCurrentMode(fMode);
        if (fMode == DrivingMode.EMERGENCY && lastState.getPhase() == SimulationPhase.STOPPED) {
            summary.setNextMode(DrivingMode.MANUAL);
        }

        SimulationResult result = new SimulationResult(states, summary, stopResult, safetyEvents);
        // 清空 stationStops：EB 或续算中断了多站任务
        result.setStationStops(java.util.Collections.emptyList());
        return result;
    }

    /**
     * 将局部坐标（0→segDist）的 state 转换为全程累积坐标。
     *
     * @param localPos           局部位置（0 起点算）
     * @param cumulativeOffset   当前帧之前的累积里程
     * @param baseAbsolutePos    续算起点的 absolutePosition（可为 null）
     */
    private com.bjtu.railtransit.vehicle.dto.TrainState makeGlobal(
            double t, double localPos, double velocity, double acceleration,
            SimulationPhase phase,
            double cumulativeOffset, Double baseAbsolutePos) {
        double globalPos = cumulativeOffset + localPos;
        com.bjtu.railtransit.vehicle.dto.TrainState st =
                new com.bjtu.railtransit.vehicle.dto.TrainState(
                        t, globalPos, velocity, acceleration, phase, "T1");
        if (baseAbsolutePos != null) {
            st.setAbsolutePosition(baseAbsolutePos + localPos);
        }
        return st;
    }
}
