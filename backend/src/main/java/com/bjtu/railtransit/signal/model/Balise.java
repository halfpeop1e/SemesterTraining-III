package com.bjtu.railtransit.signal.model;

public class Balise {
    private int id;
    private String hexId;                    // 0x0001
    private String name;                     // FB1/VB1 ...
    private int type;                        // FB固定/WB预告/VB可变/IB填充/DB门
    private int segId;
    private double offsetCm;
    private Integer relatedSignalId;         // 关联信号机（可空）
    private int dir;                         // 作用方向

    public Balise() {}

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getHexId() { return hexId; }
    public void setHexId(String hexId) { this.hexId = hexId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getType() { return type; }
    public void setType(int type) { this.type = type; }
    public int getSegId() { return segId; }
    public void setSegId(int segId) { this.segId = segId; }
    public double getOffsetCm() { return offsetCm; }
    public void setOffsetCm(double offsetCm) { this.offsetCm = offsetCm; }
    public Integer getRelatedSignalId() { return relatedSignalId; }
    public void setRelatedSignalId(Integer relatedSignalId) { this.relatedSignalId = relatedSignalId; }
    public int getDir() { return dir; }
    public void setDir(int dir) { this.dir = dir; }
}
