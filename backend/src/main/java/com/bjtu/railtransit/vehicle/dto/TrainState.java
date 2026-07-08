package com.bjtu.railtransit.vehicle.dto;

import com.bjtu.railtransit.vehicle.enums.SimulationPhase;

/**
 * 单个仿真时间点的列车状态（SIM 数据，由 service 逐步计算生成）。
 *
 * <p>对应指挥书中的 {@code train_state} 契约：time/position/velocity/acceleration/phase/trainId。
 * 多站连续仿真新增：absolutePosition / segmentIndex / stationId / stationName（均为可选）。</p>
 */
public class TrainState {

    /** 仿真时刻，单位 s（多站时全程连续递增）。 */
    private double time;

    /**
     * 区间内相对位置，单位 m（0 → 本区间里程）。
     * 多站时每进入新区间从 0 重置。DriverCabView 继续使用此字段，不受影响。
     */
    private double position;

    /** 当前速度，单位 m/s。 */
    private double velocity;

    /** 当前加速度，单位 m/s2（制动时为负值）。 */
    private double acceleration;

    /** 当前运行阶段（含多站新增的 DWELL 驻留阶段）。 */
    private SimulationPhase phase;

    /** 列车编号，本轮固定为 "T1"。 */
    private String trainId;

    // ---- 多站连续仿真新增字段（均为可选，单区间仿真时为 null/0）----

    /**
     * 列车全线绝对里程，单位 m（多站时连续递增）。
     * = fromStation.km*1000 + segmentOffset + position_in_segment
     * LineRunView 优先使用此字段定位列车。
     */
    private Double absolutePosition;

    /**
     * 当前所在区间编号（0-based）。
     * 单区间仿真时为 0；多站仿真时 0=第一段，1=第二段，以此类推。
     */
    private Integer segmentIndex;

    /** 当前所在或即将到达的站点 id（多站仿真填充，单区间为 null）。 */
    private Integer stationId;

    /** 当前所在或即将到达的站点名称（中文，多站仿真填充，单区间为 null）。 */
    private String stationName;

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

    public double getTime() { return time; }
    public void setTime(double time) { this.time = time; }

    public double getPosition() { return position; }
    public void setPosition(double position) { this.position = position; }

    public double getVelocity() { return velocity; }
    public void setVelocity(double velocity) { this.velocity = velocity; }

    public double getAcceleration() { return acceleration; }
    public void setAcceleration(double acceleration) { this.acceleration = acceleration; }

    public SimulationPhase getPhase() { return phase; }
    public void setPhase(SimulationPhase phase) { this.phase = phase; }

    public String getTrainId() { return trainId; }
    public void setTrainId(String trainId) { this.trainId = trainId; }

    public Double getAbsolutePosition() { return absolutePosition; }
    public void setAbsolutePosition(Double absolutePosition) { this.absolutePosition = absolutePosition; }

    public Integer getSegmentIndex() { return segmentIndex; }
    public void setSegmentIndex(Integer segmentIndex) { this.segmentIndex = segmentIndex; }

    public Integer getStationId() { return stationId; }
    public void setStationId(Integer stationId) { this.stationId = stationId; }

    public String getStationName() { return stationName; }
    public void setStationName(String stationName) { this.stationName = stationName; }
}
