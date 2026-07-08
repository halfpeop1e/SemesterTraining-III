package com.bjtu.railtransit.vehicle.dto;

import com.bjtu.railtransit.vehicle.enums.SimulationPhase;

/**
 * 单个仿真时间点的列车状态（SIM 数据，由 service 逐步计算生成）。
 *
 * <p>对应指挥书中的 {@code train_state} 契约：time/position/velocity/acceleration/phase/trainId。</p>
 */
public class TrainState {

    /** 仿真时刻，单位 s。 */
    private double time;

    /** 列车当前位置，单位 m。 */
    private double position;

    /** 当前速度，单位 m/s。 */
    private double velocity;

    /** 当前加速度，单位 m/s2（制动时为负值）。 */
    private double acceleration;

    /** 当前运行阶段。 */
    private SimulationPhase phase;

    /** 列车编号，本轮固定为 "T1"。 */
    private String trainId;

    public TrainState() {
    }

    public TrainState(double time, double position, double velocity, double acceleration,
                       SimulationPhase phase, String trainId) {
        this.time = time;
        this.position = position;
        this.velocity = velocity;
        this.acceleration = acceleration;
        this.phase = phase;
        this.trainId = trainId;
    }

    public double getTime() {
        return time;
    }

    public void setTime(double time) {
        this.time = time;
    }

    public double getPosition() {
        return position;
    }

    public void setPosition(double position) {
        this.position = position;
    }

    public double getVelocity() {
        return velocity;
    }

    public void setVelocity(double velocity) {
        this.velocity = velocity;
    }

    public double getAcceleration() {
        return acceleration;
    }

    public void setAcceleration(double acceleration) {
        this.acceleration = acceleration;
    }

    public SimulationPhase getPhase() {
        return phase;
    }

    public void setPhase(SimulationPhase phase) {
        this.phase = phase;
    }

    public String getTrainId() {
        return trainId;
    }

    public void setTrainId(String trainId) {
        this.trainId = trainId;
    }
}
