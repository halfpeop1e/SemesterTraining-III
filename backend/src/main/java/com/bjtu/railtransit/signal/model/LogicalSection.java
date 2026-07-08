package com.bjtu.railtransit.signal.model;

public class LogicalSection {
    private int id;
    private String name;                     // 1G-A ...
    private int startSegId;
    private double startOffsetCm;
    private int endSegId;
    private double endOffsetCm;

    public LogicalSection() {}

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getStartSegId() { return startSegId; }
    public void setStartSegId(int v) { this.startSegId = v; }
    public double getStartOffsetCm() { return startOffsetCm; }
    public void setStartOffsetCm(double v) { this.startOffsetCm = v; }
    public int getEndSegId() { return endSegId; }
    public void setEndSegId(int v) { this.endSegId = v; }
    public double getEndOffsetCm() { return endOffsetCm; }
    public void setEndOffsetCm(double v) { this.endOffsetCm = v; }
}
