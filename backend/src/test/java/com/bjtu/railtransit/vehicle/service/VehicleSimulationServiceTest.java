package com.bjtu.railtransit.vehicle.service;

import com.bjtu.railtransit.vehicle.dto.SimulationResult;
import com.bjtu.railtransit.vehicle.dto.StopResult;
import com.bjtu.railtransit.vehicle.dto.TrainState;
import com.bjtu.railtransit.vehicle.enums.SimulationPhase;
import com.bjtu.railtransit.vehicle.enums.StopWindowState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 车辆仿真核心逻辑单元测试（阶段1建立、阶段2补充真实动力学验证、阶段3B制动响应时间补偿）。
 *
 * <p>验证 states 由 service 循环计算生成、非空，速度全程非负，位置随时间
 * 单调不减，末态速度收敛趋近 0，且四段响应字段（states/summary/stopResult/
 * safetyEvents）均存在。阶段2额外验证 Davis 阻力/坡度阻力确实进入加速度计算，
 * 并如实反映引入真实阻力后停站误差变化。阶段3B验证制动响应时间补偿后停站误差
 * 收敛回 0.5m 停车窗内。</p>
 */
class VehicleSimulationServiceTest {

    private final VehicleSimulationService service = new VehicleSimulationService(new DemoScenarioProvider());

    @Test
    void statesAreNonEmptyAndGeneratedByLoop() {
        SimulationResult result = service.runDemoSimulation();

        assertNotNull(result.getStates());
        assertFalse(result.getStates().isEmpty(), "states 不能为空，必须由循环计算生成");
        // 阶段1A 的演示配置（限速20m/s、加速度1.0m/s2、dt=0.5s、线路1200m）
        // 理论步数远大于个别写死的固定数组长度，用步数下限间接佐证是逐步计算而非硬编码几条数据。
        assertTrue(result.getStates().size() > 50,
                "states 数量过少，可能未按 dt 循环计算完整仿真过程");
    }

    @Test
    void velocityIsNeverNegative() {
        SimulationResult result = service.runDemoSimulation();

        for (TrainState state : result.getStates()) {
            assertTrue(state.getVelocity() >= 0.0,
                    "速度不能为负，出现在 time=" + state.getTime());
        }
    }

    @Test
    void positionIsMonotonicNonDecreasing() {
        SimulationResult result = service.runDemoSimulation();
        List<TrainState> states = result.getStates();

        double previousPosition = states.get(0).getPosition();
        for (TrainState state : states) {
            assertTrue(state.getPosition() >= previousPosition - 1.0e-9,
                    "位置必须随时间单调不减，出现在 time=" + state.getTime());
            previousPosition = state.getPosition();
        }
    }

    @Test
    void finalStateConvergesToStop() {
        SimulationResult result = service.runDemoSimulation();
        List<TrainState> states = result.getStates();
        TrainState last = states.get(states.size() - 1);

        assertEquals(SimulationPhase.STOPPED, last.getPhase());
        assertTrue(last.getVelocity() < 0.2, "末态速度应收敛趋近 0，实际为 " + last.getVelocity());
    }

    @Test
    void responseContainsAllFourSections() {
        SimulationResult result = service.runDemoSimulation();

        assertNotNull(result.getStates());
        assertNotNull(result.getSummary());
        assertNotNull(result.getStopResult());
        assertNotNull(result.getSafetyEvents());

        assertTrue(result.getSummary().getMaxVelocity() > 0.0);
        assertTrue(result.getSummary().getTotalTime() > 0.0);
        assertTrue(result.getSummary().getFinalPosition() > 0.0);

        // stopResult 字段齐全（占位但结构完整）
        assertEquals(1200.0, result.getStopResult().getTargetStopPosition());
        assertNotNull(result.getStopResult()); // actualStopPosition/stopError/success 均已在 buildResult 中赋值

        // 阶段1A 不实现 SafetyGuard，safetyEvents 应为空数组而非 null
        assertTrue(result.getSafetyEvents().isEmpty());
    }

