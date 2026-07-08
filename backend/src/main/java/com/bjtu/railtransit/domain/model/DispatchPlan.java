package com.bjtu.railtransit.domain.model;

import java.util.List;

public class DispatchPlan {

    private String lineId;
    private int headwaySeconds = 360;
    private int departureIntervalSeconds;
    private int trainCount;
    private List<ScheduleEntry> schedule;

    public DispatchPlan() {
    }

    public String getLineId() {
        return lineId;
    }

    public void setLineId(String lineId) {
        this.lineId = lineId;
    }

    public int getHeadwaySeconds() {
        return headwaySeconds;
    }

    public void setHeadwaySeconds(int headwaySeconds) {
        this.headwaySeconds = headwaySeconds;
    }

    public int getDepartureIntervalSeconds() {
        return departureIntervalSeconds;
    }

    public void setDepartureIntervalSeconds(int departureIntervalSeconds) {
        this.departureIntervalSeconds = departureIntervalSeconds;
    }

    public int getTrainCount() {
        return trainCount;
    }

    public void setTrainCount(int trainCount) {
        this.trainCount = trainCount;
    }

    public List<ScheduleEntry> getSchedule() {
        return schedule;
    }

    public void setSchedule(List<ScheduleEntry> schedule) {
        this.schedule = schedule;
    }

    public static class ScheduleEntry {
        private String trainId;
        private int stationIndex;
        private double plannedArrivalKm;
        private String plannedDepartureTime;

        public ScheduleEntry() {
        }

        public String getTrainId() {
            return trainId;
        }

        public void setTrainId(String trainId) {
            this.trainId = trainId;
        }

        public int getStationIndex() {
            return stationIndex;
        }

        public void setStationIndex(int stationIndex) {
            this.stationIndex = stationIndex;
        }

        public double getPlannedArrivalKm() {
            return plannedArrivalKm;
        }

        public void setPlannedArrivalKm(double plannedArrivalKm) {
            this.plannedArrivalKm = plannedArrivalKm;
        }

        public String getPlannedDepartureTime() {
            return plannedDepartureTime;
        }

        public void setPlannedDepartureTime(String plannedDepartureTime) {
            this.plannedDepartureTime = plannedDepartureTime;
        }
    }
}
