package com.bjtu.railtransit.signal.model;

public class TemporarySpeedRestriction {
    private double startM;
    private double endM;
    private double speedLimitKmh;
    private boolean active;

    public TemporarySpeedRestriction() {}

    public double getStartM() { return startM; }
    public void setStartM(double startM) { this.startM = startM; }
    public double getEndM() { return endM; }
    public void setEndM(double endM) { this.endM = endM; }
    public double getSpeedLimitKmh() { return speedLimitKmh; }
    public void setSpeedLimitKmh(double speedLimitKmh) { this.speedLimitKmh = speedLimitKmh; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
