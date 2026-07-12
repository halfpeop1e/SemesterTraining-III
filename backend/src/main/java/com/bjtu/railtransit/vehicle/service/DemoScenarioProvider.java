package com.bjtu.railtransit.vehicle.service;

import com.bjtu.railtransit.vehicle.model.GradeSegment;
import com.bjtu.railtransit.vehicle.model.LineProfile;
import com.bjtu.railtransit.vehicle.model.ScenarioConfig;
import com.bjtu.railtransit.vehicle.model.TrainModel;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 后端内置 SI 单位演示配置提供者。
 *
 * <p>参数全部来源于"列车仿真参数.xlsx"，包括 Davis 阻力系数、列车质量、
 * 加减速度、传动比等。阶段 1A 使用固定演示线路（1200m），
 * 阶段 2 在此基础上新增坡度段演示配置。</p>
 */
@Component
public class DemoScenarioProvider {

    /** 线路起点，单位 m。 */
    private static final double START_POSITION = 0.0;

    /** 目标停车点/线路终点，单位 m。 */
    private static final double TARGET_STOP_POSITION = 1200.0;

    /** 区间限速，单位 m/s（约 80 km/h）。 */
    private static final double SPEED_LIMIT = 22.2;

    /** 最大加速度，单位 m/s2 (来源: 列车仿真参数.xlsx, GR=7.5时F_max/M≈1.21m/s²)。 */
    private static final double MAX_ACCELERATION = 1.2;

    /** 常用制动减速度，单位 m/s2 (来源: 列车仿真参数.xlsx, GR=7.5时F_brake/M≈1.13m/s²)。 */
    private static final double NORMAL_BRAKE_DECELERATION = 1.1;

    /** 紧急制动减速度，单位 m/s2。 */
    private static final double EMERGENCY_BRAKE_DECELERATION = 1.5;

    /** 制动响应时间，单位 s。 */
    private static final double BRAKE_RESPONSE_TIME = 0.5;

    /** 列车总质量，单位 kg (来源: 列车仿真参数.xlsx AW0空车 Tc:34500×2 + M:39000×4)。 */
    private static final double MASS = 225_000.0;

    /** Davis 阻力公式常数项，单位 N/kN (来源: 列车仿真参数.xlsx R[N]=6.4M+130n+...)。 */
    private static final double DAVIS_A = 2.067;

    /** Davis 阻力公式线性项系数，单位 N/kN/(km/h)。 */
    private static final double DAVIS_B = 0.01428;

    /** Davis 阻力公式二次项系数，单位 N/kN/(km/h)²。 */
    private static final double DAVIS_C = 0.000377;

    /** 仿真步长（对外采样间隔），单位 s。 */
    private static final double DT = 0.5;

    /**
     * 构建阶段 2 使用的内置演示场景配置（含 Davis 阻力与坡度段）。
     */
    public ScenarioConfig getDemoScenario() {
        LineProfile lineProfile = new LineProfile(
                START_POSITION,
                TARGET_STOP_POSITION,
                SPEED_LIMIT,
                Arrays.asList(
                        new GradeSegment(0.0, 800.0, 0.0),
                        new GradeSegment(800.0, TARGET_STOP_POSITION, 0.003)
                )
        );
        TrainModel trainModel = new TrainModel(
                MAX_ACCELERATION,
                NORMAL_BRAKE_DECELERATION,
                EMERGENCY_BRAKE_DECELERATION,
                BRAKE_RESPONSE_TIME,
                MASS,
                DAVIS_A,
                DAVIS_B,
                DAVIS_C
        );
        return new ScenarioConfig(lineProfile, trainModel, DT);
    }

    /**
     * 构建一个不含 Davis 阻力、不含坡度的"零阻力"演示场景，仅供测试对照使用
     * （验证阻力/坡度确实进入了加速度计算，通过与含阻力场景的曲线对比来自证）。
     */
    public ScenarioConfig getZeroResistanceDemoScenario() {
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

    /**
     * 构建一个含 Davis 阻力但不含坡度段的演示场景，仅供测试对照使用
     * （用于隔离验证"坡度阻力"这一项本身是否进入了加速度计算，
     * 与 {@link #getDemoScenario()}（含坡度）对比，两者唯一差异是坡度段）。
     */
    public ScenarioConfig getDemoScenarioWithoutGrade() {
        LineProfile lineProfile = new LineProfile(START_POSITION, TARGET_STOP_POSITION, SPEED_LIMIT);
        TrainModel trainModel = new TrainModel(
                MAX_ACCELERATION,
                NORMAL_BRAKE_DECELERATION,
                EMERGENCY_BRAKE_DECELERATION,
                BRAKE_RESPONSE_TIME,
                MASS,
                DAVIS_A,
                DAVIS_B,
                DAVIS_C
        );
        return new ScenarioConfig(lineProfile, trainModel, DT);
    }

    /**
     * 使用外部传入的 {@link LineProfile} 构造场景配置（阶段4B 线路数据真实化新增）。
     *
     * <p>车辆参数（Davis 系数、制动减速度、质量、制动响应时间、dt）全部使用本类内置
     * 默认值，不因起止站不同而改变。调用方只需构造好来自 line-profile.json 的
     * {@link LineProfile}，本方法完成车型+步长的组装。</p>
     *
     * <p><b>注意：</b>传入的 LineProfile 的 speedLimit 与 gradeSegments 由调用方
     * 负责（当前 {@link LineProfileJsonLoader} 使用假设值 20 m/s + 空坡度段）。</p>
     *
     * @param lineProfile 由调用方构造的线路配置（相对坐标）
     * @return 组装好的 ScenarioConfig
     */
    public ScenarioConfig buildScenario(LineProfile lineProfile) {
        TrainModel trainModel = new TrainModel(
                MAX_ACCELERATION,
                NORMAL_BRAKE_DECELERATION,
                EMERGENCY_BRAKE_DECELERATION,
                BRAKE_RESPONSE_TIME,
                MASS,
                DAVIS_A,
                DAVIS_B,
                DAVIS_C
        );
        return new ScenarioConfig(lineProfile, trainModel, DT);
    }

    /**
     * 构建一个制动响应时间可自定义的演示场景（其余参数与 {@link #getDemoScenario()} 完全一致）。
     * 阶段3B新增，仅供测试对照，验证制动响应时间补偿的生效效果。
     *
     * @param brakeResponseTime 制动响应时间，单位 s
     */
    public ScenarioConfig getDemoScenarioWithBrakeResponseTime(double brakeResponseTime) {
        LineProfile lineProfile = new LineProfile(
                START_POSITION,
                TARGET_STOP_POSITION,
                SPEED_LIMIT,
                Arrays.asList(
                        new GradeSegment(0.0, 800.0, 0.0),
                        new GradeSegment(800.0, TARGET_STOP_POSITION, 0.003)
                )
        );
        TrainModel trainModel = new TrainModel(
                MAX_ACCELERATION,
                NORMAL_BRAKE_DECELERATION,
                EMERGENCY_BRAKE_DECELERATION,
                brakeResponseTime,
                MASS,
                DAVIS_A,
                DAVIS_B,
                DAVIS_C
        );
        return new ScenarioConfig(lineProfile, trainModel, DT);
    }
}
