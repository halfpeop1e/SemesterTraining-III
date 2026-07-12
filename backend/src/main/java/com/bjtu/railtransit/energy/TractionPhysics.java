package com.bjtu.railtransit.energy;

import com.bjtu.railtransit.dispatch.LineDataService;
import com.bjtu.railtransit.domain.model.TrainState;
import org.springframework.stereotype.Service;

/**
 * 列车牵引物理模型 —— 基于"列车仿真参数.xlsx"真实车型数据.
 *
 * <p>公式来源:
 * <ul>
 *   <li>基本阻力: R[N] = 6.4M + 130n + 0.14Mv + [0.046+0.0065(N-1)]Av² (Davis公式)
 *       M=列车质量(吨), n=24轴, v=车速(km/h), N=6辆, A=10.6m²</li>
 *   <li>坡道阻力: Wᵢ = M·g·i/1000 (i=坡度千分数‰)</li>
 *   <li>回转质量修正: γ = 0.06</li>
 *   <li>电机效率: η = 0.882 (全工况恒定, 由网流/机械功率回归)</li>
 *   <li>牵引/制动特性: 基于电机转矩曲线 Speed_Tranction_Te_AW0 / Speed_Brake_Te_AW0</li>
 * </ul>
 */
@Service
public class TractionPhysics {

    // ── 基本参数 (来源: 列车仿真参数.xlsx) ──
    private static final double TRAIN_MASS_KG = 225_000.0;
    private static final int MOTOR_COUNT = 16;
    private static final double WHEEL_RADIUS_M = 0.46;

    // ── Davis 基本阻力系数 (来源: 列车仿真参数.xlsx) ──
    // 通用形式: R[N] = 6.4M_t + 3120 + 0.14M_t·v_kmh + 0.8321·v_kmh²
    // 换算为 N/kg 制 (v: m/s):
    //   R/M = 0.02027 + 0.000504·v_ms + 0.0000479·v_ms²
    private static final double DAVIS_A = 0.02027;
    private static final double DAVIS_B = 0.000504;
    private static final double DAVIS_C = 0.0000479;
    private static final double ROTARY_MASS_FACTOR = 0.06;

    // ── 传动比 (需根据实际车型确认, 当前为推算值) ──
    private static final double GEAR_RATIO = 7.5;
    private static final double RPM_TO_KMH = 2.0 * Math.PI * WHEEL_RADIUS_M * 3.6 / 60.0;

    // ── 电机转矩特性 (单电机, 来源: 列车仿真参数.xlsx 牵引曲线) ──
    /** 恒转矩区峰值 N·m */
    private static final double TORQUE_CONST = 1042.9;
    /** 恒转矩→恒功率过渡转速 rpm */
    private static final double RPM_CT_END = 2496.1;
    /** 恒功率区功率 kW */
    private static final double POWER_CONST_KW = 271.0;
    /** 恒功率→自然特性过渡转速 rpm */
    private static final double RPM_CP_END = 2912.1;
    /** 自然特性区: T = K_NAT / rpm² */
    private static final double K_NAT = 6.477812984e9;

    // ── 制动转矩特性 (单电机) ──
    private static final double TORQUE_BRAKE = 977.7;
    private static final double RPM_BRAKE_START = 166.4;

    // ── 效率 (来源: 列车仿真参数.xlsx 网流/机械功率回归) ──
    private static final double EFF_TRACTION = 0.882;
    private static final double EFF_REGEN = 0.802;

    // ── 辅助能耗 ──
    private static final double AUX_POWER_PER_CAR_KW = 18.0;

    private final LineDataService lineDataService;

    public TractionPhysics(LineDataService lineDataService) {
        this.lineDataService = lineDataService;
    }

    // ═══════════════════════════════════════════════════════════════
    // 阻力计算
    // ═══════════════════════════════════════════════════════════════

    /** 获取列车总质量 (kg) */
    private double getTrainMassKg(TrainState train) {
        return TRAIN_MASS_KG;
    }

    public double totalResistanceForce(TrainState train) {
        double massKg = getTrainMassKg(train);
        double speedMs = train.getSpeed() / 3.6;
        return basicResistanceNewton(massKg, speedMs)
                + gradeResistanceNewton(massKg, train.getPositionMeters());
    }

    /**
     * Davis 基本阻力 W₀(N) = M × (DAVIS_A + DAVIS_B×v_ms + DAVIS_C×v_ms²)
     */
    public double basicResistanceNewton(double massKg, double speedMs) {
        return massKg * (DAVIS_A + DAVIS_B * speedMs + DAVIS_C * speedMs * speedMs);
    }

    /**
     * 坡道附加阻力 Wᵢ(N) = M × g × i / 1000 (i=‰)
     */
    public double gradeResistanceNewton(double massKg, double positionMeters) {
        double posKm = positionMeters / 1000.0;
        int permille = lineDataService.getGradientAtKm(posKm);
        return massKg * 9.81 * permille / 1000.0;
    }

