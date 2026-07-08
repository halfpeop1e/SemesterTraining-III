package com.bjtu.railtransit.vehicle.dto;

/**
 * 仿真总结指标（对应指挥书 {@code summary} 契约的最小字段集合）。
 */
public class SimulationSummary {

    /** 全程最大速度，单位 m/s。 */
    private double maxVelocity;

    /** 全程总用时，单位 s。 */
    private double totalTime;

    /** 终态位置，单位 m。 */
    private double finalPosition;

    public SimulationSummary() {
    }

    public SimulationSummary(double maxVelocity, double totalTime, double finalPosition) {
        this.maxVelocity = maxVelocity;
        this.totalTime = totalTime;
        this.finalPosition = finalPosition;
    }

    public double getMaxVelocity() {
        return maxVelocity;
    }

    public void setMaxVelocity(double maxVelocity) {
        this.maxVelocity = maxVelocity;
    }

    public double getTotalTime() {
        return totalTime;
    }

    public void setTotalTime(double totalTime) {
        this.totalTime = totalTime;
    }

    public double getFinalPosition() {
        return finalPosition;
    }

    public void setFinalPosition(double finalPosition) {
        this.finalPosition = finalPosition;
    }
}
