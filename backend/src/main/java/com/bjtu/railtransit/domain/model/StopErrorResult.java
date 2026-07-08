package com.bjtu.railtransit.domain.model;

/**
 * 单站停站误差结果
 */
public class StopErrorResult {
    private int stationId;
    private String stationName;
    private String direction;            // "up" / "down"
    private double targetPosition;       // 目标停车位置 (m)
    private double actualPosition;       // 实际停车位置 (m)
    private double error;                // 误差 (m)
    private String status;               // "in_window" / "over" / "under"

    public StopErrorResult() {}

    public int getStationId() { return stationId; }
    public void setStationId(int stationId) { this.stationId = stationId; }

    public String getStationName() { return stationName; }
    public void setStationName(String stationName) { this.stationName = stationName; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public double getTargetPosition() { return targetPosition; }
    public void setTargetPosition(double targetPosition) { this.targetPosition = targetPosition; }

    public double getActualPosition() { return actualPosition; }
    public void setActualPosition(double actualPosition) { this.actualPosition = actualPosition; }

    public double getError() { return error; }
    public void setError(double error) { this.error = error; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
