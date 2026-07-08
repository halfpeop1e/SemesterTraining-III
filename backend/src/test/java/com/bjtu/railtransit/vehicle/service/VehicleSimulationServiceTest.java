package com.bjtu.railtransit.vehicle.service;

import com.bjtu.railtransit.vehicle.dto.SimulationResult;
import com.bjtu.railtransit.vehicle.dto.StopResult;
import com.bjtu.railtransit.vehicle.dto.TrainState;
import com.bjtu.railtransit.vehicle.enums.SimulationPhase;
import com.bjtu.railtransit.vehicle.enums.StopWindowState;
import com.bjtu.railtransit.vehicle.model.LineProfile;
import com.bjtu.railtransit.vehicle.model.ScenarioConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 车辆仿真核心逻辑单元测试（阶段1~3B 原有测试保留，阶段4B 线路数据真实化补充测试）。
 *
 * <p>阶段4B 新增测试覆盖：
 * <ul>
 *   <li>默认请求（1→2）仍能跑通，targetStopPosition ≈ 1348m。</li>
 *   <li>LineProfileJsonLoader 验证：listStations 站点数量、buildLineProfile 正确计算。</li>
 *   <li>非法站点 id 报错清晰。</li>
 *   <li>toStationId &le; fromStationId 报错清晰。</li>
 *   <li>summary.dtPerFrame 仍为 0.5。</li>
 *   <li>STOP_POSITION_TOLERANCE 仍为 0.5，STOP_VELOCITY_TOLERANCE 仍为 0.1。</li>
 * </ul>
 * </p>
 */
class VehicleSimulationServiceTest {

    private final DemoScenarioProvider demoScenarioProvider = new DemoScenarioProvider();
    private final VehicleSimulationService service = new VehicleSimulationService(demoScenarioProvider);
    private final LineProfileJsonLoader loader = new LineProfileJsonLoader();

    @Test
    void statesAreNonEmptyAndGeneratedByLoop() {
        SimulationResult result = service.runDemoSimulation();

        assertNotNull(result.getStates());
        assertFalse(result.getStates().isEmpty(), "states 不能为空，必须由循环计算生成");
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

        assertEquals(1200.0, result.getStopResult().getTargetStopPosition());
        assertNotNull(result.getStopResult());

        assertTrue(result.getSafetyEvents().isEmpty());
    }

    @Test
    void allStatesBelongToTrainT1() {
        SimulationResult result = service.runDemoSimulation();
        for (TrainState state : result.getStates()) {
            assertEquals("T1", state.getTrainId());
        }
    }

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

    @Test
    void predictedStopPositionIsConsistentWithActualStopPosition() {
        SimulationResult result = service.runDemoSimulation();
        StopResult stopResult = result.getStopResult();

        double predicted = stopResult.getPredictedStopPosition();
        double actual = stopResult.getActualStopPosition();

        assertTrue(predicted > 0.0, "predictedStopPosition 应为正值，实际为 " + predicted);
        assertTrue(actual > 0.0, "actualStopPosition 应为正值，实际为 " + actual);
        double predictionError = Math.abs(predicted - actual);
        assertTrue(predictionError <= 1.0,
                "predictedStopPosition 与 actualStopPosition 的差异应不超过 1.0m，"
                        + "实际差异=" + predictionError + "m，"
                        + "predicted=" + predicted + "，actual=" + actual);
    }

