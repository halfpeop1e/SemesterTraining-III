package com.bjtu.railtransit.vehicle.dto;

import java.util.Collections;
import java.util.List;

/**
 * 一次仿真的完整返回结果。
 * states / summary / stopResult / safetyEvents 四段字段始终存在。
 * stationStops 仅多站仿真时非空（单区间时为空列表）。
 */
public class SimulationResult {

    /** 逐时间点列车状态序列，非空。position 为从 fromStation 起的连续累计里程。 */
    private List<TrainState> states;

    /** 仿真总结指标。 */
    private SimulationSummary summary;

    /**
     * 最终目标站停车结果。
     * targetStopPosition = fromStation 到最终 toStation 的总距离（多站时为全程总里程）。
     */
    private StopResult stopResult;

    /** SafetyGuard 事件列表。 */
    private List<SafetyEvent> safetyEvents;

    /**
     * 各站停车记录（多站时非空，单区间时为空列表）。
     * targetPosition 字段为该站相对于 fromStation 的累计里程，与 states.position 坐标系一致。
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
        this.stationStops = Collections.emptyList();
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