    @Test
    void allStatesBelongToTrainT1() {
        SimulationResult result = service.runDemoSimulation();
        for (TrainState state : result.getStates()) {
            assertEquals("T1", state.getTrainId());
        }
    }

    /**
     * 阶段 3B 核心验证：引入制动响应时间补偿后，演示配置下停站误差应收敛到停车窗内
     * （{@code |stopError| <= 0.5m}），stopWindowState 为 IN_WINDOW，success 为 true。
     *
     * <p>阶段2第一次交付（无制动响应时间补偿）时停站误差约0.57m，因制动指令下达到
     * 制动真正生效之间有 0.5s 的响应延迟未被补偿，预测式触发低估了实际停车距离。
     * 阶段3B在 predictStopPosition 和主循环制动分支两处都加入了制动响应时间补偿
     * （响应时间内只受阻力，不施加制动减速度），使预测更接近真实动力学，停站误差
     * 重新收敛到 0.5m 窗内。</p>
     */
    @Test
    void stopErrorConvergesToStopWindowUnderBrakeResponseCompensation() {
        SimulationResult result = service.runDemoSimulation();
        StopResult stopResult = result.getStopResult();

        assertTrue(Math.abs(stopResult.getStopError()) <= 0.5,
                "阶段3B引入制动响应时间补偿后，演示配置下停站误差应收敛到停车窗内（<=0.5m），"
                        + "实际 stopError=" + stopResult.getStopError());
        assertEquals(StopWindowState.IN_WINDOW, stopResult.getStopWindowState(),
                "停站误差在窗内且末速度已收敛，stopWindowState 应为 IN_WINDOW");
        assertTrue(stopResult.isSuccess(),
                "停站误差在窗内且末速度已收敛，success 应为 true");
    }

    /**
     * 阶段 3B 验证：制动响应时间补偿确实让列车更早触发制动。
     *
     * <p>对比两个场景：
     * <ol>
     *   <li>{@link DemoScenarioProvider#getDemoScenario()}（默认 {@code brakeResponseTime=0.5s}）</li>
     *   <li>{@link DemoScenarioProvider#getDemoScenarioWithBrakeResponseTime(double)} 传入 0.0s</li>
     * </ol>
     * 其余参数完全一致。响应时间更长（0.5s）的场景，制动触发位置应更早（brakeTriggerPosition 更小），
     * 因为预测阶段考虑了额外 0.5s 的阻力-only 滑行，所以预测停车位置到达目标点会更早被触发。</p>
     */
    @Test
    void brakeResponseTimeCompensationTriggersEarlierWithLongerResponseTime() {
        SimulationResult defaultScenario = service.runDemoSimulation();
        SimulationResult zeroResponseTime = service.run(
                new DemoScenarioProvider().getDemoScenarioWithBrakeResponseTime(0.0));

        double triggerPosWithResponse = defaultScenario.getStopResult().getBrakeTriggerPosition();
        double triggerPosWithoutResponse = zeroResponseTime.getStopResult().getBrakeTriggerPosition();

        assertTrue(triggerPosWithResponse > 0.0,
                "演示配置下制动触发位置应为正值，实际为 " + triggerPosWithResponse);
        assertTrue(triggerPosWithoutResponse > 0.0,
                "零响应时间场景制动触发位置应为正值，实际为 " + triggerPosWithoutResponse);
        assertTrue(triggerPosWithResponse < triggerPosWithoutResponse,
                "制动响应时间更长（0.5s）的场景应更早触发制动（brakeTriggerPosition 更小），"
                        + "实际 0.5s 场景触发位置=" + triggerPosWithResponse
                        + "，0.0s 场景触发位置=" + triggerPosWithoutResponse);
    }

