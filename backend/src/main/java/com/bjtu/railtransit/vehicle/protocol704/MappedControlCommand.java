package com.bjtu.railtransit.vehicle.protocol704;

public class MappedControlCommand {
    private String command;
    private double levelPercent;
    private double targetDecel;
    private boolean verified;
    private String note;
    private int triggerByteOffset;
    private int triggerByteValue;
    private int directionHandle;
    private int masterHandle;
    private int tractionLevelRaw;
    private int brakeLevelRaw;
    /** local-v1 internal semantic; PLC bit meaning awaits formal 704 confirmation. */
    private String direction;

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public double getLevelPercent() { return levelPercent; }
    public void setLevelPercent(double levelPercent) { this.levelPercent = levelPercent; }
    public double getTargetDecel() { return targetDecel; }
    public void setTargetDecel(double targetDecel) { this.targetDecel = targetDecel; }
    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public int getTriggerByteOffset() { return triggerByteOffset; }
    public void setTriggerByteOffset(int triggerByteOffset) { this.triggerByteOffset = triggerByteOffset; }
    public int getTriggerByteValue() { return triggerByteValue; }
    public void setTriggerByteValue(int triggerByteValue) { this.triggerByteValue = triggerByteValue; }
    public int getDirectionHandle() { return directionHandle; }
    public void setDirectionHandle(int directionHandle) { this.directionHandle = directionHandle; }
    public int getMasterHandle() { return masterHandle; }
    public void setMasterHandle(int masterHandle) { this.masterHandle = masterHandle; }
    public int getTractionLevelRaw() { return tractionLevelRaw; }
    public void setTractionLevelRaw(int tractionLevelRaw) { this.tractionLevelRaw = tractionLevelRaw; }
    public int getBrakeLevelRaw() { return brakeLevelRaw; }
    public void setBrakeLevelRaw(int brakeLevelRaw) { this.brakeLevelRaw = brakeLevelRaw; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
}
