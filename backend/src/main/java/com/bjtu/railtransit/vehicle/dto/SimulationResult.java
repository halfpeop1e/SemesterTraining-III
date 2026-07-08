package com.bjtu.railtransit.vehicle.dto;

import java.util.List;

/**
 * 一次仿真的完整返回结果，四段字段齐全：
 * states / summary / stopResult / safetyEvents。
 *
 * <p>多站扩展（新增 stationStops，旧字段保持不变）：
 * stopResult 仍表示最终目标站的停车结果；stationStops 记录途经各站的停车记录（含中间站）。
 * 单区间仿真时 stationStops 为空列表，不影响旧行为。</p>
 */
public class SimulationResult {

    /** 逐时间点列车状态序列，由 service 循环计算生成，非空。 */
    private List<TrainState> states;

    /** 仿真总结指标。 */
    private SimulationSummary summary;

    /** 最终目标站停车结果（单区间或多站的终点站）。 */
    private StopResult stopResult;

    /** SafetyGuard 事件列表。 */
    private List<SafetyEvent> safetyEvents;

    /**
     * 多站连续仿真各站停车记录（新增，单区间仿真时为空列表）。
     * 包含所有途经站（含终点站）的停车结果，每站含停车误差、驻留时间等信息。
     */
    private List<StationStop> stationStops;

    public SimulationResult() {
    }

    public SimulationResult(List<TrainState> states, SimulationSummary summary,
                             StopResult stopResult, List<SafetyEvent> safetyEvents) {
        this.states = states;
        this.summary = summary;
        this.stopResult = stopResult;
        this.safetyEvents = safetyEvents;
        this.stationStops = java.util.Collections.emptyList();
    }

    public List<TrainState> getStates() { return states; }
    public void setStates(List<TrainState> states) { this.states = states; }

    public SimulationSummary getSummary() { return summary; }
    public void setSummary(SimulationSummary summary) { this.summary = summary; }

    public StopResult getStopResult() { return stopResult; }
    public void setStopResult(StopResult stopResult) { this.stopResult = stopResult; }

    public List<SafetyEvent> getSafetyEvents() { return safetyEvents; }
    public void setSafetyEvents(List<SafetyEvent> safetyEvents) { this.safetyEvents = safetyEvents; }

    public List<StationStop> getStationStops() { return stationStops; }
    public void setStationStops(List<StationStop> stationStops) { this.stationStops = stationStops; }
}
