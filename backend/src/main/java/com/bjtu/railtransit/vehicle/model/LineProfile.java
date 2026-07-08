package com.bjtu.railtransit.vehicle.model;

/**
 * 线路静态配置（STATIC 数据）。
 *
 * <p>阶段 1A 使用后端内置的最简演示线路：单一区间、单一限速，
 * 不读取 {@code configs/} 下的 sample JSON。坡度等字段留给后续阶段，
 * 缺失时按 0 处理（当前版本尚未引入坡度字段）。</p>
 */
public class LineProfile {

    /** 线路起点位置，单位 m。 */
    private final double startPosition;

    /** 目标停车点位置（本轮等同于线路终点），单位 m。 */
    private final double targetStopPosition;

    /** 区间限速，单位 m/s。 */
    private final double speedLimit;

    public LineProfile(double startPosition, double targetStopPosition, double speedLimit) {
        this.startPosition = startPosition;
        this.targetStopPosition = targetStopPosition;
        this.speedLimit = speedLimit;
    }

    public double getStartPosition() {
        return startPosition;
    }

    public double getTargetStopPosition() {
        return targetStopPosition;
    }

    public double getSpeedLimit() {
        return speedLimit;
    }
}
