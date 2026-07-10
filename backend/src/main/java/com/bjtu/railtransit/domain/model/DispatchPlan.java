package com.bjtu.railtransit.domain.model;

import java.util.List;

/**
 * 调度计划 —— 对应总控调度系统的运行计划。
 *
 * 统一数据结构:
 *   - planId: 计划标识
 *   - lineId: 线路标识
 *   - operationMode: 运营模式 (NORMAL / COMPRESS / STRETCH / EMERGENCY)
 *   - headwaySeconds: 计划发车间隔(秒)
 *   - effectiveHeadwaySeconds: 实际上线间隔(秒, 考虑可用车辆约束)
 *   - trainCount: 总列车数
 *   - availableTrainCount: 可用列车数
 *   - requiredTrainCount: 客流所需列车数 (N_required = ceil(T_cycle / headway))
 *   - startStationId / endStationId: 起终点站
 *   - routePattern: 交路模式
 *   - timetableItems: 时刻表条目
 */
public class DispatchPlan {

    private String planId;
    private String lineId;
    private String operationMode;
    private int headwaySeconds = 360;
    private int effectiveHeadwaySeconds;
    private int departureIntervalSeconds;
    private int trainCount;
    private int availableTrainCount;
    private int requiredTrainCount;
    private int startStationId;
    private int endStationId;
    private String routePattern;
    private List<ScheduleEntry> schedule;
    private List<TimetableItem> timetableItems;

    public DispatchPlan() {
    }

    public String getPlanId() { return planId; }
    public void setPlanId(String v) { this.planId = v; }

    public String getLineId() { return lineId; }
    public void setLineId(String lineId) { this.lineId = lineId; }

    public String getOperationMode() { return operationMode; }
    public void setOperationMode(String v) { this.operationMode = v; }

    public int getHeadwaySeconds() { return headwaySeconds; }
    public void setHeadwaySeconds(int headwaySeconds) { this.headwaySeconds = headwaySeconds; }

    public int getEffectiveHeadwaySeconds() { return effectiveHeadwaySeconds; }
    public void setEffectiveHeadwaySeconds(int v) { this.effectiveHeadwaySeconds = v; }

    public int getDepartureIntervalSeconds() { return departureIntervalSeconds; }
    public void setDepartureIntervalSeconds(int departureIntervalSeconds) { this.departureIntervalSeconds = departureIntervalSeconds; }

    public int getTrainCount() { return trainCount; }
    public void setTrainCount(int trainCount) { this.trainCount = trainCount; }

    public int getAvailableTrainCount() { return availableTrainCount; }
    public void setAvailableTrainCount(int v) { this.availableTrainCount = v; }

    public int getRequiredTrainCount() { return requiredTrainCount; }
    public void setRequiredTrainCount(int v) { this.requiredTrainCount = v; }

    public int getStartStationId() { return startStationId; }
    public void setStartStationId(int v) { this.startStationId = v; }

    public int getEndStationId() { return endStationId; }
    public void setEndStationId(int v) { this.endStationId = v; }

    public String getRoutePattern() { return routePattern; }
    public void setRoutePattern(String v) { this.routePattern = v; }

    public List<ScheduleEntry> getSchedule() { return schedule; }
    public void setSchedule(List<ScheduleEntry> schedule) { this.schedule = schedule; }

    public List<TimetableItem> getTimetableItems() { return timetableItems; }
    public void setTimetableItems(List<TimetableItem> v) { this.timetableItems = v; }

    // ── 内嵌类 ──

    public static class ScheduleEntry {
        private String trainId;
        private int stationIndex;
        private double plannedArrivalKm;
        private String plannedDepartureTime;

        public ScheduleEntry() {}

        public String getTrainId() { return trainId; }
        public void setTrainId(String trainId) { this.trainId = trainId; }
        public int getStationIndex() { return stationIndex; }
        public void setStationIndex(int stationIndex) { this.stationIndex = stationIndex; }
        public double getPlannedArrivalKm() { return plannedArrivalKm; }
        public void setPlannedArrivalKm(double plannedArrivalKm) { this.plannedArrivalKm = plannedArrivalKm; }
        public String getPlannedDepartureTime() { return plannedDepartureTime; }
        public void setPlannedDepartureTime(String plannedDepartureTime) { this.plannedDepartureTime = plannedDepartureTime; }
    }

    /**
     * 时刻表条目 —— 统一结构
     */
    public static class TimetableItem {
        private String trainId;
        private String tripId;
        private int stationId;
        private double plannedArrivalTimeSeconds;
        private double plannedDepartureTimeSeconds;
        private double plannedDwellSeconds;
        private double minDwellSeconds;
        private double actualArrivalTimeSeconds;
        private double actualDepartureTimeSeconds;
        private double delaySeconds;

        public TimetableItem() {}

        public String getTrainId() { return trainId; }
        public void setTrainId(String v) { this.trainId = v; }
        public String getTripId() { return tripId; }
        public void setTripId(String v) { this.tripId = v; }
        public int getStationId() { return stationId; }
        public void setStationId(int v) { this.stationId = v; }
        public double getPlannedArrivalTimeSeconds() { return plannedArrivalTimeSeconds; }
        public void setPlannedArrivalTimeSeconds(double v) { this.plannedArrivalTimeSeconds = v; }
        public double getPlannedDepartureTimeSeconds() { return plannedDepartureTimeSeconds; }
        public void setPlannedDepartureTimeSeconds(double v) { this.plannedDepartureTimeSeconds = v; }
        public double getPlannedDwellSeconds() { return plannedDwellSeconds; }
        public void setPlannedDwellSeconds(double v) { this.plannedDwellSeconds = v; }
        public double getMinDwellSeconds() { return minDwellSeconds; }
        public void setMinDwellSeconds(double v) { this.minDwellSeconds = v; }
        public double getActualArrivalTimeSeconds() { return actualArrivalTimeSeconds; }
        public void setActualArrivalTimeSeconds(double v) { this.actualArrivalTimeSeconds = v; }
        public double getActualDepartureTimeSeconds() { return actualDepartureTimeSeconds; }
        public void setActualDepartureTimeSeconds(double v) { this.actualDepartureTimeSeconds = v; }
        public double getDelaySeconds() { return delaySeconds; }
        public void setDelaySeconds(double v) { this.delaySeconds = v; }
    }
}