    /**
     * 阶段 3B 验证：预测停车位置与实际停车位置的差异应保持在合理范围内。
     *
     * <p>由于预测式制动在触发时刻调用了 {@code predictStopPosition} 生成预测停车位置，
     * 而实际制动过程由主循环中完全相同的动力学模型和响应时间补偿执行，两者应高度一致。
     * 断言 {@code |predictedStopPosition - actualStopPosition| <= 1.0m}，即预测误差
     * 不超过 1 米（影响因素：子步长离散化与预测步长的细微差异）。</p>
     */
    @Test
    void predictedStopPositionIsConsistentWithActualStopPosition() {
        SimulationResult result = service.runDemoSimulation();
        StopResult stopResult = result.getStopResult();

        double predicted = stopResult.getPredictedStopPosition();
        double actual = stopResult.getActualStopPosition();

        assertTrue(predicted > 0.0,
                "predictedStopPosition 应为正值，实际为 " + predicted);
        assertTrue(actual > 0.0,
                "actualStopPosition 应为正值，实际为 " + actual);
        double predictionError = Math.abs(predicted - actual);
        assertTrue(predictionError <= 1.0,
                "predictedStopPosition 与 actualStopPosition 的差异应不超过 1.0m，"
                        + "实际差异=" + predictionError + "m，"
                        + "predicted=" + predicted + "，actual=" + actual);
    }

    /**
     * 阶段 3B 验证：速度收敛阈值与业务停车判定阈值应保持分离。
     *
     * <p>{@code VELOCITY_EPSILON(1e-3)} 是数值积分收敛阈值，
     * {@code STOP_VELOCITY_TOLERANCE(0.1)} 和 {@code STOP_POSITION_TOLERANCE(0.5)}
     * 是业务停车判定阈值。两者语义不同，不应合并。通过反射验证三个常量的精确值。</p>
     */
    @Test
    void velocityThresholdsAreSeparateAndUnchanged() throws Exception {
        java.lang.reflect.Field velocityEpsilonField = VehicleSimulationService.class
                .getDeclaredField("VELOCITY_EPSILON");
        velocityEpsilonField.setAccessible(true);
        double velocityEpsilon = velocityEpsilonField.getDouble(null);
        assertEquals(1.0e-3, velocityEpsilon, 1.0e-12,
                "VELOCITY_EPSILON 应为 1.0e-3");

        java.lang.reflect.Field stopVelocityField = VehicleSimulationService.class
                .getDeclaredField("STOP_VELOCITY_TOLERANCE");
        stopVelocityField.setAccessible(true);
        double stopVelocity = stopVelocityField.getDouble(null);
        assertEquals(0.1, stopVelocity, 1.0e-12,
                "STOP_VELOCITY_TOLERANCE 应为 0.1");

        java.lang.reflect.Field stopPositionField = VehicleSimulationService.class
                .getDeclaredField("STOP_POSITION_TOLERANCE");
        stopPositionField.setAccessible(true);
        double stopPosition = stopPositionField.getDouble(null);
        assertEquals(0.5, stopPosition, 1.0e-12,
                "STOP_POSITION_TOLERANCE 应为 0.5");
    }

    /**
     * 阶段 2 验证：Davis 阻力确实进入了加速度计算，而不是定义了系数却没接入积分循环。
     *
     * <p>对比含 Davis 阻力（且不含坡度，隔离变量）与零阻力两种场景：惰行阶段本应
     * 因为阻力存在而发生速度衰减；零阻力场景下惰行阶段应保持速度不变（旧的匀速假设）。
     * 用"惰行阶段末速度是否低于惰行阶段首速度"来判定阻力是否真的在起作用。</p>
     */
    @Test
    void davisResistanceActuallyAffectsCoastPhaseVelocity() {
        SimulationResult withResistance = service.run(new DemoScenarioProvider().getDemoScenarioWithoutGrade());
        SimulationResult zeroResistance = service.run(new DemoScenarioProvider().getZeroResistanceDemoScenario());

        double coastVelocityDropWithResistance = coastPhaseVelocityDrop(withResistance.getStates());
        double coastVelocityDropZeroResistance = coastPhaseVelocityDrop(zeroResistance.getStates());

        assertTrue(coastVelocityDropWithResistance > 0.05,
                "含 Davis 阻力时惰行阶段应发生可观测的速度衰减，实际衰减为 "
                        + coastVelocityDropWithResistance);
        assertTrue(coastVelocityDropZeroResistance < 1.0e-6,
                "零阻力场景惰行阶段应保持匀速（速度基本不变），实际衰减为 "
                        + coastVelocityDropZeroResistance);
    }

