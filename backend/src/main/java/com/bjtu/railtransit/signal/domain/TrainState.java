package com.bjtu.railtransit.signal.domain;

/**
 * MA 输入列车状态（绝对里程 m + km/h）。
 * P2：故障限速 / 定位丢失 / 完整性丢失（司机台协议 3.3.1）。
 */
public class TrainState {
    private String trainId;
    private double positionM;            // 车头里程
    private double speedKmh;             // 当前速度
    private double accelerationMps2;     // 当前加速度（m/s²）
    private double lengthM;              // 列车长度
    private Direction direction;         // UP / DOWN / INVALID
    private double timestamp;            // 仿真时刻

    /**
     * 每车故障限速 km/h（协议 A6，边界由 cm/s 换入）。
     * {@link Double#NaN} 表示无故障限速（不参与 min）。
     */
    private double faultSpeedLimitKmh = Double.NaN;

    /** 列车定位丢失（协议 A9）→ MA fail-safe 收紧 */
    private boolean positionLost;

    /** 列车完整性丢失（协议 A10）→ MA fail-safe 收紧 */
    private boolean integrityLost;

    /**
     * 载重系数（满载率）0.0~1.0（A4 载重折减制动）。
     * {@link Double#NaN} 表示未知 → 按额定 aBrake 计算（与现网行为兼容）。
     */
    private double loadFactor = Double.NaN;

    public TrainState() {}

    public String getTrainId() { return trainId; }
    public void setTrainId(String trainId) { this.trainId = trainId; }
    public double getPositionM() { return positionM; }
    public void setPositionM(double positionM) { this.positionM = positionM; }
    public double getSpeedKmh() { return speedKmh; }
    public void setSpeedKmh(double speedKmh) { this.speedKmh = speedKmh; }
    public double getAccelerationMps2() { return accelerationMps2; }
    public void setAccelerationMps2(double accelerationMps2) { this.accelerationMps2 = accelerationMps2; }
    public double getLengthM() { return lengthM; }
    public void setLengthM(double lengthM) { this.lengthM = lengthM; }
    public Direction getDirection() { return direction; }
    public void setDirection(Direction direction) { this.direction = direction; }
    public double getTimestamp() { return timestamp; }
    public void setTimestamp(double timestamp) { this.timestamp = timestamp; }

    public double getFaultSpeedLimitKmh() { return faultSpeedLimitKmh; }
    public void setFaultSpeedLimitKmh(double faultSpeedLimitKmh) { this.faultSpeedLimitKmh = faultSpeedLimitKmh; }
    public boolean isPositionLost() { return positionLost; }
    public void setPositionLost(boolean positionLost) { this.positionLost = positionLost; }
    public boolean isIntegrityLost() { return integrityLost; }
    public void setIntegrityLost(boolean integrityLost) { this.integrityLost = integrityLost; }

    /** A4 载重系数（满载率）；NaN=未知（按额定计算） */
    public double getLoadFactor() { return loadFactor; }
    public void setLoadFactor(double loadFactor) { this.loadFactor = loadFactor; }
}
