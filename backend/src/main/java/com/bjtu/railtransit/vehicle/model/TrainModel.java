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

    public TrainModel(double maxAcceleration,
                       double normalBrakeDeceleration,
                       double emergencyBrakeDeceleration,
                       double brakeResponseTime,
                       double mass) {
        this.maxAcceleration = maxAcceleration;
        this.normalBrakeDeceleration = normalBrakeDeceleration;
        this.emergencyBrakeDeceleration = emergencyBrakeDeceleration;
        this.brakeResponseTime = brakeResponseTime;
        this.mass = mass;
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
}