    /**
     * 阶段 2 验证：坡度阻力确实进入了加速度计算。
     *
     * <p>对比含坡度演示场景（{@link DemoScenarioProvider#getDemoScenario()}，
     * 800~1200m 为 3‰ 上坡）与不含坡度场景（其余参数完全一致），坡度段内
     * 上坡应带来更大的减速效应，因此含坡度场景在坡度段内的净加速度应比不含坡度场景
     * 更小（更负/更慢），从而两者最终仿真时长应有可观测差异。</p>
     */
    @Test
    void gradeResistanceActuallyAffectsAcceleration() {
        SimulationResult withGrade = service.runDemoSimulation();
        SimulationResult withoutGrade = service.run(new DemoScenarioProvider().getDemoScenarioWithoutGrade());

        double withGradeTotalTime = withGrade.getSummary().getTotalTime();
        double withoutGradeTotalTime = withoutGrade.getSummary().getTotalTime();

        assertTrue(withGradeTotalTime > withoutGradeTotalTime,
                "含 3‰ 上坡坡度段的场景总运行时间应比不含坡度场景更长（上坡增加阻力、拉长运行时间），"
                        + "实际 withGrade=" + withGradeTotalTime + " withoutGrade=" + withoutGradeTotalTime);
    }

    /**
     * 阶段 2 验证：一旦切入制动阶段（或停车），phase 不可逆——不会出现制动后又跳回
     * 牵引/惰行的抖动（预测式触发本身应保证单调性，这里做结构性回归）。
     */
    @Test
    void brakingPhaseIsIrreversibleOnceTriggered() {
        SimulationResult result = service.runDemoSimulation();
        boolean brakingSeen = false;
        for (TrainState state : result.getStates()) {
            if (state.getPhase() == SimulationPhase.BRAKING || state.getPhase() == SimulationPhase.STOPPED) {
                brakingSeen = true;
            } else if (brakingSeen) {
                throw new AssertionError("phase 在切入制动/停车后不应再回到 " + state.getPhase());
            }
        }
        assertTrue(brakingSeen, "演示配置下应观测到至少一次制动阶段");
    }

    /**
     * 计算 states 中惰行（COAST）阶段的首末速度差（首速度 - 末速度），
     * 用于验证阻力是否让惰行阶段真正发生了速度衰减。若不存在惰行阶段返回 0。
     */
    private double coastPhaseVelocityDrop(List<TrainState> states) {
        Double firstCoastVelocity = null;
        Double lastCoastVelocity = null;
        for (TrainState state : states) {
            if (state.getPhase() == SimulationPhase.COAST) {
                if (firstCoastVelocity == null) {
                    firstCoastVelocity = state.getVelocity();
                }
                lastCoastVelocity = state.getVelocity();
            }
        }
        if (firstCoastVelocity == null || lastCoastVelocity == null) {
            return 0.0;
        }
        return firstCoastVelocity - lastCoastVelocity;
    }

    /**
     * 阶段 1.6 704 语义对齐验证：summary.speedLimit 应等于线路限速（内置演示配置为
     * 20 m/s），且不影响 states 生成算法本身（前面几个测试已验证 states 数值不变）。
     */
    @Test
    void summarySpeedLimitMatchesLineProfile() {
        SimulationResult result = service.runDemoSimulation();

        assertEquals(20.0, result.getSummary().getSpeedLimit(), 1.0e-9,
                "summary.speedLimit 应等于 DemoScenarioProvider 中配置的线路限速");
    }