    @Test
    void velocityThresholdsAreSeparateAndUnchanged() throws Exception {
        java.lang.reflect.Field velocityEpsilonField = VehicleSimulationService.class
                .getDeclaredField("VELOCITY_EPSILON");
        velocityEpsilonField.setAccessible(true);
        double velocityEpsilon = velocityEpsilonField.getDouble(null);
        assertEquals(1.0e-3, velocityEpsilon, 1.0e-12, "VELOCITY_EPSILON 应为 1.0e-3");

        java.lang.reflect.Field stopVelocityField = VehicleSimulationService.class
                .getDeclaredField("STOP_VELOCITY_TOLERANCE");
        stopVelocityField.setAccessible(true);
        double stopVelocity = stopVelocityField.getDouble(null);
        assertEquals(0.1, stopVelocity, 1.0e-12, "STOP_VELOCITY_TOLERANCE 应为 0.1");

        java.lang.reflect.Field stopPositionField = VehicleSimulationService.class
                .getDeclaredField("STOP_POSITION_TOLERANCE");
        stopPositionField.setAccessible(true);
        double stopPosition = stopPositionField.getDouble(null);
        assertEquals(0.5, stopPosition, 1.0e-12, "STOP_POSITION_TOLERANCE 应为 0.5");
    }

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

    @Test
    void gradeResistanceActuallyAffectsAcceleration() {
        SimulationResult withGrade = service.runDemoSimulation();
        SimulationResult withoutGrade = service.run(new DemoScenarioProvider().getDemoScenarioWithoutGrade());

        double withGradeTotalTime = withGrade.getSummary().getTotalTime();
        double withoutGradeTotalTime = withoutGrade.getSummary().getTotalTime();

        assertTrue(withGradeTotalTime > withoutGradeTotalTime,
                "含 3‰ 上坡坡度段的场景总运行时间应比不含坡度场景更长，"
                        + "实际 withGrade=" + withGradeTotalTime + " withoutGrade=" + withoutGradeTotalTime);
    }

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

    @Test
    void summarySpeedLimitMatchesLineProfile() {
        SimulationResult result = service.runDemoSimulation();
        assertEquals(20.0, result.getSummary().getSpeedLimit(), 1.0e-9,
                "summary.speedLimit 应等于 DemoScenarioProvider 中配置的线路限速");
    }

    @Test
    void summaryDtPerFrameMatchesScenarioDt() {
        SimulationResult result = service.runDemoSimulation();
        assertEquals(0.5, result.getSummary().getDtPerFrame(), 1.0e-9,
                "summary.dtPerFrame 应等于 DemoScenarioProvider 中配置的 dt=0.5s");
    }

    @Test
    void stopWindowStateMatchesSuccessDerivationForDemoScenario() {
        SimulationResult result = service.runDemoSimulation();
        StopResult stopResult = result.getStopResult();

        boolean isInWindow = stopResult.getStopWindowState() == StopWindowState.IN_WINDOW;
        assertEquals(stopResult.isSuccess(), isInWindow,
                "success 应与 stopWindowState==IN_WINDOW 保持等价，success=" + stopResult.isSuccess()
                        + " stopWindowState=" + stopResult.getStopWindowState()
                        + " stopError=" + stopResult.getStopError());
        assertEquals(StopWindowState.IN_WINDOW, stopResult.getStopWindowState(),
                "阶段3B制动响应时间补偿后，演示配置下停站误差应在窗内，应判定为 IN_WINDOW，"
                        + "实际 stopError=" + stopResult.getStopError());
    }

    @Test
    void stopWindowStateDerivationRulesAreConsistent() throws Exception {
        java.lang.reflect.Method deriveMethod = VehicleSimulationService.class
                .getDeclaredMethod("deriveStopWindowState", double.class, double.class);
        deriveMethod.setAccessible(true);

        assertEquals(StopWindowState.IN_WINDOW, deriveMethod.invoke(service, 0.3, 0.05));
        assertEquals(StopWindowState.IN_WINDOW, deriveMethod.invoke(service, -0.5, 0.1));
        assertEquals(StopWindowState.OVERSHOOT, deriveMethod.invoke(service, 0.8, 0.05));
        assertEquals(StopWindowState.UNDERSHOOT, deriveMethod.invoke(service, -0.8, 0.05));
        assertEquals(StopWindowState.NOT_ACCURATE, deriveMethod.invoke(service, 0.1, 0.2));
        assertEquals(StopWindowState.NOT_ACCURATE, deriveMethod.invoke(service, -2.0, 1.0));
    }

    // ---- 阶段4B 新增测试 ----

