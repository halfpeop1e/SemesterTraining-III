package com.bjtu.railtransit.vehicle.protocol704;

public class RealtimeVehicleState {
    private String trainId;
    private long lastUpdateTime;
    private double positionM;
    private double velocityMs;
    private double accelerationMs2;
    private String mode;
    private String lastCommand;
    private String note;

    public RealtimeVehicleState() {
        this.trainId = "T1";
        this.lastUpdateTime = System.currentTimeMillis();
        this.positionM = 0.0;
        this.velocityMs = 0.0;
        this.accelerationMs2 = 0.0;
        this.mode = "MANUAL";
        this.lastCommand = "none";
        this.note = "initialized";
    }

    public String getTrainId() { return trainId; }
    public void setTrainId(String trainId) { this.trainId = trainId; }
    public long getLastUpdateTime() { return lastUpdateTime; }
    public void setLastUpdateTime(long lastUpdateTime) { this.lastUpdateTime = lastUpdateTime; }
    public double getPositionM() { return positionM; }
    public void setPositionM(double positionM) { this.positionM = positionM; }
    public double getVelocityMs() { return velocityMs; }
    public void setVelocityMs(double velocityMs) { this.velocityMs = velocityMs; }
    public double getAccelerationMs2() { return accelerationMs2; }
    public void setAccelerationMs2(double accelerationMs2) { this.accelerationMs2 = accelerationMs2; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getLastCommand() { return lastCommand; }
    public void setLastCommand(String lastCommand) { this.lastCommand = lastCommand; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}