package com.bjtu.railtransit.signal.model;

public class TrackSegment {
    private String id;
    private double lengthCm;                 // 长度 cm
    private int forwardStartSegId;           // 起点正向相邻 SegID
    private int sideStartSegId;              // 起点侧向相邻 SegID（65535=无）
    private int forwardEndSegId;             // 终点正向相邻 SegID
    private int sideEndSegId;                // 终点侧向相邻 SegID
    private int zcZoneId;                    // 所属 ZC 区域（ZC≈RBC，MA 类比）
    private int atsZoneId;
    private int ciZoneId;

    public TrackSegment() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public double getLengthCm() { return lengthCm; }
    public void setLengthCm(double lengthCm) { this.lengthCm = lengthCm; }
    public int getForwardStartSegId() { return forwardStartSegId; }
    public void setForwardStartSegId(int v) { this.forwardStartSegId = v; }
    public int getSideStartSegId() { return sideStartSegId; }
    public void setSideStartSegId(int v) { this.sideStartSegId = v; }
    public int getForwardEndSegId() { return forwardEndSegId; }
    public void setForwardEndSegId(int v) { this.forwardEndSegId = v; }
    public int getSideEndSegId() { return sideEndSegId; }
    public void setSideEndSegId(int v) { this.sideEndSegId = v; }
    public int getZcZoneId() { return zcZoneId; }
    public void setZcZoneId(int v) { this.zcZoneId = v; }
    public int getAtsZoneId() { return atsZoneId; }
    public void setAtsZoneId(int v) { this.atsZoneId = v; }
    public int getCiZoneId() { return ciZoneId; }
    public void setCiZoneId(int v) { this.ciZoneId = v; }
}
