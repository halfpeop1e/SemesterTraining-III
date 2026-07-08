package com.bjtu.railtransit.domain.model;

/**
 * 安全事件
 */
public class SafetyEvent {
    private int trainId;
    private long timestamp;         // 事件发生时间 (ms)
    private String eventType;       // "over_speed" / "eb_triggered" / "brake_insufficient" / "comm_failure" / "degraded_mode"
    private String description;
    private double speedAtEvent;    // 事件时刻速度 (m/s)
    private double positionAtEvent; // 事件时刻位置 (m)

    public SafetyEvent() {}

    public int getTrainId() { return trainId; }
    public void setTrainId(int trainId) { this.trainId = trainId; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public double getSpeedAtEvent() { return speedAtEvent; }
    public void setSpeedAtEvent(double speedAtEvent) { this.speedAtEvent = speedAtEvent; }

    public double getPositionAtEvent() { return positionAtEvent; }
    public void setPositionAtEvent(double positionAtEvent) { this.positionAtEvent = positionAtEvent; }
}
