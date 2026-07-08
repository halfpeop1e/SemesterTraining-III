package com.bjtu.railtransit.vehicle.dto;

/**
 * 多站连续仿真中单个途中站的停车记录（新增 DTO）。
 *
 * <p>记录每个经停站的停车结果，包含停车时刻、绝对里程、误差和驻留时间。
 * 最终目标站的停车结果仍由 {@link StopResult} 表示，不影响旧结构。</p>
 */
public class StationStop {

    /** 经停站 id（对应 configs/line-profile.json stations[].id）。 */
    private int stationId;

    /** 经停站中文名。 */
    private String stationName;

    /** 实际停车时刻，单位 s（全程连续 time）。 */
    private double arrivalTime;

    /** 发车时刻，单位 s（= arrivalTime + dwellTime）。 */
    private double departureTime;

    /** 驻留时长，单位 s。 */
    private double dwellTime;

    /** 停车误差（实际停车点 - 该站目标停车点），单位 m。 */
    private double stopError;

    /** 是否在停车窗内（|stopError| <= 0.5m 且末速度 <= 0.1m/s）。 */
    private boolean inWindow;

    /** 该站目标停车绝对里程，单位 m。 */
    private double targetAbsolutePosition;

    /** 实际停车绝对里程，单位 m。 */
    private double actualAbsolutePosition;

    public StationStop() {
    }

    public StationStop(int stationId, String stationName, double arrivalTime, double departureTime,
                        double dwellTime, double stopError, boolean inWindow,
                        double targetAbsolutePosition, double actualAbsolutePosition) {
        this.stationId = stationId;
        this.stationName = stationName;
        this.arrivalTime = arrivalTime;
        this.departureTime = departureTime;
        this.dwellTime = dwellTime;
        this.stopError = stopError;
        this.inWindow = inWindow;
        this.targetAbsolutePosition = targetAbsolutePosition;
        this.actualAbsolutePosition = actualAbsolutePosition;
    }

    public int getStationId() { return stationId; }
    public void setStationId(int stationId) { this.stationId = stationId; }

    public String getStationName() { return stationName; }
    public void setStationName(String stationName) { this.stationName = stationName; }

    public double getArrivalTime() { return arrivalTime; }
    public void setArrivalTime(double arrivalTime) { this.arrivalTime = arrivalTime; }

    public double getDepartureTime() { return departureTime; }
    public void setDepartureTime(double departureTime) { this.departureTime = departureTime; }

    public double getDwellTime() { return dwellTime; }
    public void setDwellTime(double dwellTime) { this.dwellTime = dwellTime; }

    public double getStopError() { return stopError; }
    public void setStopError(double stopError) { this.stopError = stopError; }

    public boolean isInWindow() { return inWindow; }
    public void setInWindow(boolean inWindow) { this.inWindow = inWindow; }

    public double getTargetAbsolutePosition() { return targetAbsolutePosition; }
    public void setTargetAbsolutePosition(double targetAbsolutePosition) { this.targetAbsolutePosition = targetAbsolutePosition; }

    public double getActualAbsolutePosition() { return actualAbsolutePosition; }
    public void setActualAbsolutePosition(double actualAbsolutePosition) { this.actualAbsolutePosition = actualAbsolutePosition; }
}
