package com.bjtu.railtransit.signal.domain;

public class TrainState {
    private String trainId;
    private double positionM;            // 车头里程
    private double speedKmh;             // 当前速度
    private double accelerationMps2;     // 当前加速度（m/s²）
    private double lengthM;              // 列车长度
    private Direction direction;         // UP / DOWN
    private double timestamp;            // 仿真时刻

    public TrainState() {}

    public String getTrainId() { return trainId; }
    public void setTrainId(String trainId) { this.trainId = trainId; }
    public double getPositionM() { return positionM; }
    public void setPositionM(double positionM) { this.positionM = positionM; }
    public double getSpeedKmh() { return speedKmh; }
    public void setSpeedKmh(double speedKmh) { this.speedKmh = speedKmh; }
    public double getAccelerationMps2() { return accelerationMps2; }
    public void setAccelerationMps2(double accelerationMps2) { this.accelerationMps2 = accelerationMps2; }
    public double getLengthM() { return lengthM; }
    public void setLengthM(double lengthM) { this.lengthM = lengthM; }
    public Direction getDirection() { return direction; }
    public void setDirection(Direction direction) { this.direction = direction; }
    public double getTimestamp() { return timestamp; }
    public void setTimestamp(double timestamp) { this.timestamp = timestamp; }
}