    /**
     * 阶段4B：listStations 应返回 13 个站点（北京地铁9号线 configs/line-profile.json）。
     */
    @Test
    void lineProfileJsonLoaderListsThirteenStations() {
        List<LineProfileJsonLoader.StationEntry> stations = loader.listStations();
        assertEquals(13, stations.size(), "configs/line-profile.json 应包含 13 个站点");
        // 首站应为郭公庄
        assertEquals("郭公庄", stations.get(0).name, "第一个站应为郭公庄");
        // 末站应为国家图书馆
        assertEquals("国家图书馆", stations.get(12).name, "最后一个站应为国家图书馆");
    }

    /**
     * 阶段4B：默认区间 1→2（郭公庄→丰台科技园）的 targetStopPosition 应约等于
     * (1.661 - 0.313) * 1000 = 1348m。
     */
    @Test
    void defaultStation1To2TargetStopPositionApprox1348m() {
        LineProfile lineProfile = loader.buildLineProfile(1, 2);
        ScenarioConfig scenario = demoScenarioProvider.buildScenario(lineProfile);
        SimulationResult result = service.run(scenario);

        double target = result.getStopResult().getTargetStopPosition();
        // (1.661 - 0.313) * 1000 = 1348.0m
        assertEquals(1348.0, target, 1.0,
                "1→2 区间 targetStopPosition 应约等于 1348m，实际=" + target);
    }

    /**
     * 阶段4B：非法站点 id 应抛出 IllegalArgumentException 并包含 id 值。
     */
    @Test
    void invalidStationIdThrowsWithClearMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> loader.buildLineProfile(1, 99));
        assertTrue(ex.getMessage().contains("99"),
                "异常消息应包含非法 id=99，实际消息：" + ex.getMessage());
    }

    /**
     * 阶段4B：toStationId <= fromStationId 应抛出 IllegalArgumentException。
     */
    @Test
    void reversedStationIdsThrowWithClearMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> loader.buildLineProfile(3, 2));
        assertTrue(ex.getMessage().contains("大于") || ex.getMessage().contains("toStationId"),
                "异常消息应说明 toStationId 必须大于 fromStationId，实际消息：" + ex.getMessage());
    }

    /**
     * 阶段4B：toStationId == fromStationId 同样应抛出 IllegalArgumentException。
     */
    @Test
    void sameStationIdsThrowError() {
        assertThrows(IllegalArgumentException.class,
                () -> loader.buildLineProfile(2, 2));
    }

    /**
     * 阶段4B：summary.dtPerFrame 在真实区间仿真中仍应为 0.5s（不因区间变化）。
     */
    @Test
    void dtPerFrameRemainsHalfSecondForRealSectionSimulation() {
        LineProfile lineProfile = loader.buildLineProfile(1, 2);
        ScenarioConfig scenario = demoScenarioProvider.buildScenario(lineProfile);
        SimulationResult result = service.run(scenario);

        assertEquals(0.5, result.getSummary().getDtPerFrame(), 1.0e-9,
                "summary.dtPerFrame 在真实区间仿真中应仍为 0.5s");
    }

    /**
     * 阶段4B：跨多站区间（2→5）应能正常完成仿真，位置单调不减，末态停车。
     */
    @Test
    void multiStationGapSimulationRunsSuccessfully() {
        LineProfile lineProfile = loader.buildLineProfile(2, 5);
        ScenarioConfig scenario = demoScenarioProvider.buildScenario(lineProfile);
        SimulationResult result = service.run(scenario);

        assertNotNull(result.getStates());
        assertFalse(result.getStates().isEmpty());

        TrainState last = result.getStates().get(result.getStates().size() - 1);
        assertEquals(SimulationPhase.STOPPED, last.getPhase(),
                "多站区间仿真末态应为 STOPPED");

        // 位置单调不减
        double prev = 0.0;
        for (TrainState s : result.getStates()) {
            assertTrue(s.getPosition() >= prev - 1e-9, "位置应单调不减");
            prev = s.getPosition();
        }
    }

    // ---- 辅助方法 ----

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
}
