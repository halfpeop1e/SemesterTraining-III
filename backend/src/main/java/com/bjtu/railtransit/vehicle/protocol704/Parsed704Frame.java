package com.bjtu.railtransit.vehicle.protocol704;

import java.util.LinkedHashMap;
import java.util.Map;

public class Parsed704Frame {
    private int frameLength;
    private Map<String, Object> fields = new LinkedHashMap<>();
    private boolean hasUnverifiedFields = false;
    private String note = "";
    private MappedControlCommand mappedCommand;
    private Double rawSpeedCms;
    private Double rawDistanceCm;

    public int getFrameLength() { return frameLength; }
    public void setFrameLength(int frameLength) { this.frameLength = frameLength; }
    public Map<String, Object> getFields() { return fields; }
    public void setFields(Map<String, Object> fields) { this.fields = fields; }
    public boolean isHasUnverifiedFields() { return hasUnverifiedFields; }
    public void setHasUnverifiedFields(boolean hasUnverifiedFields) { this.hasUnverifiedFields = hasUnverifiedFields; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public MappedControlCommand getMappedCommand() { return mappedCommand; }
    public void setMappedCommand(MappedControlCommand mappedCommand) { this.mappedCommand = mappedCommand; }
    public Double getRawSpeedCms() { return rawSpeedCms; }
    public void setRawSpeedCms(Double rawSpeedCms) { this.rawSpeedCms = rawSpeedCms; }
    public Double getRawDistanceCm() { return rawDistanceCm; }
    public void setRawDistanceCm(Double rawDistanceCm) { this.rawDistanceCm = rawDistanceCm; }
}