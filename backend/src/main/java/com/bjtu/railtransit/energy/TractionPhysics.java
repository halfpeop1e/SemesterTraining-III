package com.bjtu.railtransit.energy;

import com.bjtu.railtransit.dispatch.LineDataService;
import com.bjtu.railtransit.domain.model.TrainState;
import org.springframework.stereotype.Service;

/**
 * 列车牵引物理模型 —— 基于 TB/T 1407.2-2024 国家标准的能耗计算引擎.
 *
 * 公式来源:
 * 基本阻力: W₀ = M·(a + b·v + c·v²)·g (Davis 公式, a/b/c 为 N/kN 级系数)
 * 坡道阻力: Wᵢ = M·g·i/1000 (i 为坡度千分数 ‰)
 * 曲线阻力: Wᵣ = M·g·(600/R) (R 为曲线半径 m, 简化公式)
 * 回转质量修正: γ = 0.06 (旋转部件惯量)
 * 电机效率: η(v) 速度相关映射
 * 辅助能耗: HVAC+照明 ≈ 18kW/节 (TB/T 1407.2 推荐值)
 */
@Service
public class TractionPhysics {

    // ── Davis 基本阻力系数 (地铁6编组, N/kN 级) ──
    // W₀(N) = M(kg) × 9.81 / 1000 × (a + b·v_(km/h) + c·v_(km/h)²) × 1000
    // 简化: W₀(N) = M(kg) × (A + B·v_ms + C·v_ms²)
    // 其中 A≈0.025, B≈0.00035, C≈0.00115 (已在 N/kg 单位)
    private static final double DAVIS_A = 0.025; // N/kg 机械摩擦常数项 (~525N for 210t)
    private static final double DAVIS_B = 0.00035; // N·s/(kg·m) 滚动线性项
    private static final double DAVIS_C = 0.00115; // N·s²/(kg·m²) 空气阻力二次项
    private static final double ROTARY_MASS_FACTOR = 0.06; // γ 回转质量系数

    // ── 辅助能耗参数 (TB/T 1407.2 推荐: 8编组~18A, 16编组~36A) ──
    // 6编组估算: 12A × 1500V DC = 18kW/节 → 108kW 总辅助功率
    private static final double AUX_POWER_PER_CAR_KW = 18.0; // kW/节

    // ── 电机效率曲线 η(v) (速度相关, 简化为梯形) ──
    // 0-15 km/h: 0.88 (低速大转矩, 效率稍低)
    // 15-50 km/h: 0.93 (最优区)
    // 50-80 km/h: 0.90 (弱磁区)
    // >80 km/h: 0.88
    public static double motorEfficiency(double speedKmh) {
        if (speedKmh < 15)
            return 0.88;
        if (speedKmh < 50)
            return 0.93;
        if (speedKmh < 80)
            return 0.90;
        return 0.88;
    }

    private final LineDataService lineDataService;

    public TractionPhysics(LineDataService lineDataService) {
        this.lineDataService = lineDataService;
    }

    /**
     * 计算列车总运行阻力 (N)
     * W = W₀(基本) + Wᵢ(坡道) + Wᵣ(曲线)
     */
    public double totalResistanceForce(TrainState train) {
        double massKg = train.getCarCount() * 35000.0;
        double speedMs = train.getSpeed() / 3.6;

        double basicResistance = basicResistanceNewton(massKg, speedMs);
        double gradeResistance = gradeResistanceNewton(massKg, train.getPositionMeters());
        // 曲线附加阻力: 当前线路数据中无水平曲线半径字段, 保持0
        // w_r = M × g × (600 / R), 其中 R 为曲线半径(m)
        double curveResistance = 0;

        return basicResistance + gradeResistance + curveResistance;
    }

    /**
     * Davis 基本阻力 W₀(N)
     * 实际公式: W₀ = M × (DAVIS_A + DAVIS_B×v + DAVIS_C×v²)
     * 其中 M 为列车质量(kg), v 为速度(m/s)
     */
    public double basicResistanceNewton(double massKg, double speedMs) {
        return massKg * (DAVIS_A + DAVIS_B * speedMs + DAVIS_C * speedMs * speedMs);
    }

    /**
     * 坡道附加阻力 Wᵢ(N)
     * Wᵢ = M × g × i / 1000
     * 其中 i 为线路坡度千分数 (‰)
     * 上坡为正(增阻), 下坡为负(助行)
     */
    public double gradeResistanceNewton(double massKg, double positionMeters) {
        double posKm = positionMeters / 1000.0;
        int permille = lineDataService.getGradientAtKm(posKm);
        return massKg * 9.81 * permille / 1000.0;
    }

    /**
     * 惯性力 (N): F = M × a × (1 + γ)
     * 回转质量系数 γ 修正旋转部件惯量对加速的影响
     */
    public double inertiaForce(double massKg, double accelMs2) {
        return massKg * accelMs2 * (1.0 + ROTARY_MASS_FACTOR);
    }

