package com.bjtu.railtransit.domain.dto;

import com.bjtu.railtransit.domain.model.SimulationLog;
import java.util.List;

/**
 * 能耗计算请求
 */
public class EnergyCalculateRequest {
    private String scenarioName;
    private List<SimulationLog> simulationLogs;
    private double tractionEfficiency;       // 牵引效率 (默认 0.85)
    private double regenEfficiency;          // 再生制动效率 (默认 0.65)
    private double powerSupplyThreshold;     // 供电阈值 (kW)

    public String getScenarioName() { return scenarioName; }
    public void setScenarioName(String scenarioName) { this.scenarioName = scenarioName; }

    public List<SimulationLog> getSimulationLogs() { return simulationLogs; }
    public void setSimulationLogs(List<SimulationLog> simulationLogs) { this.simulationLogs = simulationLogs; }

    public double getTractionEfficiency() { return tractionEfficiency; }
    public void setTractionEfficiency(double tractionEfficiency) { this.tractionEfficiency = tractionEfficiency; }

    public double getRegenEfficiency() { return regenEfficiency; }
    public void setRegenEfficiency(double regenEfficiency) { this.regenEfficiency = regenEfficiency; }

    public double getPowerSupplyThreshold() { return powerSupplyThreshold; }
    public void setPowerSupplyThreshold(double powerSupplyThreshold) { this.powerSupplyThreshold = powerSupplyThreshold; }
}
