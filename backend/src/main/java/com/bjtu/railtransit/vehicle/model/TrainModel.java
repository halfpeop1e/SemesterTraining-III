package com.bjtu.railtransit.vehicle.model;

/**
 * 车型静态配置（STATIC 数据）。
 *
 * <p>阶段 1A 使用后端内置演示车型参数，统一 SI 单位。当前组内
 * {@code configs/train-model.sample.json} 缺少质量、常用制动、紧急制动、
 * 制动响应时间等字段，因此本轮由后端内置默认值补齐，具体默认值是否合理
 * 记为进度账本待确认项，不代表已获得郭逸晨最终确认。</p>
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
     * Davis 基本阻力公式 w0 = davisA + davisB*v + davisC*v^2 (N/kN) 的三个系数（阶段 2 新增）。
     *
     * <p>公式形式本身有城轨/铁道列车牵引计算教材依据（戴维斯公式的中国工程教学版本），
     * 但本类携带的具体数值是工程假设量级，不是针对某个真实车型的实测系数，也不来自
     * {@code 704系统协议汇总.docx} 或 {@code 线路数据(1).xls}（这两个文件不含车辆阻力
     * 系数、迎风面积等参数）。原始公式速度单位是 km/h，本项目内部统一 SI（m/s），
     * 换算关系与具体取值详见 {@link com.bjtu.railtransit.vehicle.service.DemoScenarioProvider}
     * 的类注释。</p>
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
