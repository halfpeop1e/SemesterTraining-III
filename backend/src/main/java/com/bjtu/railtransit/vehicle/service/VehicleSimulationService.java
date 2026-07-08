package com.bjtu.railtransit.vehicle.service;

import com.bjtu.railtransit.vehicle.dto.SafetyEvent;
import com.bjtu.railtransit.vehicle.dto.SimulationResult;
import com.bjtu.railtransit.vehicle.dto.SimulationSummary;
import com.bjtu.railtransit.vehicle.dto.StopResult;
import com.bjtu.railtransit.vehicle.dto.TrainState;
import com.bjtu.railtransit.vehicle.enums.SimulationPhase;
import com.bjtu.railtransit.vehicle.model.LineProfile;
import com.bjtu.railtransit.vehicle.model.ScenarioConfig;
import com.bjtu.railtransit.vehicle.model.TrainModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 车辆纵向运动仿真核心逻辑（阶段 1A）。
 *
 * <p>本轮实现最简单的三段式纵向运动：牵引加速到限速 -&gt; 惰行/匀速 -&gt;
 * 按常用制动减速度制动至停车。{@code states} 由本类以 {@code dt} 为步长逐步
 * 循环计算生成，禁止写死数组冒充仿真。停站判定与安全事件留给阶段 3、阶段 4
 * 做真实化，本轮只保证字段结构齐全、物理约束合理。</p>
 */
@Service
public class VehicleSimulationService {

    /** 速度收敛判定阈值，单位 m/s：低于该值视为已停车。 */
    private static final double VELOCITY_EPSILON = 1.0e-3;

    /** 停站成功阈值（位置误差），单位 m，取自指挥书阶段 3 的成功标准，本轮先复用作参考判定。 */
    private static final double STOP_POSITION_TOLERANCE = 0.5;

    /** 停站成功阈值（末速度），单位 m/s。 */
    private static final double STOP_VELOCITY_TOLERANCE = 0.1;

    /** 循环步数上限，避免配置异常导致死循环。 */
    private static final int MAX_STEPS = 100_000;

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

        double startPosition = line.getStartPosition();
        double targetStopPosition = line.getTargetStopPosition();
        double speedLimit = line.getSpeedLimit();
        double maxAcceleration = train.getMaxAcceleration();
        double normalBrakeDeceleration = train.getNormalBrakeDeceleration();

        // 惰行阶段以限速匀速行驶，据此计算理论制动距离与理论触发位置：
        // 制动距离 = v^2 / (2 * 减速度)，理论触发位置 = 目标停车点 - 制动距离。
        double brakingDistance = (speedLimit * speedLimit) / (2.0 * normalBrakeDeceleration);
        double brakingStartPosition = targetStopPosition - brakingDistance;

        List<TrainState> states = new ArrayList<>();

        double t = 0.0;
        double pos = startPosition;
        double v = 0.0;

