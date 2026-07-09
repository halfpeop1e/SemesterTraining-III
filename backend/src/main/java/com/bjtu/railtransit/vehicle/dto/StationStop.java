package com.bjtu.railtransit.vehicle.dto;

/**
 * 多站连续仿真中单个途经站的停车记录。
 *
 * <p>注意坐标约定：{@link #segmentStartPosition} 是该区间起点相对于本次仿真
 * 起点站（fromStation）的累计里程；{@link #targetPosition} 是该站相对于 fromStation
 * 的累计里程，等于 segmentStartPosition + 区间里程。</p>
 */
public class StationStop {

    /** 经停站 id（对应 line-profile.json stations[].id）。 */
    private int stationId;

    /** 经停站中文名。 */
    private String stationName;

    /**
     * 该区间起点相对于本次仿真起点站的累计里程，单位 m。
     * 例如 1→4 仿真中，station3 对应的区间起点是 station2 的位置里程。
     */
    private double segmentStartPosition;

    /**
     * 该站目标停车位置（相对于 fromStation 的累计里程），单位 m。
     * 等于 segmentStartPosition + 区间里程。
     */
    private double targetPosition;

    /** 实际停车位置（相对于 fromStation 的累计里程），单位 m。 */
    private double actualPosition;

    /** 停车误差 = actualPosition - targetPosition，单位 m。 */
    private double stopError;

    /** 是否在停车窗内（|stopError|<=0.5 且末速<=0.1）。 */
    private boolean inWindow;

    /** 到站时刻（全程连续 time），单位 s。 */
    private double arrivalTime;

    /** 驻留时长，单位 s（终点站为 0）。 */
    private double dwellTime;

    public StationStop() {
    }

    public StationStop(int stationId, String stationName,
                        double segmentStartPosition, double targetPosition,
                        double actualPosition, double stopError, boolean inWindow,
                        double arrivalTime, double dwellTime) {
        this.stationId = stationId;
        this.stationName = stationName;
        this.segmentStartPosition = segmentStartPosition;
        this.targetPosition = targetPosition;
        this.actualPosition = actualPosition;
        this.stopError = stopError;
        this.inWindow = inWindow;
        this.arrivalTime = arrivalTime;
        this.dwellTime = dwellTime;
    }

    public int getStationId() { return stationId; }
    public void setStationId(int stationId) { this.stationId = stationId; }

    public String getStationName() { return stationName; }
    public void setStationName(String stationName) { this.stationName = stationName; }

    public double getSegmentStartPosition() { return segmentStartPosition; }
    public void setSegmentStartPosition(double segmentStartPosition) { this.segmentStartPosition = segmentStartPosition; }

    public double getTargetPosition() { return targetPosition; }
    public void setTargetPosition(double targetPosition) { this.targetPosition = targetPosition; }

    public double getActualPosition() { return actualPosition; }
    public void setActualPosition(double actualPosition) { this.actualPosition = actualPosition; }

    public double getStopError() { return stopError; }
    public void setStopError(double stopError) { this.stopError = stopError; }

    public boolean isInWindow() { return inWindow; }
    public void setInWindow(boolean inWindow) { this.inWindow = inWindow; }

    public double getArrivalTime() { return arrivalTime; }
    public void setArrivalTime(double arrivalTime) { this.arrivalTime = arrivalTime; }

    public double getDwellTime() { return dwellTime; }
    public void setDwellTime(double dwellTime) { this.dwellTime = dwellTime; }
}
