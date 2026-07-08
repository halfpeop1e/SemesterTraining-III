package com.bjtu.railtransit.domain.model;

import java.util.List;
import java.util.Map;

/**
 * 综合评估报告
 */
public class EvaluationReport {
    private String scenarioName;
    private long simulationDuration;                     // 仿真时长 (ms)
    // 能耗
    private List<EnergyRecord> energyRecords;
    private PeakPowerResult peakPowerResult;
    private String powerRiskLevel;                       // "safe" / "warning" / "danger"
    private double powerSupplyThreshold;                 // 供电阈值 (kW)
    // 评估指标
    private List<StopErrorResult> stopErrors;
    private PunctualityResult punctuality;
    private ComfortResult comfort;
    private List<SafetyEvent> safetyEvents;
    // 汇总
    private Map<String, Double> summary;                 // 各指标汇总

    public EvaluationReport() {}

    public String getScenarioName() { return scenarioName; }
    public void setScenarioName(String scenarioName) { this.scenarioName = scenarioName; }

    public long getSimulationDuration() { return simulationDuration; }
    public void setSimulationDuration(long simulationDuration) { this.simulationDuration = simulationDuration; }

    public List<EnergyRecord> getEnergyRecords() { return energyRecords; }
    public void setEnergyRecords(List<EnergyRecord> energyRecords) { this.energyRecords = energyRecords; }

    public PeakPowerResult getPeakPowerResult() { return peakPowerResult; }
    public void setPeakPowerResult(PeakPowerResult peakPowerResult) { this.peakPowerResult = peakPowerResult; }

    public String getPowerRiskLevel() { return powerRiskLevel; }
    public void setPowerRiskLevel(String powerRiskLevel) { this.powerRiskLevel = powerRiskLevel; }

    public double getPowerSupplyThreshold() { return powerSupplyThreshold; }
    public void setPowerSupplyThreshold(double powerSupplyThreshold) { this.powerSupplyThreshold = powerSupplyThreshold; }

    public List<StopErrorResult> getStopErrors() { return stopErrors; }
    public void setStopErrors(List<StopErrorResult> stopErrors) { this.stopErrors = stopErrors; }

    public PunctualityResult getPunctuality() { return punctuality; }
    public void setPunctuality(PunctualityResult punctuality) { this.punctuality = punctuality; }

    public ComfortResult getComfort() { return comfort; }
    public void setComfort(ComfortResult comfort) { this.comfort = comfort; }

    public List<SafetyEvent> getSafetyEvents() { return safetyEvents; }
    public void setSafetyEvents(List<SafetyEvent> safetyEvents) { this.safetyEvents = safetyEvents; }

    public Map<String, Double> getSummary() { return summary; }
    public void setSummary(Map<String, Double> summary) { this.summary = summary; }
}