    /**
     * 惯性力 (N): F = M × a × (1 + γ)
     */
    public double inertiaForce(double massKg, double accelMs2) {
        return massKg * accelMs2 * (1.0 + ROTARY_MASS_FACTOR);
    }

    // ═══════════════════════════════════════════════════════════════
    // 电机转矩 / 牵引力
    // ═══════════════════════════════════════════════════════════════

    /**
     * 单电机最大牵引转矩 (N·m).
     * 恒转矩区(0~2496rpm):     T = 1042.9
     * 恒功率区(2496~2912rpm):  T = 271kW × 9549.3 / rpm
     * 自然特性区(2912~4160rpm): T = 6.478×10⁹ / rpm²
     */
    public static double motorTorqueTraction(double rpm) {
        if (rpm <= RPM_CT_END) {
            return TORQUE_CONST;
        } else if (rpm <= RPM_CP_END) {
            return POWER_CONST_KW * 9549.3 / rpm;
        } else {
            return K_NAT / (rpm * rpm);
        }
    }

    /** 车速 km/h → 电机转速 rpm */
    private static double speedToRpm(double speedKmh) {
        return speedKmh * GEAR_RATIO / RPM_TO_KMH;
    }

    /**
     * 最大可用轮周牵引力 (N).
     */
    public double maxTractiveForceAtSpeed(double speedKmh, int availableMotors, int totalMotors) {
        double rpm = speedToRpm(Math.max(0.1, speedKmh));
        double torque = motorTorqueTraction(rpm);
        double ratio = (double) availableMotors / Math.max(1, totalMotors);
        return torque * MOTOR_COUNT * ratio * GEAR_RATIO / WHEEL_RADIUS_M;
    }

    // ═══════════════════════════════════════════════════════════════
    // 功率 / 能耗
    // ═══════════════════════════════════════════════════════════════

    /** 电机+逆变器效率 (全工况恒定 88.2%) */
    public static double motorEfficiency(double speedKmh) {
        return EFF_TRACTION;
    }

    /** 牵引功率 (kW): P = (F_inertia + F_resistance) × v / (η × 1000) */
    public double tractionPowerKw(TrainState train) {
        double massKg = getTrainMassKg(train);
        double accelMs2 = Math.max(0, train.getAcceleration() / 3.6);
        double speedMs = train.getSpeed() / 3.6;
        double totalForceN = inertiaForce(massKg, accelMs2) + totalResistanceForce(train);
        return (totalForceN * speedMs) / (EFF_TRACTION * 1000.0);
    }

    /** 巡航功率 (kW): 仅克服阻力 */
    public double cruisingPowerKw(TrainState train) {
        double speedMs = train.getSpeed() / 3.6;
        double resistanceForce = totalResistanceForce(train);
        return (resistanceForce * speedMs) / (EFF_TRACTION * 1000.0);
    }

    /** 再生制动回收功率 (kW) */
    public double regenPowerKw(TrainState train, double regenUtilizationRate) {
        double massKg = getTrainMassKg(train);
        double decelMs2 = Math.abs(Math.min(0, train.getAcceleration() / 3.6));
        double speedMs = train.getSpeed() / 3.6;
        double decelForce = massKg * decelMs2 * (1.0 + ROTARY_MASS_FACTOR);
        double resistanceHelp = basicResistanceNewton(massKg, speedMs);
        double netBrakingPower = Math.max(0, (decelForce - resistanceHelp) * speedMs / 1000.0);
        return netBrakingPower * EFF_REGEN * regenUtilizationRate;
    }

    /** 单步牵引能耗 (kWh) */
    public double tractionStepEnergyKwh(TrainState train) {
        return tractionPowerKw(train) / 3600.0;
    }

    /** 单步巡航能耗 (kWh) */
    public double cruisingStepEnergyKwh(TrainState train) {
        return cruisingPowerKw(train) / 3600.0;
    }

    /** 单步再生制动回收 (kWh) */
    public double regenStepEnergyKwh(TrainState train, double regenUtilizationRate) {
        return regenPowerKw(train, regenUtilizationRate) / 3600.0;
    }

    /** 辅助能耗 (kW): 6编组×18kW/节 = 108kW */
    public double auxiliaryPowerKw(int carCount) {
        return carCount * AUX_POWER_PER_CAR_KW;
    }

    /** 单步辅助能耗 (kWh) */
    public double auxiliaryStepEnergyKwh(int carCount) {
        return auxiliaryPowerKw(carCount) / 3600.0;
    }

    // ═══════════════════════════════════════════════════════════════
    // 查询
    // ═══════════════════════════════════════════════════════════════

    public int getGradePermilleAtMeters(double positionMeters) {
        return lineDataService.getGradientAtKm(positionMeters / 1000.0);
    }

    public boolean isInTunnel(double positionMeters) {
        return lineDataService.isTunnelAtKm(positionMeters / 1000.0);
    }
}
