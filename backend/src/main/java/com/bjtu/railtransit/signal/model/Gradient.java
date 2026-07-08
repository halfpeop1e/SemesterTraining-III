package com.bjtu.railtransit.signal.model;

public class Gradient {
    private int id;
    private int startSegId;
    private double startOffsetCm;
    private int endSegId;
    private double endOffsetCm;
    private double permille;                 // 坡度值 ‰（正=上坡，负=下坡）
    private int tiltDir;                     // 倾斜方向
    private double verticalCurveRadiusCm;    // 竖曲线半径 cm

    public Gradient() {}

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public int getStartSegId() { return startSegId; }
    public void setStartSegId(int v) { this.startSegId = v; }
    public double getStartOffsetCm() { return startOffsetCm; }
    public void setStartOffsetCm(double v) { this.startOffsetCm = v; }
    public int getEndSegId() { return endSegId; }
    public void setEndSegId(int v) { this.endSegId = v; }
    public double getEndOffsetCm() { return endOffsetCm; }
    public void setEndOffsetCm(double v) { this.endOffsetCm = v; }
    public double getPermille() { return permille; }
    public void setPermille(double permille) { this.permille = permille; }
    public int getTiltDir() { return tiltDir; }
    public void setTiltDir(int tiltDir) { this.tiltDir = tiltDir; }
    public double getVerticalCurveRadiusCm() { return verticalCurveRadiusCm; }
    public void setVerticalCurveRadiusCm(double v) { this.verticalCurveRadiusCm = v; }
}
