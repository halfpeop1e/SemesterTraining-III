package com.bjtu.railtransit.vehicle.service;

import com.bjtu.railtransit.vehicle.model.LineProfile;
import com.bjtu.railtransit.vehicle.model.ScenarioConfig;
import com.bjtu.railtransit.vehicle.model.TrainModel;
import org.springframework.stereotype.Component;

/**
 * 后端内置 SI 单位演示配置提供者。
 *
 * <p>阶段 1A 不读取 {@code configs/} 下的 sample JSON，全部使用本类内置的
 * 固定演示参数，避免因组内配置字段缺失（质量、紧急制动、制动响应时间等）
 * 或单位不明确导致阻塞。</p>
 *
 * <p><b>待确认项（记入进度账本）：</b></p>
 * <ul>
 *   <li>车辆质量 40000 kg 为占位默认值，需郭逸晨/组内后续确认是否符合实训车型设定。</li>
 *   <li>限速 20 m/s、最大加速度 1.0 m/s2、常用制动 1.2 m/s2、紧急制动 1.5 m/s2、
 *       制动响应时间 0.5 s 均为参考地铁列车量级的合理默认值，非真实车型数据。</li>
 * </ul>
 */
@Component
public class DemoScenarioProvider {

    /** 线路起点，单位 m。 */
    private static final double START_POSITION = 0.0;

    /** 目标停车点/线路终点，单位 m。 */
    private static final double TARGET_STOP_POSITION = 1200.0;

    /** 区间限速，单位 m/s（约 72 km/h，参考地铁量级）。 */
    private static final double SPEED_LIMIT = 20.0;

    /** 最大加速度，单位 m/s2。 */
    private static final double MAX_ACCELERATION = 1.0;

    /** 常用制动减速度，单位 m/s2。 */
    private static final double NORMAL_BRAKE_DECELERATION = 1.2;

    /** 紧急制动减速度，单位 m/s2。阶段 1A 暂未使用，供后续阶段（SafetyGuard）使用。 */
    private static final double EMERGENCY_BRAKE_DECELERATION = 1.5;

    /** 制动响应时间，单位 s。阶段 1A 简化动力学暂未引入响应延迟，供后续阶段使用。 */
    private static final double BRAKE_RESPONSE_TIME = 0.5;

    /** 车辆质量，单位 kg。默认值为待确认项，见类注释。 */
    private static final double MASS = 40000.0;

    /** 仿真步长，单位 s。 */
    private static final double DT = 0.5;

    /**
     * 构建阶段 1A 使用的内置演示场景配置。
     */
    public ScenarioConfig getDemoScenario() {
        LineProfile lineProfile = new LineProfile(START_POSITION, TARGET_STOP_POSITION, SPEED_LIMIT);
        TrainModel trainModel = new TrainModel(
                MAX_ACCELERATION,
                NORMAL_BRAKE_DECELERATION,
                EMERGENCY_BRAKE_DECELERATION,
                BRAKE_RESPONSE_TIME,
                MASS
        );
        return new ScenarioConfig(lineProfile, trainModel, DT);
    }
}
