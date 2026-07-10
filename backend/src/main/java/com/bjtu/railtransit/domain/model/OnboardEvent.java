package com.bjtu.railtransit.domain.model;

public class OnboardEvent {
    private String eventId;
    private String trainId;
    private String eventType;
    private double timestampSeconds;
    private double positionMeters;
    private double speedKmh;
    private String severity;
    private String details;

    public String getEventId() { return eventId; } public void setEventId(String v) { eventId=v; }
    public String getTrainId() { return trainId; } public void setTrainId(String v) { trainId=v; }
    public String getEventType() { return eventType; } public void setEventType(String v) { eventType=v; }
    public double getTimestampSeconds() { return timestampSeconds; } public void setTimestampSeconds(double v) { timestampSeconds=v; }
    public double getPositionMeters() { return positionMeters; } public void setPositionMeters(double v) { positionMeters=v; }
    public double getSpeedKmh() { return speedKmh; } public void setSpeedKmh(double v) { speedKmh=v; }
    public String getSeverity() { return severity; } public void setSeverity(String v) { severity=v; }
    public String getDetails() { return details; } public void setDetails(String v) { details=v; }
}
