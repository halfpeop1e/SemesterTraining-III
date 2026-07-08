package com.bjtu.railtransit.signal.model;

public class Switch {
    private String id;
    private double positionM;                // 道岔中心里程
    private int normalSegId;                 // 定位接通 Seg
    private int reverseSegId;                // 反位接通 Seg
    private int mergeSegId;                  // 汇合 Seg
    private int linkedSwitchId;              // 联动道岔（65535=无）
    private SwitchState state;               // NORMAL / REVERSE
    private double divergingSpeedLimitKmh;   // 侧向限速 km/h

    public Switch() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public double getPositionM() { return positionM; }
    public void setPositionM(double positionM) { this.positionM = positionM; }
    public int getNormalSegId() { return normalSegId; }
    public void setNormalSegId(int v) { this.normalSegId = v; }
    public int getReverseSegId() { return reverseSegId; }
    public void setReverseSegId(int v) { this.reverseSegId = v; }
    public int getMergeSegId() { return mergeSegId; }
    public void setMergeSegId(int v) { this.mergeSegId = v; }
    public int getLinkedSwitchId() { return linkedSwitchId; }
    public void setLinkedSwitchId(int v) { this.linkedSwitchId = v; }
    public SwitchState getState() { return state; }
    public void setState(SwitchState state) { this.state = state; }
    public double getDivergingSpeedLimitKmh() { return divergingSpeedLimitKmh; }
    public void setDivergingSpeedLimitKmh(double v) { this.divergingSpeedLimitKmh = v; }
}
