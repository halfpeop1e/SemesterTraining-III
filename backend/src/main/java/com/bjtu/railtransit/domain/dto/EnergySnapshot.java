package com.bjtu.railtransit.domain.dto;

import com.bjtu.railtransit.domain.model.EnergyRecord;
import java.util.List;

/**
 * 能耗实时快照（供调度页面实时展示用）
 */
public class EnergySnapshot {
    private List<EnergyRecord> energyRecords;   // 各列车能耗
    private double totalTractionKwh;             // 总牵引能耗 (kWh)
    private double totalRegenKwh;                // 总再生回收能量 (kWh)
    private double netEnergyKwh;                 // 净能耗 (kWh)
    private double maxPeakKw;                    // 峰值功率 (kW)
    private long timeOfPeak;                     // 峰值时刻 (ms)
    private List<Integer> vehiclesAtPeak;        // 峰值时刻车辆
    private String riskLevel;                    // "safe" / "warning" / "danger"
    private double powerSupplyThreshold;         // 供电阈值 (kW)
    private double thresholdRatio;               // 峰值/阈值比

    public EnergySnapshot() {}

    public List<EnergyRecord> getEnergyRecords() { return energyRecords; }
    public void setEnergyRecords(List<EnergyRecord> energyRecords) { this.energyRecords = energyRecords; }

    public double getTotalTractionKwh() { return totalTractionKwh; }
    public void setTotalTractionKwh(double totalTractionKwh) { this.totalTractionKwh = totalTractionKwh; }

    public double getTotalRegenKwh() { return totalRegenKwh; }
    public void setTotalRegenKwh(double totalRegenKwh) { this.totalRegenKwh = totalRegenKwh; }

    public double getNetEnergyKwh() { return netEnergyKwh; }
    public void setNetEnergyKwh(double netEnergyKwh) { this.netEnergyKwh = netEnergyKwh; }

    public double getMaxPeakKw() { return maxPeakKw; }
    public void setMaxPeakKw(double maxPeakKw) { this.maxPeakKw = maxPeakKw; }

    public long getTimeOfPeak() { return timeOfPeak; }
    public void setTimeOfPeak(long timeOfPeak) { this.timeOfPeak = timeOfPeak; }

    public List<Integer> getVehiclesAtPeak() { return vehiclesAtPeak; }
    public void setVehiclesAtPeak(List<Integer> vehiclesAtPeak) { this.vehiclesAtPeak = vehiclesAtPeak; }

    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }

    public double getPowerSupplyThreshold() { return powerSupplyThreshold; }
    public void setPowerSupplyThreshold(double powerSupplyThreshold) { this.powerSupplyThreshold = powerSupplyThreshold; }

    public double getThresholdRatio() { return thresholdRatio; }
    public void setThresholdRatio(double thresholdRatio) { this.thresholdRatio = thresholdRatio; }
}