    /**
     * 牵引功率 (kW): P = (F_traction × v) / (η × 1000)
     * F_traction = 惯性力 + 总阻力
     * η = 电机效率 (速度相关)
     */
    public double tractionPowerKw(TrainState train) {
        double massKg = train.getCarCount() * 35000.0;
        double accelMs2 = Math.max(0, train.getAcceleration() / 3.6); // km/h/s → m/s²
        double speedMs = train.getSpeed() / 3.6;
        double speedKmh = train.getSpeed();

        double inertiaForce = inertiaForce(massKg, accelMs2);
        double resistanceForce = totalResistanceForce(train);
        double totalForceN = inertiaForce + resistanceForce;
        double efficiency = motorEfficiency(speedKmh);

        return (totalForceN * speedMs) / (efficiency * 1000.0);
    }

    /**
     * 巡航功率 (kW): 仅克服阻力, 无惯性项
     */
    public double cruisingPowerKw(TrainState train) {
        double speedMs = train.getSpeed() / 3.6;
        double speedKmh = train.getSpeed();
        double resistanceForce = totalResistanceForce(train);
        double efficiency = motorEfficiency(speedKmh);
        return (resistanceForce * speedMs) / (efficiency * 1000.0);
    }

    /**
     * 再生制动回收功率 (kW)
     * P_regen = (F_brake × v) × η_regen / 1000
     * η_regen = 0.65 (典型值), 实际利用率取决于附近是否有吸收列车
     */
    public double regenPowerKw(TrainState train, double regenUtilizationRate) {
        double massKg = train.getCarCount() * 35000.0;
        double decelMs2 = Math.abs(Math.min(0, train.getAcceleration() / 3.6));
        double speedMs = train.getSpeed() / 3.6;
        double decelForce = massKg * decelMs2 * (1.0 + ROTARY_MASS_FACTOR);
        // 制动时阻力帮助减速, 可回收部分扣除阻力
        double resistanceHelp = basicResistanceNewton(massKg, speedMs);
        double netBrakingPower = Math.max(0, (decelForce - resistanceHelp) * speedMs / 1000.0);
        return netBrakingPower * 0.65 * regenUtilizationRate;
    }

    /**
     * 单步牵引能耗 (kWh): E = P_traction × Δt / 3600
     */
    public double tractionStepEnergyKwh(TrainState train) {
        return tractionPowerKw(train) / 3600.0; // 1秒步长
    }

    /**
     * 单步巡航能耗 (kWh)
     */
    public double cruisingStepEnergyKwh(TrainState train) {
        return cruisingPowerKw(train) / 3600.0;
    }

    /**
     * 单步再生制动回收 (kWh)
     */
    public double regenStepEnergyKwh(TrainState train, double regenUtilizationRate) {
        return regenPowerKw(train, regenUtilizationRate) / 3600.0;
    }

    /**
     * 辅助能耗 (kW): HVAC + 照明 + 控制系统
     * 6节编组 × 18kW/节 = 108kW
     */
    public double auxiliaryPowerKw(int carCount) {
        return carCount * AUX_POWER_PER_CAR_KW;
    }

    /**
     * 单步辅助能耗 (kWh)
     */
    public double auxiliaryStepEnergyKwh(int carCount) {
        return auxiliaryPowerKw(carCount) / 3600.0; // 1秒步长
    }

    // ── 查询方法 ──

    /**
     * 牵引能力曲线 (中车株洲所异步电机特性, 6B编组参考值).
     *
     * 恒转矩区 (0~36km/h):  F_max = 310 kN
     * 恒功率区 (36~72km/h): F = 310 × 36 / v
     * 自然特性区 (>72km/h): F = 310 × 36 × 72 / v²
     *
     * @param speedKmh 当前速度 (km/h)
     * @param availableMotors 可用电机数
     * @param totalMotors 总电机数 (用于计算能力比例)
     * @return 当前最大可用牵引力 (N)
     */
    public double maxTractiveForceAtSpeed(double speedKmh, int availableMotors, int totalMotors) {
        double ratio = (double) availableMotors / Math.max(1, totalMotors);
        double baseForceKN = 310.0 * ratio; // 6B编组恒转矩 310kN
        double v = Math.max(1, speedKmh);

        if (v <= 36.0) {
            return baseForceKN * 1000.0;
        } else if (v <= 72.0) {
            return baseForceKN * 36.0 / v * 1000.0;
        } else {
            return baseForceKN * 36.0 * 72.0 / (v * v) * 1000.0;
        }
    }

    /** 获取位置处坡度(‰) */
    public int getGradePermilleAtMeters(double positionMeters) {
        return lineDataService.getGradientAtKm(positionMeters / 1000.0);
    }

    /** 是否在隧道内 */
    public boolean isInTunnel(double positionMeters) {
        return lineDataService.isTunnelAtKm(positionMeters / 1000.0);
    }
}
