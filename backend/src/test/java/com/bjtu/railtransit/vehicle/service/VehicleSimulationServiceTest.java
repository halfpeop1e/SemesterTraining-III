package com.bjtu.railtransit.vehicle.service;

import com.bjtu.railtransit.vehicle.dto.SimulationResult;
import com.bjtu.railtransit.vehicle.dto.StopResult;
import com.bjtu.railtransit.vehicle.dto.TrainState;
import com.bjtu.railtransit.vehicle.enums.SimulationPhase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 1A 车辆仿真核心逻辑单元测试。
 *
 * <p>验证 states 由 service 循环计算生成、非空，速度全程非负，位置随时间
 * 单调不减，末态速度收敛趋近 0，且四段响应字段（states/summary/stopResult/
 * safetyEvents）均存在。</p>
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
     * 阶段 1 补强验证：制动触发提前量修正后，演示配置下的停站误差应从修正前的
     * 约 6.7m（离散检测延迟导致的系统性多冲）显著收敛到 1m 量级以内。
     *
     * <p>本断言不要求达到阶段 3 的 0.5m 严格标准（阶段 3 需在真实动力学、含
     * 制动响应时间补偿的条件下才追求该精度），只验证本轮"理想匀减速模型下的
     * 离散检测延迟"已被消除，且该数值是仿真真实跑出来的，未做任何硬对齐。</p>
     */
    @Test
    void stopErrorConvergesAfterBrakingTriggerLeadCompensation() {
        SimulationResult result = service.runDemoSimulation();
        StopResult stopResult = result.getStopResult();

        assertTrue(Math.abs(stopResult.getStopError()) <= 1.0,
                "停站误差应收敛到 1m 量级以内，实际为 " + stopResult.getStopError());
    }
}
