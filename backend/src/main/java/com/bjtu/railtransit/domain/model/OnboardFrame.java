package com.bjtu.railtransit.domain.model;

import com.bjtu.railtransit.vehicle.dto.SafetyEvent;
import com.bjtu.railtransit.vehicle.dto.SimulationResult;
import com.bjtu.railtransit.vehicle.dto.StationStop;
import com.bjtu.railtransit.vehicle.dto.TrainState;

import java.util.ArrayList;
import java.util.List;

/**
 * 车载前端 WebSocket 消息。每 50ms 推送一帧。
 */
public class OnboardFrame {

    private String type; // frame | finished | error
    private double time;
    private double position;
    private double velocity;
    private double acceleration;
    private String phase;
    private String trainId;
    private Double absolutePosition;
    private Double tractionForce;
    private Double brakeForce;
    private Integer availableMotors;
    private Double resistanceDecel; // 总阻力减速度 m/s²（Davis+坡度+隧道）

    // 摘要（仅 finished 帧携带）
    private Double speedLimit;
    private Double targetStopPosition;
    private String departureState;
    private String fromStationName;
    private String toStationName;
    private Double lineStartPosition;
    private Integer totalStations;
    private Integer completedStops;
    private Double dtPerFrame;
    private List<StationStop> stationStops;
    private List<SafetyEvent> safetyEvents;
    private Double trainMass;

    public OnboardFrame() {}

    public static OnboardFrame fromTrainState(TrainState s) {
        OnboardFrame f = new OnboardFrame();
        f.type = "frame";
        f.trainId = s.getTrainId();
        f.time = s.getTime();
        f.position = s.getPosition();
        f.velocity = s.getVelocity();
        f.acceleration = s.getAcceleration();
        f.phase = s.getPhase() != null ? s.getPhase().name() : null;
        f.absolutePosition = s.getAbsolutePosition();
        f.tractionForce = s.getTractionForce();
        f.brakeForce = s.getBrakeForce();
        f.availableMotors = s.getAvailableMotors();
        f.resistanceDecel = s.getResistanceDecel();
        return f;
    }

    public static OnboardFrame finished(String trainId, SimulationResult result) {
        OnboardFrame f = new OnboardFrame();
        f.type = "finished";
        f.trainId = trainId;
        if (result.getSummary() != null) {
            f.speedLimit = result.getSummary().getSpeedLimit();
            f.targetStopPosition = result.getStopResult() != null
                    ? result.getStopResult().getTargetStopPosition() : null;
            f.departureState = result.getSummary().getDepartureState();
            f.fromStationName = result.getSummary().getFromStationName();
            f.toStationName = result.getSummary().getToStationName();
            f.lineStartPosition = result.getSummary().getLineStartPosition();
            f.totalStations = result.getSummary().getTotalStations();
            f.completedStops = result.getSummary().getCompletedStops();
            f.dtPerFrame = result.getSummary().getDtPerFrame();
            f.trainMass = result.getSummary().getTrainMass();
        }
        if (result.getStationStops() != null) {
            f.stationStops = new ArrayList<>(result.getStationStops());
        }
        if (result.getSafetyEvents() != null) {
            f.safetyEvents = new ArrayList<>(result.getSafetyEvents());
        }
        return f;
    }

    public static OnboardFrame error(String trainId, String message) {
        OnboardFrame f = new OnboardFrame();
        f.type = "error";
        f.trainId = trainId;
        f.phase = message;
        return f;
    }

    // Getters / Setters
    public String getType() { return type; }
    public void setType(String v) { this.type = v; }
    public double getTime() { return time; }
    public void setTime(double v) { this.time = v; }
    public double getPosition() { return position; }
    public void setPosition(double v) { this.position = v; }
    public double getVelocity() { return velocity; }
    public void setVelocity(double v) { this.velocity = v; }
    public double getAcceleration() { return acceleration; }
    public void setAcceleration(double v) { this.acceleration = v; }
    public String getPhase() { return phase; }
    public void setPhase(String v) { this.phase = v; }
    public String getTrainId() { return trainId; }
    public void setTrainId(String v) { this.trainId = v; }
    public Double getAbsolutePosition() { return absolutePosition; }
    public void setAbsolutePosition(Double v) { this.absolutePosition = v; }
    public Double getTractionForce() { return tractionForce; }
    public void setTractionForce(Double v) { this.tractionForce = v; }
    public Double getBrakeForce() { return brakeForce; }
    public void setBrakeForce(Double v) { this.brakeForce = v; }
    public Integer getAvailableMotors() { return availableMotors; }
    public void setAvailableMotors(Integer v) { this.availableMotors = v; }
    public Double getResistanceDecel() { return resistanceDecel; }
    public void setResistanceDecel(Double v) { this.resistanceDecel = v; }
    public Double getSpeedLimit() { return speedLimit; }
    public void setSpeedLimit(Double v) { this.speedLimit = v; }
    public Double getTargetStopPosition() { return targetStopPosition; }
    public void setTargetStopPosition(Double v) { this.targetStopPosition = v; }
    public String getDepartureState() { return departureState; }
    public void setDepartureState(String v) { this.departureState = v; }
    public String getFromStationName() { return fromStationName; }
    public void setFromStationName(String v) { this.fromStationName = v; }
    public String getToStationName() { return toStationName; }
    public void setToStationName(String v) { this.toStationName = v; }
    public Double getLineStartPosition() { return lineStartPosition; }
    public void setLineStartPosition(Double v) { this.lineStartPosition = v; }
    public Integer getTotalStations() { return totalStations; }
    public void setTotalStations(Integer v) { this.totalStations = v; }
    public Integer getCompletedStops() { return completedStops; }
    public void setCompletedStops(Integer v) { this.completedStops = v; }
    public Double getDtPerFrame() { return dtPerFrame; }
    public void setDtPerFrame(Double v) { this.dtPerFrame = v; }
    public List<StationStop> getStationStops() { return stationStops; }
    public void setStationStops(List<StationStop> v) { this.stationStops = v; }
    public List<SafetyEvent> getSafetyEvents() { return safetyEvents; }
    public void setSafetyEvents(List<SafetyEvent> v) { this.safetyEvents = v; }
    public Double getTrainMass() { return trainMass; }
    public void setTrainMass(Double v) { this.trainMass = v; }
}
