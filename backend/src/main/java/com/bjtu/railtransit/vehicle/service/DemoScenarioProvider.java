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
 * <p>阶段 1A 不读取 {@code configs/} 下的 sample JSON，全部使用本类内置的
 * 固定演示参数，避免因组内配置字段缺失（质量、紧急制动、制动响应时间等）
 * 或单位不明确导致阻塞。阶段 2 在此基础上新增 Davis 阻力系数与坡度段演示配置。</p>
 *
 * <p><b>待确认项（记入进度账本）：</b></p>
 * <ul>
 *   <li>车辆质量 40000 kg 为占位默认值，需郭逸晨/组内后续确认是否符合实训车型设定。</li>
 *   <li>限速 20 m/s、最大加速度 1.0 m/s2、常用制动 1.2 m/s2、紧急制动 1.5 m/s2、
 *       制动响应时间 0.5 s 均为参考地铁列车量级的合理默认值，非真实车型数据。</li>
 * </ul>
 *
 * <p><b>阶段 2 新增：Davis 阻力系数（工程假设值，非实测数据）。</b></p>
 * <ul>
 *   <li>公式形式 {@code w0 = a + b*v + c*v^2 (N/kN)}（v 单位 km/h）取自城轨/铁道列车
 *       牵引计算教材的通用戴维斯公式形式，多个独立教材/词条来源（如"铁道工程名词"
 *       词条、列车运行阻力教学资料）一致给出这个结构，公式骨架有据可查。</li>
 *   <li>但本类填入的具体系数数值（a=2.0, b=0.03, c=0.0015）<b>没有</b>对应到某个具体
 *       真实车型的实测报告或标准文档，也<b>不来自</b> {@code 704系统协议汇总.docx} 或
 *       {@code 线路数据(1).xls}（这两个文件不含车辆阻力系数、迎风面积等物理参数）。
 *       取值只保证量级合理（在本演示线路限速 20m/s≈72km/h 下，阻力对应的减速度约
 *       0.117 m/s²，相对于最大加速度 1.0 m/s²、常用制动 1.2 m/s² 是有意义但不会
 *       压倒控制量的量级），明确标注为工程假设值，不冒充实测数据。</li>
 *   <li>换算说明：教材公式 v 用 km/h，本项目内部速度统一 SI（m/s）。
 *       {@link VehicleSimulationService} 在计算阻力时会把内部 m/s 速度换算成 km/h
 *       再代入本公式（v_kmh = v_ms * 3.6），换算逻辑写在 service 内，不改变这里存储的
 *       原始教材基准系数。</li>
 * </ul>
 *
 * <p><b>阶段 2 新增：坡度段（工程假设值，非线路真实坡度数据）。</b></p>
 * <ul>
 *   <li>本轮明确不解析 {@code 线路数据(1).xls} 的坡度表（属于阶段 2.5 之后的数据工程
 *       范畴）。这里用 2 段简化常数坡度演示"坡度阻力确实进入动力学计算"这一效应：
 *       0~800m 平坡（i=0），800~1200m 上坡 i=3‰（0.003）。数值纯属演示假设。</li>
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

    /** 最大加速度，单位 m/s2。阶段 2 起语义调整为"牵引能力对应的加速度"，见 service 注释。 */
    private static final double MAX_ACCELERATION = 1.0;

    /** 常用制动减速度，单位 m/s2。阶段 2 起语义调整为"制动系统本身的减速能力"，阻力会叠加帮助减速。 */
    private static final double NORMAL_BRAKE_DECELERATION = 1.2;

    /** 紧急制动减速度，单位 m/s2。阶段 1A 暂未使用，供后续阶段（SafetyGuard）使用。 */
    private static final double EMERGENCY_BRAKE_DECELERATION = 1.5;

    /** 制动响应时间，单位 s。阶段 1A 简化动力学暂未引入响应延迟，供后续阶段使用。 */
    private static final double BRAKE_RESPONSE_TIME = 0.5;

    /** 车辆质量，单位 kg。默认值为待确认项，见类注释。 */
    private static final double MASS = 40000.0;

    /** Davis 阻力公式常数项，单位 N/kN。工程假设值，见类注释。 */
    private static final double DAVIS_A = 2.0;

    /** Davis 阻力公式线性项系数，单位 N/kN/(km/h)。工程假设值，见类注释。 */
    private static final double DAVIS_B = 0.03;

    /** Davis 阻力公式二次项系数，单位 N/kN/(km/h)^2。工程假设值，见类注释。 */
    private static final double DAVIS_C = 0.0015;

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
