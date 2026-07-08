package com.bjtu.railtransit.domain.model;

import java.util.ArrayList;
import java.util.List;

public class TrainState {

    private String trainId;
    private String trainName;
    private double positionMeters;
    private double speed;
    private String status;
    private int currentStationIndex;
    private int nextStationIndex;
    private String departureTime;
    private double nextStationKm;
    private List<TrainCar> cars;
    private int carCount = 6;
    private double carLength = 19.0;

    public TrainState() {
    }

    public String getTrainId() {
        return trainId;
    }

    public void setTrainId(String trainId) {
        this.trainId = trainId;
    }

    public String getTrainName() {
        return trainName;
    }

    public void setTrainName(String trainName) {
        this.trainName = trainName;
    }

    public double getPositionMeters() {
        return positionMeters;
    }

    public void setPositionMeters(double positionMeters) {
        this.positionMeters = positionMeters;
    }

    public double getSpeed() {
        return speed;
    }

    public void setSpeed(double speed) {
        this.speed = speed;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getCurrentStationIndex() {
        return currentStationIndex;
    }

    public void setCurrentStationIndex(int currentStationIndex) {
        this.currentStationIndex = currentStationIndex;
    }

    public int getNextStationIndex() {
        return nextStationIndex;
    }

    public void setNextStationIndex(int nextStationIndex) {
        this.nextStationIndex = nextStationIndex;
    }

    public String getDepartureTime() {
        return departureTime;
    }

    public void setDepartureTime(String departureTime) {
        this.departureTime = departureTime;
    }

    public double getNextStationKm() {
        return nextStationKm;
    }

    public void setNextStationKm(double nextStationKm) {
        this.nextStationKm = nextStationKm;
    }

    public List<TrainCar> getCars() {
        return cars;
    }

    public void setCars(List<TrainCar> cars) {
        this.cars = cars;
    }

    public int getCarCount() {
        return carCount;
    }

    public void setCarCount(int carCount) {
        this.carCount = carCount;
    }

    public double getCarLength() {
        return carLength;
    }

    public void setCarLength(double carLength) {
        this.carLength = carLength;
    }
}
