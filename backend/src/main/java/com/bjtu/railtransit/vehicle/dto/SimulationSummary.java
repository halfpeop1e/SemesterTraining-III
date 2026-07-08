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

    /**
     * 线路限速，单位 m/s（阶段 1.6 704 语义对齐新增字段）。
     *
     * <p>对应 704 DMI「允许速度」（704 侧单位 cm/s，本项目内部统一使用 SI 单位 m/s，
     * 换算关系为 704值(cm/s) = 本字段(m/s) * 100）。数值取自
     * {@link com.bjtu.railtransit.vehicle.model.LineProfile#getSpeedLimit()}，
     * 由 service 在构建结果时原样填充，不改变任何既有字段的含义。</p>
     */
    private double speedLimit;

    /**
     * 后端仿真采样步长，单位 s（等于 {@link com.bjtu.railtransit.vehicle.model.ScenarioConfig#getDt()}）。
     *
     * <p>前端播放 interval（毫秒）= dtPerFrame * 1000 / speedMultiplier，
     * 由此保证播放速度与后端采样频率解耦：改变倍速只改前端 interval，不改后端 dt。</p>
     */
    private double dtPerFrame;

    public SimulationSummary() {
    }

    public SimulationSummary(double maxVelocity, double totalTime, double finalPosition) {
        this.maxVelocity = maxVelocity;
        this.totalTime = totalTime;
        this.finalPosition = finalPosition;
    }

    public SimulationSummary(double maxVelocity, double totalTime, double finalPosition, double speedLimit) {
        this.maxVelocity = maxVelocity;
        this.totalTime = totalTime;
        this.finalPosition = finalPosition;
        this.speedLimit = speedLimit;
    }

    public SimulationSummary(double maxVelocity, double totalTime, double finalPosition, double speedLimit, double dtPerFrame) {
        this.maxVelocity = maxVelocity;
        this.totalTime = totalTime;
        this.finalPosition = finalPosition;
        this.speedLimit = speedLimit;
        this.dtPerFrame = dtPerFrame;
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

    public double getSpeedLimit() {
        return speedLimit;
    }

    public void setSpeedLimit(double speedLimit) {
        this.speedLimit = speedLimit;
    }

    public double getDtPerFrame() {
        return dtPerFrame;
    }

    public void setDtPerFrame(double dtPerFrame) {
        this.dtPerFrame = dtPerFrame;
    }
}
