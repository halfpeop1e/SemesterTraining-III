package com.bjtu.railtransit.domain.model;

import java.util.List;

public class SimulationSnapshot {

    private double simulationTime;
    private String simTimeFormatted;
    private int totalTrains;
    private int activeTrains;
    private List<TrainState> trains;
    private List<HeadwayInfo> headways;
    private List<TrainCommand> commands;
    private List<TrainPositionPoint> positionHistory;
    private List<StationArrival> stationArrivals;
    private double totalEnergyKwh;

    public SimulationSnapshot() {
    }

    public double getSimulationTime() {
        return simulationTime;
    }

    public void setSimulationTime(double simulationTime) {
        this.simulationTime = simulationTime;
    }

    public String getSimTimeFormatted() {
        return simTimeFormatted;
    }

    public void setSimTimeFormatted(String simTimeFormatted) {
        this.simTimeFormatted = simTimeFormatted;
    }

    public int getTotalTrains() {
        return totalTrains;
    }

    public void setTotalTrains(int totalTrains) {
        this.totalTrains = totalTrains;
    }

    public int getActiveTrains() {
        return activeTrains;
    }

    public void setActiveTrains(int activeTrains) {
        this.activeTrains = activeTrains;
    }

    public List<TrainState> getTrains() {
        return trains;
    }

    public void setTrains(List<TrainState> trains) {
        this.trains = trains;
    }

    public List<HeadwayInfo> getHeadways() {
        return headways;
    }

    public void setHeadways(List<HeadwayInfo> headways) {
        this.headways = headways;
    }

    public List<TrainCommand> getCommands() {
        return commands;
    }

    public void setCommands(List<TrainCommand> commands) {
        this.commands = commands;
    }

    public List<TrainPositionPoint> getPositionHistory() {
        return positionHistory;
    }

    public void setPositionHistory(List<TrainPositionPoint> positionHistory) {
        this.positionHistory = positionHistory;
    }

    public List<StationArrival> getStationArrivals() {
        return stationArrivals;
    }

    public void setStationArrivals(List<StationArrival> stationArrivals) {
        this.stationArrivals = stationArrivals;
    }

    public double getTotalEnergyKwh() {
        return totalEnergyKwh;
    }

    public void setTotalEnergyKwh(double totalEnergyKwh) {
        this.totalEnergyKwh = totalEnergyKwh;
    }

    public static class HeadwayInfo {
        private String fromTrainId;
        private String toTrainId;
        private double distanceMeters;
        private double timeSeconds;
        private String status;

        public HeadwayInfo() {
        }

        public String getFromTrainId() {
            return fromTrainId;
        }

        public void setFromTrainId(String fromTrainId) {
            this.fromTrainId = fromTrainId;
        }

        public String getToTrainId() {
            return toTrainId;
        }

        public void setToTrainId(String toTrainId) {
            this.toTrainId = toTrainId;
        }

        public double getDistanceMeters() {
            return distanceMeters;
        }

        public void setDistanceMeters(double distanceMeters) {
            this.distanceMeters = distanceMeters;
        }

        public double getTimeSeconds() {
            return timeSeconds;
        }

        public void setTimeSeconds(double timeSeconds) {
            this.timeSeconds = timeSeconds;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    public static class TrainCommand {
        private String trainId;
        private String commandType;
        private double targetValue;
        private String reason;

        public TrainCommand() {
        }

        public String getTrainId() {
            return trainId;
        }

        public void setTrainId(String trainId) {
            this.trainId = trainId;
        }

        public String getCommandType() {
            return commandType;
        }

        public void setCommandType(String commandType) {
            this.commandType = commandType;
        }

        public double getTargetValue() {
            return targetValue;
        }

        public void setTargetValue(double targetValue) {
            this.targetValue = targetValue;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }

    public static class TrainPositionPoint {
        private String trainId;
        private double timeSeconds;
        private double positionKm;

        public TrainPositionPoint() {
        }

        public String getTrainId() {
            return trainId;
        }

        public void setTrainId(String trainId) {
            this.trainId = trainId;
        }

        public double getTimeSeconds() {
            return timeSeconds;
        }

        public void setTimeSeconds(double timeSeconds) {
            this.timeSeconds = timeSeconds;
        }

        public double getPositionKm() {
            return positionKm;
        }

        public void setPositionKm(double positionKm) {
            this.positionKm = positionKm;
        }
    }

    public static class StationArrival {
        private String trainId;
        private String stationName;
        private int stationIndex;
        private double arrivalTimeSeconds;
        private double departureTimeSeconds;
        private double dwellSeconds;

        public StationArrival() {
        }

        public String getTrainId() {
            return trainId;
        }

        public void setTrainId(String trainId) {
            this.trainId = trainId;
        }

        public String getStationName() {
            return stationName;
        }

        public void setStationName(String stationName) {
            this.stationName = stationName;
        }

        public int getStationIndex() {
            return stationIndex;
        }

        public void setStationIndex(int stationIndex) {
            this.stationIndex = stationIndex;
        }

        public double getArrivalTimeSeconds() {
            return arrivalTimeSeconds;
        }

        public void setArrivalTimeSeconds(double arrivalTimeSeconds) {
            this.arrivalTimeSeconds = arrivalTimeSeconds;
        }

        public double getDepartureTimeSeconds() {
            return departureTimeSeconds;
        }

        public void setDepartureTimeSeconds(double departureTimeSeconds) {
            this.departureTimeSeconds = departureTimeSeconds;
        }

        public double getDwellSeconds() {
            return dwellSeconds;
        }

        public void setDwellSeconds(double dwellSeconds) {
            this.dwellSeconds = dwellSeconds;
        }
    }
}
