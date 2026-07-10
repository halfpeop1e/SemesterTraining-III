package com.bjtu.railtransit.domain.model;

import java.util.List;

public class StatusReport {
    private String trainId;
    private String deviceId;
    private String sourceType;
    private double timestampSeconds;
    private double positionMeters;
    private double speedKmh;
    private double accelerationMps2;
    private String direction;
    private String currentSegmentId;
    private String currentStationId;
    private String nextStationId;
    private String phase;
    private double delaySeconds;
    private String health;
    private String activeCommandId;
    private String lineId;
    private String routeId;
    private String fromStationId;
    private String toStationId;
    private String fromStationName;
    private String toStationName;
    private String operatingMode;
    private boolean paused;
    /**
     * true means this report may replace the dispatch runtime state. Software HMI
     * monitoring reports use false until the dispatcher explicitly grants control.
     */
    private Boolean authoritative;

    /**
     * 多质点车厢级状态上报（车载系统 → 信号系统 → 中控）。
     * 每节车厢独立上报质量、载客率、车钩力、健康状态。
     */
    private List<CarStatus> carStatuses;

    public String getTrainId() { return trainId; } public void setTrainId(String v) { trainId=v; }
    public String getDeviceId() { return deviceId; } public void setDeviceId(String v) { deviceId=v; }
    public String getSourceType() { return sourceType; } public void setSourceType(String v) { sourceType=v; }
    public double getTimestampSeconds() { return timestampSeconds; } public void setTimestampSeconds(double v) { timestampSeconds=v; }
    public double getPositionMeters() { return positionMeters; } public void setPositionMeters(double v) { positionMeters=v; }
    public double getSpeedKmh() { return speedKmh; } public void setSpeedKmh(double v) { speedKmh=v; }
    public double getAccelerationMps2() { return accelerationMps2; } public void setAccelerationMps2(double v) { accelerationMps2=v; }
    public String getDirection() { return direction; } public void setDirection(String v) { direction=v; }
    public String getCurrentSegmentId() { return currentSegmentId; } public void setCurrentSegmentId(String v) { currentSegmentId=v; }
    public String getCurrentStationId() { return currentStationId; } public void setCurrentStationId(String v) { currentStationId=v; }
    public String getNextStationId() { return nextStationId; } public void setNextStationId(String v) { nextStationId=v; }
    public String getPhase() { return phase; } public void setPhase(String v) { phase=v; }
    public double getDelaySeconds() { return delaySeconds; } public void setDelaySeconds(double v) { delaySeconds=v; }
    public String getHealth() { return health; } public void setHealth(String v) { health=v; }
    public String getActiveCommandId() { return activeCommandId; } public void setActiveCommandId(String v) { activeCommandId=v; }
    public String getLineId() { return lineId; } public void setLineId(String v) { lineId=v; }
    public String getRouteId() { return routeId; } public void setRouteId(String v) { routeId=v; }
    public String getFromStationId() { return fromStationId; } public void setFromStationId(String v) { fromStationId=v; }
    public String getToStationId() { return toStationId; } public void setToStationId(String v) { toStationId=v; }
    public String getFromStationName() { return fromStationName; } public void setFromStationName(String v) { fromStationName=v; }
    public String getToStationName() { return toStationName; } public void setToStationName(String v) { toStationName=v; }
    public String getOperatingMode() { return operatingMode; } public void setOperatingMode(String v) { operatingMode=v; }
    public boolean isPaused() { return paused; } public void setPaused(boolean v) { paused=v; }
    public Boolean getAuthoritative() { return authoritative; } public void setAuthoritative(Boolean v) { authoritative=v; }

    public List<CarStatus> getCarStatuses() { return carStatuses; }
    public void setCarStatuses(List<CarStatus> v) { this.carStatuses = v; }

    /**
     * 车厢状态上报项 —— 车载系统通过报文上报给信号系统/中控。
     */
    public static class CarStatus {
        public int carIndex;
        public String carType;
        public boolean motored;
        public double curbMass;
        public double occupiedMass;
        public double passengerLoadRatio;
        public double positionMeters;
        public double speedKmh;
        public double accelerationKmhs;
        public double couplerForceKN;
        public double gradeResistance;
        public String health;

        public int getCarIndex() { return carIndex; } public void setCarIndex(int v) { carIndex = v; }
        public String getCarType() { return carType; } public void setCarType(String v) { carType = v; }
        public boolean isMotored() { return motored; } public void setMotored(boolean v) { motored = v; }
        public double getCurbMass() { return curbMass; } public void setCurbMass(double v) { curbMass = v; }
        public double getOccupiedMass() { return occupiedMass; } public void setOccupiedMass(double v) { occupiedMass = v; }
        public double getPassengerLoadRatio() { return passengerLoadRatio; } public void setPassengerLoadRatio(double v) { passengerLoadRatio = v; }
        public double getPositionMeters() { return positionMeters; } public void setPositionMeters(double v) { positionMeters = v; }
        public double getSpeedKmh() { return speedKmh; } public void setSpeedKmh(double v) { speedKmh = v; }
        public double getAccelerationKmhs() { return accelerationKmhs; } public void setAccelerationKmhs(double v) { accelerationKmhs = v; }
        public double getCouplerForceKN() { return couplerForceKN; } public void setCouplerForceKN(double v) { couplerForceKN = v; }
        public double getGradeResistance() { return gradeResistance; } public void setGradeResistance(double v) { gradeResistance = v; }
        public String getHealth() { return health; } public void setHealth(String v) { health = v; }
    }
}
