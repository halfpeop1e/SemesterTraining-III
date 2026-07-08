package com.bjtu.railtransit.signal.model;

import com.bjtu.railtransit.signal.domain.SignalAspect;

public class Signal {
    private int id;
    private String name;                     // Z5 / SCD1 / XQ1 ...
    private int type;                        // 1入站/2出站/3中间 ...
    private long attr;                       // 属性位 0x000C ...
    private int segId;
    private double offsetCm;
    private int protectDir;                  // 防护方向 0xaa/0x55
    private SignalAspect aspect;             // 实时显示状态（null=未接入，eoaFromSignals 按 fail-safe 截断）

    public Signal() {}

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getType() { return type; }
    public void setType(int type) { this.type = type; }
    public long getAttr() { return attr; }
    public void setAttr(long attr) { this.attr = attr; }
    public int getSegId() { return segId; }
    public void setSegId(int segId) { this.segId = segId; }
    public double getOffsetCm() { return offsetCm; }
    public void setOffsetCm(double offsetCm) { this.offsetCm = offsetCm; }
    public int getProtectDir() { return protectDir; }
    public void setProtectDir(int protectDir) { this.protectDir = protectDir; }
    public SignalAspect getAspect() { return aspect; }
    public void setAspect(SignalAspect aspect) { this.aspect = aspect; }
}
