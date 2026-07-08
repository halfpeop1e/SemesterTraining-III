package com.bjtu.railtransit.domain.dto;

/**
 * 调度策略请求 DTO
 */
public class StrategyRequest {
    private String trainId;
    private String strategyType; // CHANGE_LEVEL | SKIP_STATION | SHORT_TURN | RESUME_NORMAL
    private double targetValue;  // 目标值 (运行等级/站点索引等)

    public StrategyRequest() {}

    public String getTrainId() { return trainId; }
    public void setTrainId(String v) { this.trainId = v; }
    public String getStrategyType() { return strategyType; }
    public void setStrategyType(String v) { this.strategyType = v; }
    public double getTargetValue() { return targetValue; }
    public void setTargetValue(double v) { this.targetValue = v; }
}
