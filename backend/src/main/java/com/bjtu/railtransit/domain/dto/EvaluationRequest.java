package com.bjtu.railtransit.domain.dto;

import com.bjtu.railtransit.domain.model.SimulationLog;
import java.util.List;
import java.util.Map;

/**
 * 指标评估请求
 */
public class EvaluationRequest {
    private String scenarioName;
    private List<SimulationLog> simulationLogs;
    // 线路静态配置：站点ID -> 目标位置(m)
    private Map<Integer, Double> stationPositions;
    // 站点ID -> 站名
    private Map<Integer, String> stationNames;
    // 站点ID -> 计划到站时间(s)
    private Map<Integer, Double> plannedArrivals;
    // 站点ID -> 方向 ("up"/"down")
    private Map<Integer, String> stationDirections;
    // 限速值列表 (Seg位置 -> 限速值 m/s)，用于超速检测
    private Map<Integer, Double> speedLimits;
    private double stopWindowTolerance;     // 停站窗容忍度 (m, 默认 0.5)
    private double punctualityTolerance;    // 准点容忍度 (s, 默认 30)

    public String getScenarioName() { return scenarioName; }
    public void setScenarioName(String scenarioName) { this.scenarioName = scenarioName; }

    public List<SimulationLog> getSimulationLogs() { return simulationLogs; }
    public void setSimulationLogs(List<SimulationLog> simulationLogs) { this.simulationLogs = simulationLogs; }

    public Map<Integer, Double> getStationPositions() { return stationPositions; }
    public void setStationPositions(Map<Integer, Double> stationPositions) { this.stationPositions = stationPositions; }

    public Map<Integer, String> getStationNames() { return stationNames; }
    public void setStationNames(Map<Integer, String> stationNames) { this.stationNames = stationNames; }

    public Map<Integer, Double> getPlannedArrivals() { return plannedArrivals; }
    public void setPlannedArrivals(Map<Integer, Double> plannedArrivals) { this.plannedArrivals = plannedArrivals; }

    public Map<Integer, String> getStationDirections() { return stationDirections; }
    public void setStationDirections(Map<Integer, String> stationDirections) { this.stationDirections = stationDirections; }

    public Map<Integer, Double> getSpeedLimits() { return speedLimits; }
    public void setSpeedLimits(Map<Integer, Double> speedLimits) { this.speedLimits = speedLimits; }

    public double getStopWindowTolerance() { return stopWindowTolerance; }
    public void setStopWindowTolerance(double stopWindowTolerance) { this.stopWindowTolerance = stopWindowTolerance; }

    public double getPunctualityTolerance() { return punctualityTolerance; }
    public void setPunctualityTolerance(double punctualityTolerance) { this.punctualityTolerance = punctualityTolerance; }
}