        for (int step = 0; step < MAX_STEPS; step++) {
            SimulationPhase phase;
            double acceleration;

            boolean stopped = v <= VELOCITY_EPSILON && pos >= brakingStartPosition - 1.0e-6;

            if (stopped) {
                phase = SimulationPhase.STOPPED;
                acceleration = 0.0;
                v = 0.0;
                states.add(new TrainState(t, pos, v, acceleration, phase, "T1"));
                break;
            }

            boolean alreadyPastTrigger = pos >= brakingStartPosition;

            // 离散检测延迟提前量补偿（阶段1补强）：
            // 原实现只在"当前 pos 已越过理论触发点"时才切入制动，但仿真按 dt 离散
            // 步进，非制动阶段每步以当前速度 v 前进 v*dt，触发点往往落在两个采样点
            // 之间。原实现要等到"下一次检查"（即越过之后的那一步）才会切入制动，
            // 造成最多再多走 v*dt 才开始制动，产生系统性多冲。
            //
            // 这里改为在每步开始时预测：按当前速度 v 匀速走完本步是否会越过理论
            // 触发点（(目标点 - 当前位置) <= v*dt，等价于预测的下一步位置将越过
            // 触发点）。一旦预测到会越过，就在本步内按越过时刻做分段积分——本步
            // 前半段仍按原阶段（牵引/惰行）匀速运行到理论触发点，本步后半段立即
            // 按制动减速度运行——而不是简单地把整步都判定为制动。
            //
            // 这样可以把"检测延迟"精确收敛到触发时刻本身（而不是收敛到某个整
            // dt 网格点），避免"整步提前量"导致的反向超调（提前太多、制动距离
            // 富余、停不到目标点）。这不是新的控制策略，只是让现有简化模型在离
            // 散步进下与连续时间理论解自洽。
            boolean willCrossThisStep = !alreadyPastTrigger && v > VELOCITY_EPSILON
                    && (brakingStartPosition - pos) <= v * dt;

            double posNext;
            double vNext;

            if (alreadyPastTrigger) {
                phase = SimulationPhase.BRAKING;
                acceleration = -normalBrakeDeceleration;

                vNext = v + acceleration * dt;
                if (vNext < 0.0) {
                    vNext = 0.0;
                }
                posNext = pos + 0.5 * (v + vNext) * dt;
            } else if (willCrossThisStep) {
                // 本步内分段：先以当前速度匀速走到理论触发点（timeToTrigger），
                // 剩余时间立即按常用制动减速度运行。记录的 phase/acceleration
                // 代表本步"主要发生的动作"（切入制动），位置/速度积分则按分段
                // 精确计算，不整体套用单一加速度。
                phase = SimulationPhase.BRAKING;
                acceleration = -normalBrakeDeceleration;

                double timeToTrigger = (brakingStartPosition - pos) / v;
                double remaining = dt - timeToTrigger;
                double vAtTrigger = v; // 匀速段速度不变
                double vAfterBrakePortion = vAtTrigger - normalBrakeDeceleration * remaining;
                if (vAfterBrakePortion < 0.0) {
                    vAfterBrakePortion = 0.0;
                }
                double posAtTrigger = pos + v * timeToTrigger;
                posNext = posAtTrigger + 0.5 * (vAtTrigger + vAfterBrakePortion) * remaining;
                vNext = vAfterBrakePortion;
            } else if (v < speedLimit - VELOCITY_EPSILON) {
                phase = SimulationPhase.TRACTION;
                acceleration = maxAcceleration;

                vNext = v + acceleration * dt;
                if (vNext > speedLimit) {
                    vNext = speedLimit;
                }
                posNext = pos + 0.5 * (v + vNext) * dt;
            } else {
                phase = SimulationPhase.COAST;
                acceleration = 0.0;

                vNext = v;
                posNext = pos + v * dt;
            }

            states.add(new TrainState(t, pos, v, acceleration, phase, "T1"));

            t += dt;
            pos = posNext;
            v = vNext;
        }

        if (states.isEmpty() || states.get(states.size() - 1).getPhase() != SimulationPhase.STOPPED) {
            throw new IllegalStateException("车辆仿真未在最大步数内收敛到停车状态，请检查演示配置参数");
        }

        return buildResult(states, targetStopPosition);
    }

    private SimulationResult buildResult(List<TrainState> states, double targetStopPosition) {
        double maxVelocity = 0.0;
        for (TrainState state : states) {
            if (state.getVelocity() > maxVelocity) {
                maxVelocity = state.getVelocity();
            }
        }

        TrainState last = states.get(states.size() - 1);
        SimulationSummary summary = new SimulationSummary(maxVelocity, last.getTime(), last.getPosition());

        double actualStopPosition = last.getPosition();
        double stopError = actualStopPosition - targetStopPosition;
        boolean success = Math.abs(stopError) <= STOP_POSITION_TOLERANCE
                && last.getVelocity() <= STOP_VELOCITY_TOLERANCE;
        String reason = success
                ? null
                : String.format(
                        "停车误差 %.3fm 或末速度 %.3fm/s 超出阶段1A参考阈值（位置容差 %.1fm，速度容差 %.1fm/s），"
                                + "精确停站控制留待阶段3实现",
                        stopError, last.getVelocity(), STOP_POSITION_TOLERANCE, STOP_VELOCITY_TOLERANCE);

        StopResult stopResult = new StopResult(targetStopPosition, actualStopPosition, stopError, success, reason);

        List<SafetyEvent> safetyEvents = Collections.emptyList();

        return new SimulationResult(states, summary, stopResult, safetyEvents);
    }
}
