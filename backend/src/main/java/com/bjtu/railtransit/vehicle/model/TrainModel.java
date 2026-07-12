package com.bjtu.railtransit.vehicle.model;

/**
 * 车型静态配置（STATIC 数据）。
 *
 * <p>参数全部来源于"列车仿真参数.xlsx"真实车型数据，统一 SI 单位。</p>
 */
public class TrainModel {

    /** 最大加速度，单位 m/s2。 */
    private final double maxAcceleration;

    /** 常用制动减速度，单位 m/s2（取正值表示减速能力大小）。 */
    private final double normalBrakeDeceleration;

    /** 紧急制动减速度，单位 m/s2（取正值表示减速能力大小）。 */
    private final double emergencyBrakeDeceleration;

    /** 制动响应时间，单位 s：下达制动指令到减速度真正生效之间的延迟。 */
    private final double brakeResponseTime;

    /** 车辆质量，单位 kg。阶段 1A 动力学未直接使用质量做力学计算，先随配置携带，供后续阶段（如阻力、能耗）使用。 */
    private final double mass;

    /**
     * Davis 基本阻力公式 w0 = davisA + davisB*v + davisC*v^2 (N/kN, v单位km/h)
     * 系数来源于"列车仿真参数.xlsx" Davis公式: R[N]=6.4M+130n+0.14Mv+[0.046+0.0065(N-1)]Av²
     */
    private final double davisA;

    /** Davis 阻力公式线性项系数（对应 km/h 基准，见类注释换算说明）。 */
    private final double davisB;

    /** Davis 阻力公式二次项系数（对应 km/h 基准，见类注释换算说明）。 */
    private final double davisC;

    public TrainModel(double maxAcceleration,
                       double normalBrakeDeceleration,
                       double emergencyBrakeDeceleration,
                       double brakeResponseTime,
                       double mass) {
        this(maxAcceleration, normalBrakeDeceleration, emergencyBrakeDeceleration,
                brakeResponseTime, mass, 0.0, 0.0, 0.0);
    }

    public TrainModel(double maxAcceleration,
                       double normalBrakeDeceleration,
                       double emergencyBrakeDeceleration,
                       double brakeResponseTime,
                       double mass,
                       double davisA,
                       double davisB,
                       double davisC) {
        this.maxAcceleration = maxAcceleration;
        this.normalBrakeDeceleration = normalBrakeDeceleration;
        this.emergencyBrakeDeceleration = emergencyBrakeDeceleration;
        this.brakeResponseTime = brakeResponseTime;
        this.mass = mass;
        this.davisA = davisA;
        this.davisB = davisB;
        this.davisC = davisC;
    }

    public double getMaxAcceleration() {
        return maxAcceleration;
    }

    public double getNormalBrakeDeceleration() {
        return normalBrakeDeceleration;
    }

    public double getEmergencyBrakeDeceleration() {
        return emergencyBrakeDeceleration;
    }

    public double getBrakeResponseTime() {
        return brakeResponseTime;
    }

    public double getMass() {
        return mass;
    }

    public double getDavisA() {
        return davisA;
    }

    public double getDavisB() {
        return davisB;
    }

    public double getDavisC() {
        return davisC;
    }
}
