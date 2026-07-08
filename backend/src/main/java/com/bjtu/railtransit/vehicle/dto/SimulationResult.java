package com.bjtu.railtransit.vehicle.dto;

import java.util.List;

/**
 * 一次仿真的完整返回结果，四段字段齐全：
 * states / summary / stopResult / safetyEvents。
 *
 * <p>本轮（阶段 1A）由 {@link com.bjtu.railtransit.vehicle.service.VehicleSimulationService}
 * 一次性计算生成，作为 {@code POST /api/vehicle/simulation/run} 的响应数据体，
 * 包裹在 {@link com.bjtu.railtransit.common.ApiResponse} 中返回。</p>
 */
public class SimulationResult {

    /** 逐时间点列车状态序列，由 service 循环计算生成，非空。 */
    private List<TrainState> states;

    /** 仿真总结指标。 */
    private SimulationSummary summary;

    /** 自动停站结果（字段齐全，阶段 1A 用简化判定填充）。 */
    private StopResult stopResult;

    /** SafetyGuard 事件列表（阶段 1A 恒为空数组，字段结构已预留）。 */
    private List<SafetyEvent> safetyEvents;

    public SimulationResult() {
    }

    public SimulationResult(List<TrainState> states, SimulationSummary summary,
                             StopResult stopResult, List<SafetyEvent> safetyEvents) {
        this.states = states;
        this.summary = summary;
        this.stopResult = stopResult;
        this.safetyEvents = safetyEvents;
    }

    public List<TrainState> getStates() {
        return states;
    }

    public void setStates(List<TrainState> states) {
        this.states = states;
    }

    public SimulationSummary getSummary() {
        return summary;
    }

    public void setSummary(SimulationSummary summary) {
        this.summary = summary;
    }

    public StopResult getStopResult() {
        return stopResult;
    }

    public void setStopResult(StopResult stopResult) {
        this.stopResult = stopResult;
    }

    public List<SafetyEvent> getSafetyEvents() {
        return safetyEvents;
    }

    public void setSafetyEvents(List<SafetyEvent> safetyEvents) {
        this.safetyEvents = safetyEvents;
    }
}
