package com.bjtu.railtransit.vehicle.dto;

import com.bjtu.railtransit.vehicle.enums.SimulationPhase;

/**
 * 单个仿真时间点的列车状态（SIM 数据，由 service 逐步计算生成）。
 *
 * <p>坐标约定（本轮新增说明）：</p>
 * <ul>
 *   <li>{@link #position}：从本次选择的起点站开始的<b>连续累计相对里程</b>。
 *       多站仿真中跨越中间站时，position 不归零，始终等于"已走过的相对里程"。
 *       DriverCabView 的 distanceToTarget 依赖此字段，必须与
 *       {@link com.bjtu.railtransit.vehicle.dto.StopResult#getTargetStopPosition()}
 *       使用同一坐标系（均为从 fromStation 出发的累计距离）。</li>
 *   <li>{@link #absolutePosition}：全线绝对里程（可选）。
 *       等于 summary.lineStartPosition + position，由 service 填充，
 *       LineRunView 优先使用此字段在全线地图上定位列车。</li>
 * </ul>
 */
public class TrainState {

    /** 仿真时刻，单位 s（多站时全程连续递增）。 */
    private double time;

    /**
     * 列车当前位置，单位 m。
     *
     * <p><b>多站约定：从本次选择的起点站（fromStation）出发的连续累计相对里程，
     * 跨越中间站时不归零。</b>单站时等于区间内相对里程（0 → runDistanceM），
     * 与原有行为完全一致。</p>
     */
    private double position;

    /** 当前速度，单位 m/s。 */
    private double velocity;

    /** 当前加速度，单位 m/s2（制动时为负值）。 */
    private double acceleration;

    /** 当前运行阶段（含 DWELL 驻留阶段）。 */
    private SimulationPhase phase;

    /** 列车编号，本轮固定为 "T1"。 */
    private String trainId;

    /**
     * 全线绝对里程，单位 m（可选）。
     *
     * <p>等于 summary.lineStartPosition + position。多站时此字段单调不减，
     * LineRunView 优先使用此字段在全线地图上定位列车；null 时回退为
     * positionOffset + position。</p>
     */
    private Double absolutePosition;

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
}
