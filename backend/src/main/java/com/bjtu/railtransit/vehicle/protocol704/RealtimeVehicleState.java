package com.bjtu.railtransit.vehicle.protocol704;

// local-v1, not validated with real hardware
public class RealtimeVehicleState {
    private String trainId;
    private long lastUpdateTime;
    private double simulationTimeSeconds;  // 仿真时间（秒），与 SimulationService 统一
    private double positionM;
    private double velocityMs;
    private double accelerationMs2;
    private String mode;
    private String lastCommand;
    private String note;
    private String phase;
    private String departureState;
    private boolean doorsClosed;
    private boolean networkFault;
    private boolean emergencyLatched;
    /** byte25 bit0: 信号授权发车后上位机写 1，司机台 ATO 按钮闪烁。 */
    private boolean atoReady;

    private boolean[] tractionCutMask = new boolean[6];
    private long lastTractionCutTime;

    private double[] perCarLoadKg = new double[6];
    private boolean isReversing;
    private String direction = "UP";

    public RealtimeVehicleState() {
        this.trainId = "T1";
        this.lastUpdateTime = System.currentTimeMillis();
        this.positionM = 0.0;
        this.velocityMs = 0.0;
        this.accelerationMs2 = 0.0;
        this.mode = "MANUAL";
        this.lastCommand = "none";
        this.note = "initialized";
        this.phase = "STOPPED";
        this.departureState = "READY_TO_DEPART";
        this.doorsClosed = true;
        this.networkFault = false;
        this.emergencyLatched = false;
        this.tractionCutMask = new boolean[6];
        this.perCarLoadKg = new double[6];
        this.isReversing = false;
        this.direction = "UP";
    }

    public String getTrainId() { return trainId; }
    public void setTrainId(String trainId) { this.trainId = trainId; }
    public long getLastUpdateTime() { return lastUpdateTime; }
    public void setLastUpdateTime(long lastUpdateTime) { this.lastUpdateTime = lastUpdateTime; }
    public double getSimulationTimeSeconds() { return simulationTimeSeconds; }
    public void setSimulationTimeSeconds(double v) { this.simulationTimeSeconds = v; }
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
    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
    public String getDepartureState() { return departureState; }
    public void setDepartureState(String departureState) { this.departureState = departureState; }
    public boolean isDoorsClosed() { return doorsClosed; }
    public void setDoorsClosed(boolean doorsClosed) { this.doorsClosed = doorsClosed; }
    public boolean isNetworkFault() { return networkFault; }
    public void setNetworkFault(boolean networkFault) { this.networkFault = networkFault; }
    public boolean isEmergencyLatched() { return emergencyLatched; }
    public void setEmergencyLatched(boolean emergencyLatched) { this.emergencyLatched = emergencyLatched; }
    public boolean isAtoReady() { return atoReady; }
    public void setAtoReady(boolean atoReady) { this.atoReady = atoReady; }

    public boolean[] getTractionCutMask() { return tractionCutMask; }
    public void setTractionCutMask(boolean[] tractionCutMask) {
        this.tractionCutMask = tractionCutMask != null ? tractionCutMask.clone() : new boolean[6];
    }
    public long getLastTractionCutTime() { return lastTractionCutTime; }
    public void setLastTractionCutTime(long lastTractionCutTime) { this.lastTractionCutTime = lastTractionCutTime; }

    public double[] getPerCarLoadKg() { return perCarLoadKg; }
    public void setPerCarLoadKg(double[] perCarLoadKg) {
        this.perCarLoadKg = perCarLoadKg != null ? perCarLoadKg.clone() : new double[6];
    }
    public boolean isReversing() { return isReversing; }
    public void setReversing(boolean reversing) { isReversing = reversing; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
}
