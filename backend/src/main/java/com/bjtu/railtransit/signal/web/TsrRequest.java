package com.bjtu.railtransit.signal.web;

/** G3: TSR 创建请求体 */
public class TsrRequest {
    private double startM;
    private double endM;
    private double speedLimitKmh;
    private boolean active;

    public double getStartM() { return startM; }
    public void setStartM(double startM) { this.startM = startM; }
    public double getEndM() { return endM; }
    public void setEndM(double endM) { this.endM = endM; }
    public double getSpeedLimitKmh() { return speedLimitKmh; }
    public void setSpeedLimitKmh(double speedLimitKmh) { this.speedLimitKmh = speedLimitKmh; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
