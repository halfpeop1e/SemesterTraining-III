package com.bjtu.railtransit.domain.model;

/**
 * 舒适性评估结果
 */
public class ComfortResult {
    private double maxAcceleration;       // 最大加速度 (m/s²)
    private double maxDeceleration;       // 最大减速度 (m/s²)
    private double maxJerk;               // 最大加加速度 (m/s³)
    private double comfortScore;          // 舒适性评分 (0-100)
    private String comfortLevel;          // "excellent" / "good" / "acceptable" / "poor"

    public ComfortResult() {}

    public double getMaxAcceleration() { return maxAcceleration; }
    public void setMaxAcceleration(double maxAcceleration) { this.maxAcceleration = maxAcceleration; }

    public double getMaxDeceleration() { return maxDeceleration; }
    public void setMaxDeceleration(double maxDeceleration) { this.maxDeceleration = maxDeceleration; }

    public double getMaxJerk() { return maxJerk; }
    public void setMaxJerk(double maxJerk) { this.maxJerk = maxJerk; }

    public double getComfortScore() { return comfortScore; }
    public void setComfortScore(double comfortScore) { this.comfortScore = comfortScore; }

    public String getComfortLevel() { return comfortLevel; }
    public void setComfortLevel(String comfortLevel) { this.comfortLevel = comfortLevel; }
}
