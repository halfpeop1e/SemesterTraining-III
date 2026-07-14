package com.bjtu.railtransit.vehicle.dto;

import com.bjtu.railtransit.vehicle.enums.SimulationPhase;

import java.util.List;

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

    /**
     * 车厢状态快照列表（可选，多质点模型下车厢级详情）。
     *
     * <p>多质点仿真时由 service 从 {@code MultiParticleSimulationService.CarStatusSnapshot}
     * 映射填充。null 或空列表表示不启用车厢级展示，单质点模型或不需要车厢级
     * 详情的场景可不填充，不影响现有 JSON 序列化与前端解析（字段对老消费方透明）。</p>
     */
    private List<CarSnapshot> cars;

    /** 当前轮周牵引力 N（正值, 惰行/制动时为0）。 */
    private double tractionForce;

    /** 当前轮周制动力 N（正值, 牵引/惰行时为0）。 */
    private double brakeForce;

    /** 当前可用电机数 (默认16, 故障降级时减少)。 */
    private int availableMotors;

    /** 当前总阻力减速度 m/s²（Davis + 坡度 + 隧道），正值。 */
    private double resistanceDecel;

    public TrainState() {
    }

    public TrainState(double time, double position, double velocity, double acceleration,
                       SimulationPhase phase, String trainId) {
        this(time, position, velocity, acceleration, phase, trainId, 0.0, 0.0, 16);
    }

    public TrainState(double time, double position, double velocity, double acceleration,
                       SimulationPhase phase, String trainId,
                       double tractionForce, double brakeForce, int availableMotors) {
        this.time = time;
        this.position = position;
        this.velocity = velocity;
        this.acceleration = acceleration;
        this.phase = phase;
        this.trainId = trainId;
        this.tractionForce = tractionForce;
        this.brakeForce = brakeForce;
        this.availableMotors = availableMotors;
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

    public List<CarSnapshot> getCars() { return cars; }
    public void setCars(List<CarSnapshot> cars) { this.cars = cars; }

    public double getTractionForce() { return tractionForce; }
    public void setTractionForce(double tractionForce) { this.tractionForce = tractionForce; }

    public double getBrakeForce() { return brakeForce; }
    public void setBrakeForce(double brakeForce) { this.brakeForce = brakeForce; }

    public int getAvailableMotors() { return availableMotors; }
    public void setAvailableMotors(int v) { this.availableMotors = v; }
    public double getResistanceDecel() { return resistanceDecel; }
    public void setResistanceDecel(double v) { this.resistanceDecel = v; }

    /**
     * 车厢状态快照（多质点模型下车厢级详情，对应一次采样帧各车厢的瞬时状态）。
     *
     * <p>字段对 {@link com.bjtu.railtransit.dispatch.MultiParticleSimulationService.CarStatusSnapshot}
     * 做裁剪映射，只保留车载/前端展示需要的子集。单位沿用 CarStatusSnapshot 的约定。</p>
     */
    public static class CarSnapshot {
        /** 车厢序号 (0-based, 车头→车尾)。 */
        private int carIndex;
        /** 车厢类型: Tc/Mp/M。 */
        private String carType;
        /** 是否为动车（提供牵引/电制动）。 */
        private boolean motored;
        /** 当前总质量 (空车+乘客)，单位 kg。 */
        private double occupiedMass;
        /** 载客率 (0.0~1.5, 相对 AW2 定员倍数)。 */
        private double passengerLoadRatio;
        /** 车厢位置 (绝对公里标), 单位 m。 */
        private double positionMeters;
        /** 车厢速度, 单位 km/h。 */
        private double speedKmh;
        /** 车钩力, 单位 kN（受拉为正）。 */
        private double couplerForceKN;

        public CarSnapshot() {
        }

        public CarSnapshot(int carIndex, String carType, boolean motored, double occupiedMass,
                           double passengerLoadRatio, double positionMeters, double speedKmh,
                           double couplerForceKN) {
            this.carIndex = carIndex;
            this.carType = carType;
            this.motored = motored;
            this.occupiedMass = occupiedMass;
            this.passengerLoadRatio = passengerLoadRatio;
            this.positionMeters = positionMeters;
            this.speedKmh = speedKmh;
            this.couplerForceKN = couplerForceKN;
        }

        public int getCarIndex() { return carIndex; }
        public void setCarIndex(int carIndex) { this.carIndex = carIndex; }

        public String getCarType() { return carType; }
        public void setCarType(String carType) { this.carType = carType; }

        public boolean isMotored() { return motored; }
        public void setMotored(boolean motored) { this.motored = motored; }

        public double getOccupiedMass() { return occupiedMass; }
        public void setOccupiedMass(double occupiedMass) { this.occupiedMass = occupiedMass; }

        public double getPassengerLoadRatio() { return passengerLoadRatio; }
        public void setPassengerLoadRatio(double passengerLoadRatio) { this.passengerLoadRatio = passengerLoadRatio; }

        public double getPositionMeters() { return positionMeters; }
        public void setPositionMeters(double positionMeters) { this.positionMeters = positionMeters; }

        public double getSpeedKmh() { return speedKmh; }
        public void setSpeedKmh(double speedKmh) { this.speedKmh = speedKmh; }

        public double getCouplerForceKN() { return couplerForceKN; }
        public void setCouplerForceKN(double couplerForceKN) { this.couplerForceKN = couplerForceKN; }
    }
}
