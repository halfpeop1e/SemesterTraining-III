package com.bjtu.railtransit.domain.model;

/**
 * 单列车能耗记录
 */
public class EnergyRecord {
    private int trainId;
    private double totalTractionEnergyKwh;   // 总牵引能耗 (kWh)
    private double totalRegenEnergyKwh;      // 总再生制动回收能量 (kWh)
    private double netEnergyKwh;              // 净能耗 (kWh)
    private double maxTractionPowerKw;        // 最大牵引功率 (kW)
    private double avgTractionPowerKw;        // 平均牵引功率 (kW)

    public EnergyRecord() {}

    public int getTrainId() { return trainId; }
    public void setTrainId(int trainId) { this.trainId = trainId; }

    public double getTotalTractionEnergyKwh() { return totalTractionEnergyKwh; }
    public void setTotalTractionEnergyKwh(double totalTractionEnergyKwh) { this.totalTractionEnergyKwh = totalTractionEnergyKwh; }

    public double getTotalRegenEnergyKwh() { return totalRegenEnergyKwh; }
    public void setTotalRegenEnergyKwh(double totalRegenEnergyKwh) { this.totalRegenEnergyKwh = totalRegenEnergyKwh; }

    public double getNetEnergyKwh() { return netEnergyKwh; }
    public void setNetEnergyKwh(double netEnergyKwh) { this.netEnergyKwh = netEnergyKwh; }

    public double getMaxTractionPowerKw() { return maxTractionPowerKw; }
    public void setMaxTractionPowerKw(double maxTractionPowerKw) { this.maxTractionPowerKw = maxTractionPowerKw; }

    public double getAvgTractionPowerKw() { return avgTractionPowerKw; }
    public void setAvgTractionPowerKw(double avgTractionPowerKw) { this.avgTractionPowerKw = avgTractionPowerKw; }
}
