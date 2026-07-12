package com.bjtu.railtransit.domain.model;

/**
 * 车厢模型 —— 多质点编组中的单个车厢。
 *
 * <p>参考中车/铁科院多质点列车动力学方案:
 *   - 每节车厢独立计算位置、速度、加速度、质量、阻力
 *   - 动车(Mp/M)贡献牵引力/电制动力，拖车(Tc)仅提供基础/空气制动
 *   - 车厢间通过弹性-阻尼车钩耦合
 *   - 载客量按车厢位置分布（中间车拥挤，两端车较少）
 *
 * <p>北京地铁9号线: 6B编组 Tc-Mp-M-M-Mp-Tc
 *   - Tc(0,5): 带司机室拖车, 空重约34t, AW2约50t
 *   - Mp(1,4): 带受电弓动车, 空重约38t, AW2约55t  
 *   - M(2,3): 动车, 空重约36t, AW2约53t
 *   - motored(1,2,3,4) 共4节动车提供牵引/电制动
 */
public class TrainCar {

    /** 车厢序号 (0-based, 车头→车尾) */
    private int carIndex;

    /** 车厢类型: Tc=拖车(带司机室), Mp=动车(带受电弓), M=动车 */
    private String carType;

    /** 是否为动车 (提供牵引力/电制动) */
    private boolean motored;

    /** 空车质量 (kg) */
    private double curbMass;

    /** 当前总质量 = 空车 + 乘客 (kg) */
    private double occupiedMass;

    /** 载客率 (0.0~1.5, 相对AW2定员的倍数) */
    private double passengerLoadRatio;

    /** 车厢位置 m (绝对公里标) */
    private double positionMeters;

    /** 车厢速度 km/h */
    private double speedKmh;

    /** 车厢加速度 km/h/s */
    private double accelerationKmhs;

    /** Davis 阻力系数 A (N/kN) (来源: 列车仿真参数.xlsx) */
    private double davisA = 2.067;

    /** Davis 阻力系数 B (N/kN/(km/h)) */
    private double davisB = 0.01428;

    /** Davis 阻力系数 C (N/kN/(km/h)^2) */
    private double davisC = 0.000377;

    /** 当前坡度阻力 m/s² (坡度阻力对应减速度, 正值=阻碍) */
    private double gradeResistance;

    /** 最大牵引力贡献 N (仅动车非零) */
    private double maxTractiveEffortN;

    /** 最大电制动贡献 N (仅动车非零) */
    private double maxElectricBrakeForceN;

    /** 车钩力 N (正值=受拉, 负值=受压, 来自相邻车厢) */
    private double couplerForceN;

    /** 健康状态: NORMAL / DEGRADED / FAULTY / OFFLINE */
    private String health = "NORMAL";

    /** 车厢长度 m (默认中间车长度, 来源: 列车仿真参数.xlsx M=19.4m, Tc=20.2m) */
    private double lengthMeters = 19.4;

    // ═══════════════════════════════════════════════════════════════

    public TrainCar() {}

    public TrainCar(int carIndex, String carType, boolean motored, double curbMass) {
        this.carIndex = carIndex;
        this.carType = carType;
        this.motored = motored;
        this.curbMass = curbMass;
        this.occupiedMass = curbMass;
        this.passengerLoadRatio = 0.0;
        // 动车单节牵引力: 基于列车仿真参数.xlsx 电机转矩曲线 + GR=7.5
        // 全车16电机, 4动车各4电机, 恒转矩区 F_total = 1042.9×16×7.5/0.46 ≈ 272kN
        // 每节动车贡献 272/4 ≈ 68kN
        if (motored) {
            this.maxTractiveEffortN = 68_000.0;
            this.maxElectricBrakeForceN = 64_000.0;
        }
    }

    // ── 派生量 ──

    /** 速度 m/s */
    public double getSpeedMps() { return speedKmh / 3.6; }

    /** 车厢质心位置 (当前用中心近似) */
    public double getCenterPositionMeters() {
        return positionMeters + lengthMeters / 2.0;
    }

    /** 车厢头部位置 */
    public double getHeadPositionMeters() {
        return positionMeters + lengthMeters;
    }

    // ── Getters / Setters ──

    public int getCarIndex() { return carIndex; }
    public void setCarIndex(int v) { this.carIndex = v; }

    public String getCarType() { return carType; }
    public void setCarType(String v) { this.carType = v; }

    public boolean isMotored() { return motored; }
    public void setMotored(boolean v) { this.motored = v; }

    public double getCurbMass() { return curbMass; }
    public void setCurbMass(double v) { this.curbMass = v; }

    public double getOccupiedMass() { return occupiedMass; }
    public void setOccupiedMass(double v) { this.occupiedMass = v; }

    public double getPassengerLoadRatio() { return passengerLoadRatio; }
    public void setPassengerLoadRatio(double v) { this.passengerLoadRatio = v; }

    public double getPositionMeters() { return positionMeters; }
    public void setPositionMeters(double v) { this.positionMeters = v; }

    public double getSpeedKmh() { return speedKmh; }
    public void setSpeedKmh(double v) { this.speedKmh = v; }

    public double getAccelerationKmhs() { return accelerationKmhs; }
    public void setAccelerationKmhs(double v) { this.accelerationKmhs = v; }

    public double getDavisA() { return davisA; }
    public void setDavisA(double v) { this.davisA = v; }

    public double getDavisB() { return davisB; }
    public void setDavisB(double v) { this.davisB = v; }

    public double getDavisC() { return davisC; }
    public void setDavisC(double v) { this.davisC = v; }

    public double getGradeResistance() { return gradeResistance; }
    public void setGradeResistance(double v) { this.gradeResistance = v; }

    public double getMaxTractiveEffortN() { return maxTractiveEffortN; }
    public void setMaxTractiveEffortN(double v) { this.maxTractiveEffortN = v; }

    public double getMaxElectricBrakeForceN() { return maxElectricBrakeForceN; }
    public void setMaxElectricBrakeForceN(double v) { this.maxElectricBrakeForceN = v; }

    public double getCouplerForceN() { return couplerForceN; }
    public void setCouplerForceN(double v) { this.couplerForceN = v; }

    public String getHealth() { return health; }
    public void setHealth(String v) { this.health = v; }

    public double getLengthMeters() { return lengthMeters; }
    public void setLengthMeters(double v) { this.lengthMeters = v; }
}
