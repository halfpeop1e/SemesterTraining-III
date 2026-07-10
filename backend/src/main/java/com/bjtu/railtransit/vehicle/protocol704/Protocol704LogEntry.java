package com.bjtu.railtransit.vehicle.protocol704;

import java.time.Instant;
import java.util.Map;

public class Protocol704LogEntry {

    private String trainId;
    private int port;
    private String direction;
    private long timestamp;
    private String timestampFormatted;
    private String rawHex;
    private int frameLength;
    private Map<String, Object> parsedFields;
    private MappedControlCommand mappedCommand;
    private String source = "HARDWARE";
    private boolean verified;
    private String note;

    public Protocol704LogEntry() {
        this.timestamp = Instant.now().toEpochMilli();
        this.timestampFormatted = Instant.now().toString();
    }

    public String getTrainId() { return trainId; }
    public void setTrainId(String trainId) { this.trainId = trainId; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
        this.timestampFormatted = Instant.ofEpochMilli(timestamp).toString();
    }

    public String getTimestampFormatted() { return timestampFormatted; }
    public void setTimestampFormatted(String timestampFormatted) { this.timestampFormatted = timestampFormatted; }

    public String getRawHex() { return rawHex; }
    public void setRawHex(String rawHex) { this.rawHex = rawHex; }

    public int getFrameLength() { return frameLength; }
    public void setFrameLength(int frameLength) { this.frameLength = frameLength; }

    public Map<String, Object> getParsedFields() { return parsedFields; }
    public void setParsedFields(Map<String, Object> parsedFields) { this.parsedFields = parsedFields; }

    public MappedControlCommand getMappedCommand() { return mappedCommand; }
    public void setMappedCommand(MappedControlCommand mappedCommand) { this.mappedCommand = mappedCommand; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}