    /**
     * dtPerFrame 验证：summary.dtPerFrame 应等于 ScenarioConfig.dt（演示配置 0.5s）。
     * 前端播放 interval = dtPerFrame * 1000 / speedMultiplier，倍速只改前端 interval，
     * 不改后端积分步长，因此 dtPerFrame 必须原样取自 scenario.getDt()。
     */
    @Test
    void summaryDtPerFrameMatchesScenarioDt() {
        SimulationResult result = service.runDemoSimulation();

        assertEquals(0.5, result.getSummary().getDtPerFrame(), 1.0e-9,
                "summary.dtPerFrame 应等于 DemoScenarioProvider 中配置的 dt=0.5s");
    }

    /**
     * 阶段 1.6+阶段3B：stopWindowState 应与 success 判定保持等价。
     *
     * <p>阶段3B引入制动响应时间补偿后，演示配置下停站误差已收敛到窗内，因此预计
     * stopWindowState == IN_WINDOW 且 success == true。同时验证两者的等价关系不变。</p>
     */
    @Test
    void stopWindowStateMatchesSuccessDerivationForDemoScenario() {
        SimulationResult result = service.runDemoSimulation();
        StopResult stopResult = result.getStopResult();

        boolean isInWindow = stopResult.getStopWindowState() == StopWindowState.IN_WINDOW;
        assertEquals(stopResult.isSuccess(), isInWindow,
                "success 应与 stopWindowState==IN_WINDOW 保持等价，success=" + stopResult.isSuccess()
                        + " stopWindowState=" + stopResult.getStopWindowState()
                        + " stopError=" + stopResult.getStopError());
        // 阶段3B引入制动响应时间补偿后，停站误差应收敛到窗内。
        assertEquals(StopWindowState.IN_WINDOW, stopResult.getStopWindowState(),
                "阶段3B制动响应时间补偿后，演示配置下停站误差应在窗内，应判定为 IN_WINDOW，"
                        + "实际 stopError=" + stopResult.getStopError());
    }

    /**
     * 阶段 1.6 704 语义对齐验证：stopWindowState 的派生规则应与 stopError/末速度
     * 保持一致（覆盖 IN_WINDOW/OVERSHOOT/UNDERSHOOT/NOT_ACCURATE 四种分支的判定逻辑，
     * 通过反射复用 service 内部的派生方法进行独立验证，不重复硬编码判定规则）。
     */
    @Test
    void stopWindowStateDerivationRulesAreConsistent() throws Exception {
        java.lang.reflect.Method deriveMethod = VehicleSimulationService.class
                .getDeclaredMethod("deriveStopWindowState", double.class, double.class);
        deriveMethod.setAccessible(true);

        // |stopError| <= 0.5 且末速度 <= 0.1 -> IN_WINDOW
        assertEquals(StopWindowState.IN_WINDOW, deriveMethod.invoke(service, 0.3, 0.05));
        assertEquals(StopWindowState.IN_WINDOW, deriveMethod.invoke(service, -0.5, 0.1));

        // stopError > 0.5 且末速度 <= 0.1 -> OVERSHOOT
        assertEquals(StopWindowState.OVERSHOOT, deriveMethod.invoke(service, 0.8, 0.05));

        // stopError < -0.5 且末速度 <= 0.1 -> UNDERSHOOT
        assertEquals(StopWindowState.UNDERSHOOT, deriveMethod.invoke(service, -0.8, 0.05));

        // 末速度 > 0.1 -> NOT_ACCURATE（无论 stopError 数值如何）
        assertEquals(StopWindowState.NOT_ACCURATE, deriveMethod.invoke(service, 0.1, 0.2));
        assertEquals(StopWindowState.NOT_ACCURATE, deriveMethod.invoke(service, -2.0, 1.0));
    }
}
