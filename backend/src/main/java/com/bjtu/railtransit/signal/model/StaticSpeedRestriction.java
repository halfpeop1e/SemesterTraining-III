package com.bjtu.railtransit.signal.model;

public class StaticSpeedRestriction {
    private int id;
    private int segId;
    private double startOffsetCm;
    private double endOffsetCm;
    private Integer switchId;               // 关联道岔（可空）
    private double speedLimitCmps;          // 原始 cm/s
    private double speedLimitKmh;           // 换算后 km/h（= cmps * 0.036）

    public StaticSpeedRestriction() {}

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public int getSegId() { return segId; }
    public void setSegId(int segId) { this.segId = segId; }
    public double getStartOffsetCm() { return startOffsetCm; }
    public void setStartOffsetCm(double v) { this.startOffsetCm = v; }
    public double getEndOffsetCm() { return endOffsetCm; }
    public void setEndOffsetCm(double v) { this.endOffsetCm = v; }
    public Integer getSwitchId() { return switchId; }
    public void setSwitchId(Integer switchId) { this.switchId = switchId; }
    public double getSpeedLimitCmps() { return speedLimitCmps; }
    public void setSpeedLimitCmps(double speedLimitCmps) { this.speedLimitCmps = speedLimitCmps; }
    public double getSpeedLimitKmh() { return speedLimitKmh; }
    public void setSpeedLimitKmh(double speedLimitKmh) { this.speedLimitKmh = speedLimitKmh; }
}
