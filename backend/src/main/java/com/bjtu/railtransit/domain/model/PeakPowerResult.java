package com.bjtu.railtransit.domain.model;

import java.util.List;

/**
 * 多车供电峰值检测结果
 */
public class PeakPowerResult {
    private double maxPeakKw;                // 最大峰值功率 (kW)
    private long timeOfPeak;                 // 峰值时刻 (ms)
    private List<Integer> vehiclesAtPeak;    // 峰值时刻涉及的车辆ID
    private String riskLevel;                // "safe" / "warning" / "danger"

    public PeakPowerResult() {}

    public double getMaxPeakKw() { return maxPeakKw; }
    public void setMaxPeakKw(double maxPeakKw) { this.maxPeakKw = maxPeakKw; }

    public long getTimeOfPeak() { return timeOfPeak; }
    public void setTimeOfPeak(long timeOfPeak) { this.timeOfPeak = timeOfPeak; }

    public List<Integer> getVehiclesAtPeak() { return vehiclesAtPeak; }
    public void setVehiclesAtPeak(List<Integer> vehiclesAtPeak) { this.vehiclesAtPeak = vehiclesAtPeak; }

    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
}
