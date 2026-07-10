package com.bjtu.railtransit.vehicle.service;

import com.bjtu.railtransit.vehicle.dto.ControlCommand;
import com.bjtu.railtransit.vehicle.dto.SimulationControlRequest;
import com.bjtu.railtransit.vehicle.dto.SimulationResult;
import com.bjtu.railtransit.vehicle.dto.StopResult;
import com.bjtu.railtransit.vehicle.dto.TrainState;
import com.bjtu.railtransit.vehicle.enums.DrivingMode;
import com.bjtu.railtransit.vehicle.enums.SimulationPhase;
import com.bjtu.railtransit.vehicle.enums.StopWindowState;
import com.bjtu.railtransit.vehicle.model.LineProfile;
import com.bjtu.railtransit.vehicle.model.ScenarioConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    // ========== 本轮新增测试：多站连续仿真 + 驾驶员控制 ==========

    /** 多站 1→4：position 单调不减且不在中间站归零。 */
    @org.junit.jupiter.api.Test
    void multiStation_positionContinuousNeverResets() {
        List<LineProfileJsonLoader.StationEntry> stations = loader.listStations();
        List<LineProfileJsonLoader.StationEntry> seg = new java.util.ArrayList<>();
        for (LineProfileJsonLoader.StationEntry s : stations) {
            if (s.id >= 1 && s.id <= 4) seg.add(s);
        }
        seg.sort((a, b) -> Integer.compare(a.id, b.id));

        SimulationResult result = service.runMultiStation(seg, 30.0, demoScenarioProvider);

        List<TrainState> states = result.getStates();
        assertFalse(states.isEmpty());

        double prevPos = -1;
        for (TrainState st : states) {
            assertTrue(st.getPosition() >= prevPos - 1e-9,
                    "position 应连续不减，出现回跳: prev=" + prevPos + " cur=" + st.getPosition());
            prevPos = st.getPosition();
        }
        // 从未归零检查：末态 position 应约等于 1→4 总距离
        double totalDist = 0;
        for (int i = 0; i < seg.size() - 1; i++) {
            totalDist += (seg.get(i + 1).km - seg.get(i).km) * 1000.0;
        }
        TrainState last = states.get(states.size() - 1);
        assertTrue(last.getPosition() >= totalDist * 0.9,
                "末态 position 应接近总距离 " + totalDist + "m，实际=" + last.getPosition());
    }

    /** 多站 1→4：absolutePosition 单调不减。 */
    @org.junit.jupiter.api.Test
    void multiStation_absolutePositionContinuous() {
        List<LineProfileJsonLoader.StationEntry> stations = loader.listStations();
        List<LineProfileJsonLoader.StationEntry> seg = new java.util.ArrayList<>();
        for (LineProfileJsonLoader.StationEntry s : stations) {
            if (s.id >= 1 && s.id <= 4) seg.add(s);
        }
        seg.sort((a, b) -> Integer.compare(a.id, b.id));
        SimulationResult result = service.runMultiStation(seg, 30.0, demoScenarioProvider);

        Double prevAbs = null;
        for (TrainState st : result.getStates()) {
            Double abs = st.getAbsolutePosition();
            assertNotNull(abs, "absolutePosition 不应为 null");
            if (prevAbs != null) {
                assertTrue(abs >= prevAbs - 1e-9,
                        "absolutePosition 应单调不减: prev=" + prevAbs + " cur=" + abs);
            }
            prevAbs = abs;
        }
    }

    /** 多站 1→4：stopResult.targetStopPosition 等于 1→4 总距离。 */
    @org.junit.jupiter.api.Test
    void multiStation_finalTargetStopPositionEqualsTotalDistance() {
        List<LineProfileJsonLoader.StationEntry> stations = loader.listStations();
        List<LineProfileJsonLoader.StationEntry> seg = new java.util.ArrayList<>();
        for (LineProfileJsonLoader.StationEntry s : stations) {
            if (s.id >= 1 && s.id <= 4) seg.add(s);
        }
        seg.sort((a, b) -> Integer.compare(a.id, b.id));

        double totalDist = 0;
        for (int i = 0; i < seg.size() - 1; i++) {
            totalDist += (seg.get(i + 1).km - seg.get(i).km) * 1000.0;
        }

        SimulationResult result = service.runMultiStation(seg, 30.0, demoScenarioProvider);
        double targetStop = result.getStopResult().getTargetStopPosition();
        assertEquals(totalDist, targetStop, 1.0,
                "targetStopPosition 应等于 1→4 总距离 " + totalDist + "m，实际=" + targetStop);
    }

    /** 多站 1→4：中间站有 DWELL 帧，velocity=0，position 不变。 */
    @org.junit.jupiter.api.Test
    void multiStation_dwellFramesExistAndPositionUnchanged() {
        List<LineProfileJsonLoader.StationEntry> stations = loader.listStations();
        List<LineProfileJsonLoader.StationEntry> seg = new java.util.ArrayList<>();
        for (LineProfileJsonLoader.StationEntry s : stations) {
            if (s.id >= 1 && s.id <= 3) seg.add(s);
        }
        seg.sort((a, b) -> Integer.compare(a.id, b.id));

        SimulationResult result = service.runMultiStation(seg, 30.0, demoScenarioProvider);

        List<TrainState> dwellFrames = new java.util.ArrayList<>();
        for (TrainState st : result.getStates()) {
            if (st.getPhase() == SimulationPhase.DWELL) {
                dwellFrames.add(st);
            }
        }
        // 30s / 0.5s = 60 帧（±2 帧误差）
        assertTrue(dwellFrames.size() >= 58 && dwellFrames.size() <= 62,
                "30s 驻留应约 60 帧 DWELL，实际=" + dwellFrames.size());

        // 检查 DWELL 帧 velocity=0 且相邻帧 position 不变
        double firstDwellPos = dwellFrames.get(0).getPosition();
        for (TrainState st : dwellFrames) {
            assertEquals(0.0, st.getVelocity(), 1e-9, "DWELL 帧速度应为 0");
            assertEquals(0.0, st.getAcceleration(), 1e-9, "DWELL 帧加速度应为 0");
            assertEquals(firstDwellPos, st.getPosition(), 1e-9, "DWELL 期间 position 不变");
        }
    }

    /** 控制续算：ATO→MANUAL 模式切换不改变轨迹（返回 ATO 自动策略续算）。 */
    @org.junit.jupiter.api.Test
    void control_atoToManualModeSwitch_doesNotChangeBrakeDecel() {
        LineProfile line = loader.buildLineProfile(1, 2);
        ScenarioConfig scenario = demoScenarioProvider.buildScenario(line);
        SimulationResult full = service.run(scenario);
        TrainState midState = full.getStates().get(full.getStates().size() / 3);
        // 给 midState 设置 absolutePosition 以匹配新约定
        midState.setAbsolutePosition(313.0 + midState.getPosition());

        // ATO→MANUAL 切换：coast 指令，不触发制动
        com.bjtu.railtransit.vehicle.dto.SimulationControlRequest req =
                new com.bjtu.railtransit.vehicle.dto.SimulationControlRequest();
        req.setFromStationId(1);
        req.setToStationId(2);
        req.setCurrentState(midState);
        req.setCurrentMode(com.bjtu.railtransit.vehicle.enums.DrivingMode.ATO);
        req.setControlCommand(new com.bjtu.railtransit.vehicle.dto.ControlCommand("coast", 0));
        req.setTotalTargetPosition(line.getTargetStopPosition()); // 1348m

        SimulationResult result = service.runContinuation(req, scenario);
        // 续算结果应末态为 STOPPED，模式为 ATO（coast 不切换模式）
        assertEquals(SimulationPhase.STOPPED,
                result.getStates().get(result.getStates().size() - 1).getPhase());
    }

    /** 控制续算：MANUAL brake 指令使后续速度下降，出现 BRAKING。 */
    @org.junit.jupiter.api.Test
    void control_manualBrake_reducesVelocity() {
        LineProfile line = loader.buildLineProfile(1, 2);
        ScenarioConfig scenario = demoScenarioProvider.buildScenario(line);
        SimulationResult full = service.run(scenario);
        TrainState midState = full.getStates().get(full.getStates().size() / 3);
        midState.setAbsolutePosition(313.0 + midState.getPosition());

        com.bjtu.railtransit.vehicle.dto.SimulationControlRequest req =
                new com.bjtu.railtransit.vehicle.dto.SimulationControlRequest();
        req.setFromStationId(1);
        req.setToStationId(2);
        req.setCurrentState(midState);
        req.setCurrentMode(com.bjtu.railtransit.vehicle.enums.DrivingMode.MANUAL);
        req.setControlCommand(new com.bjtu.railtransit.vehicle.dto.ControlCommand("brake", 1.0));
        req.setTotalTargetPosition(line.getTargetStopPosition());

        SimulationResult result = service.runContinuation(req, scenario);

        boolean brakingSeen = result.getStates().stream()
                .anyMatch(s -> s.getPhase() == SimulationPhase.BRAKING);
        assertTrue(brakingSeen, "MANUAL brake 续算应出现 BRAKING 阶段");
        assertEquals(SimulationPhase.STOPPED,
                result.getStates().get(result.getStates().size() - 1).getPhase());
    }

    /** 控制续算：EB 中断多站任务，返回的 states 不继续到多站终点。 */
    @org.junit.jupiter.api.Test
    void control_eb_interruptsMultiStationTask() {
        LineProfile line = loader.buildLineProfile(1, 2);
        ScenarioConfig scenario = demoScenarioProvider.buildScenario(line);
        SimulationResult full = service.run(scenario);
        TrainState midState = full.getStates().get(full.getStates().size() / 2);
        midState.setAbsolutePosition(313.0 + midState.getPosition());

        // 假设多站仿真的总目标为 5000m（远超 1→2 区间），EB 应在远未到达时停车
        com.bjtu.railtransit.vehicle.dto.SimulationControlRequest req =
                new com.bjtu.railtransit.vehicle.dto.SimulationControlRequest();
        req.setFromStationId(1);
        req.setToStationId(2);
        req.setCurrentState(midState);
        req.setCurrentMode(com.bjtu.railtransit.vehicle.enums.DrivingMode.ATO);
        req.setControlCommand(new com.bjtu.railtransit.vehicle.dto.ControlCommand("emergency_brake", 0));
        req.setTotalTargetPosition(5000.0);

        SimulationResult result = service.runContinuation(req, scenario);

        // EB 返回的 states 末态应为 STOPPED
        assertEquals(SimulationPhase.STOPPED,
                result.getStates().get(result.getStates().size() - 1).getPhase());
        // 实际停车位置远未到达 5000m
        double finalPos = result.getStates().get(result.getStates().size() - 1).getPosition();
        assertTrue(finalPos < 5000.0 * 0.5,
                "EB 续算停车位置应远小于 5000m，实际=" + finalPos);
        // stationStops 应为空（EB 中断了多站任务）
        assertTrue(result.getStationStops().isEmpty(), "EB 应清空 stationStops");
        // SafetyEvent 不为空
        assertFalse(result.getSafetyEvents().isEmpty(), "EB 应生成 SafetyEvent");
        assertEquals("DRIVER_EMERGENCY_BRAKE", result.getSafetyEvents().get(0).getReason());
    }

    /** 控制续算：EB 生成 SafetyEvent，reason=DRIVER_EMERGENCY_BRAKE。 */
    @org.junit.jupiter.api.Test
    void control_eb_generatesSafetyEvent() {
        LineProfile line = loader.buildLineProfile(1, 2);
        ScenarioConfig scenario = demoScenarioProvider.buildScenario(line);
        SimulationResult full = service.run(scenario);
        TrainState midState = full.getStates().get(full.getStates().size() / 3);
        midState.setAbsolutePosition(313.0 + midState.getPosition());

        com.bjtu.railtransit.vehicle.dto.SimulationControlRequest req =
                new com.bjtu.railtransit.vehicle.dto.SimulationControlRequest();
        req.setFromStationId(1);
        req.setToStationId(2);
        req.setCurrentState(midState);
        req.setCurrentMode(com.bjtu.railtransit.vehicle.enums.DrivingMode.MANUAL);
        req.setControlCommand(new com.bjtu.railtransit.vehicle.dto.ControlCommand("emergency_brake", 0));
        req.setTotalTargetPosition(line.getTargetStopPosition());

        SimulationResult result = service.runContinuation(req, scenario);
        assertFalse(result.getSafetyEvents().isEmpty(), "EB 应生成至少一条 SafetyEvent");
        assertEquals("DRIVER_EMERGENCY_BRAKE", result.getSafetyEvents().get(0).getReason());
        assertEquals("emergency_brake", result.getSafetyEvents().get(0).getAction());
        assertEquals(com.bjtu.railtransit.vehicle.enums.DrivingMode.EMERGENCY,
                result.getSummary().getCurrentMode());
    }

    /** 超速场景：SafetyGuard 能生成 OVERSPEED SafetyEvent（用小目标强制高速续算）。 */
    @org.junit.jupiter.api.Test
    void safetyGuard_overspeed_generatesSafetyEvent() {
        // 构造一个限速极低的 LineProfile（限速 1 m/s），让列车立即超速
        com.bjtu.railtransit.vehicle.model.LineProfile lowLimitLine =
                new com.bjtu.railtransit.vehicle.model.LineProfile(0.0, 5000.0, 1.0);
        ScenarioConfig scenario = demoScenarioProvider.buildScenario(lowLimitLine);

        // 构造一个已达 18 m/s 的 midState（远超 1 m/s 限速）
        TrainState overspeedState = new TrainState(40.0, 300.0, 18.0, 0.0,
                SimulationPhase.COAST, "T1");
        overspeedState.setAbsolutePosition(313.0 + 300.0);

        com.bjtu.railtransit.vehicle.dto.SimulationControlRequest req =
                new com.bjtu.railtransit.vehicle.dto.SimulationControlRequest();
        req.setFromStationId(1);
        req.setToStationId(2);
        req.setCurrentState(overspeedState);
        req.setCurrentMode(com.bjtu.railtransit.vehicle.enums.DrivingMode.ATO);
        req.setControlCommand(new com.bjtu.railtransit.vehicle.dto.ControlCommand("coast", 0));
        req.setTotalTargetPosition(5000.0);

        SimulationResult result = service.runContinuation(req, scenario);
        // 超速应触发 SafetyGuard，生成 OVERSPEED 事件
        boolean hasOverspeed = result.getSafetyEvents().stream()
                .anyMatch(e -> "OVERSPEED".equals(e.getReason()));
        assertTrue(hasOverspeed, "超速场景应生成 OVERSPEED SafetyEvent");
    }

    /** dtPerFrame 仍为 0.5s，停车阈值不变。 */
    @org.junit.jupiter.api.Test
    void constants_dtPerFrameAndThresholdsUnchanged() throws Exception {
        SimulationResult result = service.runDemoSimulation();
        assertEquals(0.5, result.getSummary().getDtPerFrame(), 1e-9);

        java.lang.reflect.Field f1 = VehicleSimulationService.class.getDeclaredField("STOP_POSITION_TOLERANCE");
        f1.setAccessible(true);
        assertEquals(0.5, f1.getDouble(null), 1e-12);

        java.lang.reflect.Field f2 = VehicleSimulationService.class.getDeclaredField("STOP_VELOCITY_TOLERANCE");
        f2.setAccessible(true);
        assertEquals(0.1, f2.getDouble(null), 1e-12);
    }

    // ========== 本轮新增测试：完整司机手柄输入（牵引/惰行/制动级位） ==========

    private SimulationControlRequest buildManualControlRequest(
            TrainState currentState, String command, double targetDecel, double levelPercent,
            double totalTarget) {
        SimulationControlRequest req = new SimulationControlRequest();
        req.setFromStationId(1);
        req.setToStationId(2);
        req.setCurrentState(currentState);
        req.setCurrentMode(DrivingMode.MANUAL);
        req.setControlCommand(new ControlCommand(command, targetDecel, levelPercent));
        req.setTotalTargetPosition(totalTarget);
        return req;
    }

    private TrainState makeMovingState(double time, double position, double velocity, String phase) {
        TrainState s = new TrainState(time, position, velocity, 0.0,
                SimulationPhase.valueOf(phase.toUpperCase()), "T1");
        s.setAbsolutePosition(313.0 + position);
        return s;
    }

    @Test
    void control_manualTractionLevel7_producesTractionFramesAndVelocityIncreases() {
        LineProfile line = loader.buildLineProfile(1, 2);
        ScenarioConfig scenario = demoScenarioProvider.buildScenario(line);

        TrainState midState = makeMovingState(30.0, 300.0, 5.0, "coast");
        SimulationControlRequest req = buildManualControlRequest(
                midState, "traction", 0.0, 100.0, line.getTargetStopPosition());

        SimulationResult result = service.runContinuation(req, scenario);

        List<TrainState> states = result.getStates();
        assertFalse(states.isEmpty(), "续算结果 states 不能为空");

        boolean hasTractionFrame = false;
        for (int i = 0; i < Math.min(10, states.size()); i++) {
            if (states.get(i).getPhase() == SimulationPhase.TRACTION) {
                hasTractionFrame = true;
                assertTrue(states.get(i).getAcceleration() > 0.0,
                        "TRACTION 帧加速度应为正，实际=" + states.get(i).getAcceleration());
                break;
            }
        }
        assertTrue(hasTractionFrame, "MANUAL traction level 7 应产生 TRACTION 帧");

        TrainState earlyState = states.get(Math.min(5, states.size() - 1));
        assertTrue(earlyState.getVelocity() > 5.0,
                "牵引后速度应上升（初始 5 m/s），第5帧速度=" + earlyState.getVelocity());

        assertEquals(SimulationPhase.STOPPED, states.get(states.size() - 1).getPhase(),
                "末态应为 STOPPED");
    }

    @Test
    void control_manualTractionLevel7_accelerationGreaterThanLevel3() {
        LineProfile line = loader.buildLineProfile(1, 2);
        ScenarioConfig scenario = demoScenarioProvider.buildScenario(line);

        TrainState state7 = makeMovingState(30.0, 300.0, 8.0, "coast");
        SimulationControlRequest req7 = buildManualControlRequest(
                state7, "traction", 0.0, 100.0, line.getTargetStopPosition());
        SimulationResult result7 = service.runContinuation(req7, scenario);

        TrainState state3 = makeMovingState(30.0, 300.0, 8.0, "coast");
        SimulationControlRequest req3 = buildManualControlRequest(
                state3, "traction", 0.0, 3.0 / 7.0 * 100.0, line.getTargetStopPosition());
        SimulationResult result3 = service.runContinuation(req3, scenario);

        TrainState firstTraction7 = null;
        TrainState firstTraction3 = null;
        for (TrainState s : result7.getStates()) {
            if (s.getPhase() == SimulationPhase.TRACTION) { firstTraction7 = s; break; }
        }
        for (TrainState s : result3.getStates()) {
            if (s.getPhase() == SimulationPhase.TRACTION) { firstTraction3 = s; break; }
        }

        assertNotNull(firstTraction7, "level 7 应产生 TRACTION 帧");
        assertNotNull(firstTraction3, "level 3 应产生 TRACTION 帧");
        assertTrue(firstTraction7.getAcceleration() > firstTraction3.getAcceleration(),
                "level 7 加速度 (" + firstTraction7.getAcceleration()
                + ") 应大于 level 3 加速度 (" + firstTraction3.getAcceleration() + ")");
    }

    @Test
    void control_manualCoast_noActiveTraction_velocityDoesNotIncreaseFromTraction() {
        LineProfile line = loader.buildLineProfile(1, 2);
        ScenarioConfig scenario = demoScenarioProvider.buildScenario(line);

        TrainState midState = makeMovingState(30.0, 300.0, 10.0, "coast");
        SimulationControlRequest req = buildManualControlRequest(
                midState, "coast", 0.0, 0.0, line.getTargetStopPosition());

        SimulationResult result = service.runContinuation(req, scenario);
        List<TrainState> states = result.getStates();
        assertFalse(states.isEmpty());

        for (int i = 0; i < Math.min(20, states.size() - 1); i++) {
            TrainState s = states.get(i);
            if (s.getPhase() == SimulationPhase.TRACTION) {
                assertTrue(s.getAcceleration() <= 0.05,
                        "COAST 指令下不应有明显正牵引加速度，frame " + i
                        + " accel=" + s.getAcceleration());
            }
        }

        TrainState earlyState = states.get(Math.min(5, states.size() - 1));
        assertTrue(earlyState.getVelocity() <= 10.0 + 0.1,
                "COAST 下速度不应因牵引上升（初始 10 m/s），第5帧 v=" + earlyState.getVelocity());

        assertEquals(SimulationPhase.STOPPED, states.get(states.size() - 1).getPhase());
    }

    @Test
    void control_manualBrakeLevel0_equalsCoast_noBrakingTriggered() {
        LineProfile line = loader.buildLineProfile(1, 2);
        ScenarioConfig scenario = demoScenarioProvider.buildScenario(line);

        TrainState midState = makeMovingState(30.0, 300.0, 12.0, "coast");

        SimulationControlRequest reqBrake0 = buildManualControlRequest(
                midState, "brake", 0.0, 0.0, line.getTargetStopPosition());
        SimulationResult resultBrake0 = service.runContinuation(reqBrake0, scenario);

        SimulationControlRequest reqCoast = buildManualControlRequest(
                makeMovingState(30.0, 300.0, 12.0, "coast"),
                "coast", 0.0, 0.0, line.getTargetStopPosition());
        SimulationResult resultCoast = service.runContinuation(reqCoast, scenario);

        TrainState firstStateB0 = resultBrake0.getStates().get(0);
        TrainState firstStateCoast = resultCoast.getStates().get(0);

        assertEquals(SimulationPhase.COAST, firstStateB0.getPhase(),
                "brake level 0 第一帧应为 COAST（不触发制动）");
        assertEquals(firstStateCoast.getAcceleration(), firstStateB0.getAcceleration(), 0.01,
                "brake level 0 加速度应与 coast 一致");

        boolean immediateBraking = false;
        for (int i = 0; i < Math.min(5, resultBrake0.getStates().size()); i++) {
            if (resultBrake0.getStates().get(i).getPhase() == SimulationPhase.BRAKING) {
                immediateBraking = true;
                break;
            }
        }
        assertFalse(immediateBraking, "brake level 0 不应立即触发 BRAKING（应等价惰行）");
    }

    @Test
    void control_atoMode_manualTractionCommand_ignoredAndKeepsAto() {
        LineProfile line = loader.buildLineProfile(1, 2);
        ScenarioConfig scenario = demoScenarioProvider.buildScenario(line);
        SimulationResult full = service.run(scenario);
        TrainState midState = full.getStates().get(full.getStates().size() / 3);
        midState.setAbsolutePosition(313.0 + midState.getPosition());

        SimulationControlRequest req = new SimulationControlRequest();
        req.setFromStationId(1);
        req.setToStationId(2);
        req.setCurrentState(midState);
        req.setCurrentMode(DrivingMode.ATO);
        req.setControlCommand(new ControlCommand("traction", 0.0, 100.0));
        req.setTotalTargetPosition(line.getTargetStopPosition());

        SimulationResult result = service.runContinuation(req, scenario);
        assertEquals(DrivingMode.ATO, result.getSummary().getCurrentMode(),
                "ATO 模式下人工牵引指令应被拒绝，保持 ATO 模式");
    }

    @Test
    void control_manualEmergencyBrake_entersEmergencyMode() {
        LineProfile line = loader.buildLineProfile(1, 2);
        ScenarioConfig scenario = demoScenarioProvider.buildScenario(line);
        TrainState midState = makeMovingState(30.0, 300.0, 15.0, "traction");

        SimulationControlRequest req = new SimulationControlRequest();
        req.setFromStationId(1);
        req.setToStationId(2);
        req.setCurrentState(midState);
        req.setCurrentMode(DrivingMode.MANUAL);
        req.setControlCommand(new ControlCommand("emergency_brake", 0.0, 0.0));
        req.setTotalTargetPosition(line.getTargetStopPosition());

        SimulationResult result = service.runContinuation(req, scenario);
        assertEquals(DrivingMode.EMERGENCY, result.getSummary().getCurrentMode(),
                "EB 后模式应为 EMERGENCY");
        assertFalse(result.getSafetyEvents().isEmpty(), "EB 应生成 SafetyEvent");

        TrainState last = result.getStates().get(result.getStates().size() - 1);
        assertEquals(SimulationPhase.STOPPED, last.getPhase());
        assertEquals(DrivingMode.MANUAL, result.getSummary().getNextMode(),
                "EB 停稳后 nextMode 应为 MANUAL");
    }

    // ========== 本轮新增测试：resume_ato / reset_emergency 指令 ==========

    /**
     * MANUAL brake 停稳后，取 stopped state 作为 currentState，再发 traction level 5：
     * 前几帧应出现 TRACTION，速度从 0 上升（>0）。
     */
    @Test
    void manual_brakeStopThenTraction_restartsFromZero() {
        LineProfile line = loader.buildLineProfile(1, 2);
        ScenarioConfig scenario = demoScenarioProvider.buildScenario(line);

        // 先用 MANUAL brake 把列车制动到停稳
        TrainState movingState = makeMovingState(30.0, 300.0, 12.0, "traction");
        SimulationControlRequest brakeReq = buildManualControlRequest(
                movingState, "brake", 1.2, 100.0, line.getTargetStopPosition());
        SimulationResult brakeResult = service.runContinuation(brakeReq, scenario);
        TrainState stoppedState = brakeResult.getStates().get(brakeResult.getStates().size() - 1);
        assertEquals(SimulationPhase.STOPPED, stoppedState.getPhase(),
                "制动续算末态应为 STOPPED");
        assertEquals(0.0, stoppedState.getVelocity(), 1.0e-9,
                "制动续算末态速度应为 0");

        // 用 stoppedState 作为 currentState，再发 MANUAL traction level 5（levelPercent=5/7*100）
        SimulationControlRequest tractionReq = buildManualControlRequest(
                stoppedState, "traction", 0.0, 5.0 / 7.0 * 100.0, line.getTargetStopPosition());
        SimulationResult result = service.runContinuation(tractionReq, scenario);

        List<TrainState> states = result.getStates();
        assertFalse(states.isEmpty(), "停稳后再牵引续算 states 不能为空");

        boolean hasTraction = false;
        for (int i = 0; i < Math.min(10, states.size()); i++) {
            if (states.get(i).getPhase() == SimulationPhase.TRACTION) {
                hasTraction = true;
                break;
            }
        }
        assertTrue(hasTraction, "停稳后再牵引应出现 TRACTION 帧");

        boolean velocityIncreased = false;
        for (int i = 0; i < Math.min(10, states.size()); i++) {
            if (states.get(i).getVelocity() > 0.0) {
                velocityIncreased = true;
                break;
            }
        }
        assertTrue(velocityIncreased, "停稳后再牵引速度应从 0 上升（>0）");
    }

    /**
     * 若 stopped state 已在 totalTarget 附近（localTarget<=0），再发 traction 不应冲过终点：
     * 最终 STOPPED，position 不超过 totalTarget + 容差。
     */
    @Test
    void manual_brakeStopNearTarget_tractionRespectsSafety() {
        LineProfile line = loader.buildLineProfile(1, 2);
        ScenarioConfig scenario = demoScenarioProvider.buildScenario(line);
        double totalTarget = line.getTargetStopPosition();

        // 构造已停在终点处的 state（localTarget = totalTarget - position = 0 <= 0）
        TrainState stoppedAtTarget = new TrainState(100.0, totalTarget, 0.0, 0.0,
                SimulationPhase.STOPPED, "T1");
        stoppedAtTarget.setAbsolutePosition(313.0 + totalTarget);

        SimulationControlRequest req = buildManualControlRequest(
                stoppedAtTarget, "traction", 0.0, 5.0 / 7.0 * 100.0, totalTarget);
        SimulationResult result = service.runContinuation(req, scenario);

        List<TrainState> states = result.getStates();
        assertFalse(states.isEmpty());
        TrainState last = states.get(states.size() - 1);
        assertEquals(SimulationPhase.STOPPED, last.getPhase(),
                "终点附近再牵引应被安全停车逻辑拦下，最终 STOPPED");
        assertTrue(last.getPosition() <= totalTarget + 0.5,
                "已到终点附近再牵引不应冲过终点，最终位置=" + last.getPosition()
                        + " totalTarget+容差=" + (totalTarget + 0.5));
    }

    /**
     * MANUAL + resume_ato 后：summary.currentMode=ato，states 非空，最终能 STOPPED 停车。
     */
    @Test
    void manual_resumeAto_continuesAutoStrategy() {
        LineProfile line = loader.buildLineProfile(1, 2);
        ScenarioConfig scenario = demoScenarioProvider.buildScenario(line);
        SimulationResult full = service.run(scenario);
        TrainState midState = full.getStates().get(full.getStates().size() / 3);
        midState.setAbsolutePosition(313.0 + midState.getPosition());

        SimulationControlRequest req = new SimulationControlRequest();
        req.setFromStationId(1);
        req.setToStationId(2);
        req.setCurrentState(midState);
        req.setCurrentMode(DrivingMode.MANUAL);
        req.setControlCommand(new ControlCommand("resume_ato", 0.0, 0.0));
        req.setTotalTargetPosition(line.getTargetStopPosition());

        SimulationResult result = service.runContinuation(req, scenario);

        assertEquals(DrivingMode.ATO, result.getSummary().getCurrentMode(),
                "MANUAL + resume_ato 后 currentMode 应为 ato");
        assertFalse(result.getStates().isEmpty(), "resume_ato 续算 states 不能为空");
        assertEquals(SimulationPhase.STOPPED,
                result.getStates().get(result.getStates().size() - 1).getPhase(),
                "resume_ato 后应按 ATO 自动策略续算并最终 STOPPED 停车");
    }

    /**
     * EMERGENCY + resume_ato 应抛 IllegalArgumentException（紧急模式不能直接恢复 ATO）。
     */
    @Test
    void emergency_resumeAto_rejected() {
        LineProfile line = loader.buildLineProfile(1, 2);
        ScenarioConfig scenario = demoScenarioProvider.buildScenario(line);
        TrainState state = makeMovingState(30.0, 300.0, 5.0, "braking");

        SimulationControlRequest req = new SimulationControlRequest();
        req.setFromStationId(1);
        req.setToStationId(2);
        req.setCurrentState(state);
        req.setCurrentMode(DrivingMode.EMERGENCY);
        req.setControlCommand(new ControlCommand("resume_ato", 0.0, 0.0));
        req.setTotalTargetPosition(line.getTargetStopPosition());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.runContinuation(req, scenario));
        assertTrue(ex.getMessage().contains("紧急模式不能直接恢复 ATO"),
                "异常消息应说明紧急模式不能直接恢复 ATO，实际：" + ex.getMessage());
    }

    /**
     * EMERGENCY 停稳 state（velocity=0, phase=STOPPED）+ reset_emergency：
     * summary.currentMode=manual，nextMode=null，states 仅一帧，safetyEvents/stationStops 为空。
     */
    @Test
    void emergency_resetEmergency_whenStopped_returnsManual() {
        LineProfile line = loader.buildLineProfile(1, 2);
        ScenarioConfig scenario = demoScenarioProvider.buildScenario(line);
        double totalTarget = line.getTargetStopPosition();

        TrainState stoppedState = new TrainState(50.0, 400.0, 0.0, 0.0,
                SimulationPhase.STOPPED, "T1");
        stoppedState.setAbsolutePosition(313.0 + 400.0);

        SimulationControlRequest req = new SimulationControlRequest();
        req.setFromStationId(1);
        req.setToStationId(2);
        req.setCurrentState(stoppedState);
        req.setCurrentMode(DrivingMode.EMERGENCY);
        req.setControlCommand(new ControlCommand("reset_emergency", 0.0, 0.0));
        req.setTotalTargetPosition(totalTarget);

        SimulationResult result = service.runContinuation(req, scenario);

        assertEquals(DrivingMode.MANUAL, result.getSummary().getCurrentMode(),
                "EMERGENCY 停稳后 reset_emergency 应复位到 MANUAL");
        assertNull(result.getSummary().getNextMode(),
                "reset_emergency 后 nextMode 应为 null（已复位）");
        assertEquals(1, result.getStates().size(), "reset_emergency 应只返回一个停稳帧");
        TrainState only = result.getStates().get(0);
        assertEquals(SimulationPhase.STOPPED, only.getPhase());
        assertEquals(0.0, only.getVelocity(), 1.0e-9);
        assertEquals(0.0, only.getAcceleration(), 1.0e-9);
        assertEquals(400.0, only.getPosition(), 1.0e-9, "复位后位置应保持不变");
        assertTrue(result.getSafetyEvents().isEmpty(), "reset_emergency 不应生成 SafetyEvent");
        assertTrue(result.getStationStops().isEmpty(), "reset_emergency stationStops 应为空");
        assertNotNull(result.getStopResult(), "reset_emergency 仍应返回 stopResult");
        assertEquals(totalTarget, result.getStopResult().getTargetStopPosition(), 1.0e-9);
    }

    /**
     * EMERGENCY 未停稳 state（velocity=2.0）+ reset_emergency 应抛 IllegalArgumentException。
     */
    @Test
    void emergency_resetEmergency_whenNotStopped_rejected() {
        LineProfile line = loader.buildLineProfile(1, 2);
        ScenarioConfig scenario = demoScenarioProvider.buildScenario(line);

        TrainState movingState = new TrainState(40.0, 300.0, 2.0, -1.2,
                SimulationPhase.BRAKING, "T1");
        movingState.setAbsolutePosition(313.0 + 300.0);

        SimulationControlRequest req = new SimulationControlRequest();
        req.setFromStationId(1);
        req.setToStationId(2);
        req.setCurrentState(movingState);
        req.setCurrentMode(DrivingMode.EMERGENCY);
        req.setControlCommand(new ControlCommand("reset_emergency", 0.0, 0.0));
        req.setTotalTargetPosition(line.getTargetStopPosition());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.runContinuation(req, scenario));
        assertTrue(ex.getMessage().contains("未停稳"),
                "异常消息应说明未停稳不能复位，实际：" + ex.getMessage());
    }
}
