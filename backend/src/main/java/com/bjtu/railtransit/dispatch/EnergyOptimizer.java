package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.domain.model.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 能源感知调度优化器 —— 基于文档《调度算法与优化方案》6.2节和《ATO目标速度曲线规划》惰行优化理论。
 *
 * 三大核心策略:
 *   Strategy 1 — 错峰启动 (Staggered Departure):
 *     在基础发车间隔上叠加微偏移量，避免多列车同时加速导致的供电峰值叠加。
 *     参考文档 4.10: "多列车同时启动可能造成供电峰值"
 *
 *   Strategy 2 — 惰行窗口优化 (Coasting Window):
 *     当列车运行提前于时刻表时，在巡航段插入惰行段(切断牵引力靠惯性滑行)。
 *     参考文档 方向二 4.2: "惰行优化是节能的核心"
 *
 *   Strategy 3 — 再生制动协同 (Regen Coordination):
 *     当一列车制动时，优先让同区段内其他列车加速吸收再生能量。
 *     参考文档 6.2.2: "单线节能率 8-12%，再生能量利用率从25%提升至45%+"
 *
 *   Strategy 4 — 运行等级自适应调整:
 *     低客流时段自动切换为节能模式，高峰时恢复全速模式。
 *     参考文档 6.1.2: 满载率<40%时延长间隔+节能运行
 */
@Service
public class EnergyOptimizer {

    // ═══════════════════════════════════════════════════════════════
    // 错峰启动参数
    // ═══════════════════════════════════════════════════════════════
    /** 微偏移基础值 (秒)，相邻列车发车最小错峰间隔 */
    public static final double STAGGER_BASE_SEC = 3.0;
    /** 微偏移上限 (秒) */
    public static final double STAGGER_MAX_SEC = 8.0;

    // ═══════════════════════════════════════════════════════════════
    // 惰行窗口参数
    // ═══════════════════════════════════════════════════════════════
    /** 惰行触发条件: 提前于时刻表的秒数阈值 */
    public static final double COAST_AHEAD_THRESHOLD_SEC = 8.0;
    /** 惰行巡航速度 (km/h) —— 切断牵引后仅靠惯性 */
    public static final double COAST_CRUISE_SPEED = 55.0;
    /** 惰行最低触发速度 (km/h)，低于此速度不惰行 (避免溜停) */
    public static final double COAST_MIN_SPEED = 35.0;

    // ═══════════════════════════════════════════════════════════════
    // 再生制动协同参数
    // ═══════════════════════════════════════════════════════════════
    /** 再生制动有效范围 (米)，同一供电区段约 3km */
    public static final double REGEN_RANGE_METERS = 3000.0;
    /** 再生制动协同触发: 制动功率超过此值 (kW) 则通知附近列车加速吸收 */
    public static final double REGEN_TRIGGER_POWER_KW = 200.0;

    // ═══════════════════════════════════════════════════════════════
    // 峰值功率控制
    // ═══════════════════════════════════════════════════════════════
    /** 供电阈值 (kW) */
    public static final double POWER_SUPPLY_THRESHOLD_KW = 2500.0;
    /** 峰值风险: 同时牵引列车数上限 */
    public static final int MAX_SIMULTANEOUS_TRACTION = 4;

    // ═══════════════════════════════════════════════════════════════
    // 节能模式参数
    // ═══════════════════════════════════════════════════════════════
    /** 切换节能模式的满载率阈值 (< 40%) */
    public static final double ENERGY_SAVE_LOAD_THRESHOLD = 0.40;

    // ── 状态追踪 ──

    /**
     * 计算第 i 列车的错峰发车偏移量 (秒)。
     * 相邻列车错峰 STAGGER_BASE_SEC ~ STAGGER_MAX_SEC，
     * 取决于列车编号模数，形成周期性错峰。
     */
    public double calcStaggerOffset(int trainIndex, int totalTrains) {
        // 每4列车一个错峰周期: 0, 3s, 6s, 5s, 2s, 4s, 7s, 3s ...
        double[] pattern = {0, 3.0, 6.0, 5.0, 2.0, 4.0, 7.0, 3.5};
        return pattern[trainIndex % pattern.length];
    }

    // ================================================================
    // Strategy 1: 错峰启动决策
    // ================================================================

    /**
     * 为所有列车计算错峰发车时间。
     * 返回 Map<trainId, 偏移秒数>
     */
    public Map<String, Double> computeStaggeredDepartures(Map<String, TrainState> trains) {
        Map<String, Double> offsets = new LinkedHashMap<>();
        List<TrainState> sorted = trains.values().stream()
                .sorted(Comparator.comparingDouble(TrainState::getPlannedDepartureFromDepot))
                .collect(Collectors.toList());

        for (int i = 0; i < sorted.size(); i++) {
            TrainState t = sorted.get(i);
            double offset = calcStaggerOffset(i, sorted.size());
            offsets.put(t.getTrainId(), offset);
        }
        return offsets;
    }

    // ================================================================
    // Strategy 2: 惰行窗口优化
    // ================================================================

    /**
     * 判断一列车在当前状态下是否应该进入惰行模式。
     * 条件:
     *   1. 列车处于 CRUISING 巡航状态
     *   2. 当前速度 ≥ COAST_MIN_SPEED (避免溜停)
     *   3. 提前于计划时刻 ≥ COAST_AHEAD_THRESHOLD_SEC
     *   4. 无紧急制动或限速指令
     */
    public CoastingDecision evaluateCoasting(TrainState train, double simTimeSeconds,
                                              DispatchEngine.TimetableEntry nextEntry) {
        CoastingDecision decision = new CoastingDecision();
        decision.trainId = train.getTrainId();
        decision.shouldCoast = false;

        // 仅巡航状态
        if (!"CRUISING".equals(train.getStatus()) && !"ACCELERATING".equals(train.getStatus())) {
            return decision;
        }

        // 速度过低不惰行
        if (train.getSpeed() < COAST_MIN_SPEED) {
            return decision;
        }

        // 紧急制动中不惰行
        if (train.isEmergencyBraking()) {
            return decision;
        }

        // 计算提前量
        if (nextEntry != null && nextEntry.plannedArrival > 0) {
            double distanceToNextStation = 0;
            if (train.getNextStationIndex() >= 0) {
                double posKm = train.getPositionMeters() / 1000.0;
                distanceToNextStation = Math.abs(nextEntry.stationKm - posKm) * 1000.0;
            }

            // 预估到达时间 = 当前时间 + 距离/速度
            double speedMs = Math.max(train.getSpeed() / 3.6, 1.0);
            double etaSeconds = simTimeSeconds + (distanceToNextStation / speedMs);
            double aheadSeconds = nextEntry.plannedArrival - etaSeconds;

            decision.etaSeconds = etaSeconds;
            decision.aheadSeconds = aheadSeconds;
            decision.plannedArrival = nextEntry.plannedArrival;

            // 提前足够多 → 插入惰行
            if (aheadSeconds >= COAST_AHEAD_THRESHOLD_SEC && distanceToNextStation > 500) {
                decision.shouldCoast = true;
                // 惰行目标速度: 略低于正常巡航，允许自然减速
                decision.coastTargetSpeedKmh = COAST_CRUISE_SPEED;
                decision.reason = String.format(
                        "提前%.0fs, 距下站%.0fm, 插入惰行窗口节能",
                        aheadSeconds, distanceToNextStation);
            }
        }
        return decision;
    }

    /**
     * 惰行决策 DTO
     */
    public static class CoastingDecision {
        public String trainId;
        public boolean shouldCoast;
        public double coastTargetSpeedKmh;
        public double etaSeconds;
        public double aheadSeconds;
        public double plannedArrival;
        public String reason;
    }

    // ================================================================
    // Strategy 3: 再生制动协同
    // ================================================================

    /**
     * 检测再生制动协同机会。
     * 返回协同指令: 当有列车制动时，指定附近可加速的列车ID。
     */
    public List<RegenCoordination> detectRegenOpportunities(Map<String, TrainState> trains) {
        List<RegenCoordination> coordinations = new ArrayList<>();
        List<TrainState> braking = new ArrayList<>();
        List<TrainState> accelerating = new ArrayList<>();

        for (TrainState t : trains.values()) {
            if ("FINISHED".equals(t.getStatus()) || "DEPOT_WAITING".equals(t.getStatus())) continue;

            if ("BRAKING".equals(t.getStatus()) && t.getSpeed() > 0) {
                braking.add(t);
            }
            if ("ACCELERATING".equals(t.getStatus()) || "DEPARTING".equals(t.getStatus())) {
                accelerating.add(t);
            }
        }

        for (TrainState brakeTrain : braking) {
            // 估算制动功率
            double brakePowerKw = estimateBrakePower(brakeTrain);
            if (brakePowerKw < REGEN_TRIGGER_POWER_KW) continue;

            for (TrainState accelTrain : accelerating) {
                if (accelTrain.getTrainId().equals(brakeTrain.getTrainId())) continue;

                double distance = Math.abs(brakeTrain.getPositionMeters() - accelTrain.getPositionMeters());
                if (distance <= REGEN_RANGE_METERS) {
                    RegenCoordination rc = new RegenCoordination();
                    rc.brakingTrainId = brakeTrain.getTrainId();
                    rc.absorbingTrainId = accelTrain.getTrainId();
                    rc.distanceMeters = distance;
                    rc.brakePowerKw = brakePowerKw;
                    rc.recoverableEnergyKw = brakePowerKw * 0.65; // 再生制动效率 65%
                    rc.reason = String.format(
                            "%s 制动(%.0fkW) → %s 加速吸收, 间距%.0fm",
                            brakeTrain.getTrainId(), brakePowerKw,
                            accelTrain.getTrainId(), distance);
                    coordinations.add(rc);
                    break; // 一个制动列车配对一个加速列车
                }
            }
        }
        return coordinations;
    }

    private double estimateBrakePower(TrainState train) {
        // 简化的制动功率估计: P = m × a × v
        double massKg = train.getCarCount() * 35000.0;
        double accelMs2 = 1.0; // 常用制动减速度
        double speedMs = train.getSpeed() / 3.6;
        return (massKg * accelMs2 * speedMs) / 1000.0; // kW
    }

    public static class RegenCoordination {
        public String brakingTrainId;
        public String absorbingTrainId;
        public double distanceMeters;
        public double brakePowerKw;
        public double recoverableEnergyKw;
        public String reason;
    }

    // ================================================================
    // Strategy 4: 运行等级自适应调整
    // ================================================================

    /**
     * 根据客流满载率判断是否应切换为节能运行模式。
     */
    public String recommendOperationLevel(double loadFactor, TimePeriod currentPeriod) {
        // 低客流 (< 40%) → 节能模式
        if (loadFactor < ENERGY_SAVE_LOAD_THRESHOLD) {
            return OperationLevel.ENERGY_SAVE;
        }
        // 凌晨/夜间 → 节能模式
        if (currentPeriod == TimePeriod.EARLY_MORNING || currentPeriod == TimePeriod.NIGHT) {
            return OperationLevel.ENERGY_SAVE;
        }
        // 高峰 → 正常/快车
        if (currentPeriod == TimePeriod.MORNING_PEAK || currentPeriod == TimePeriod.EVENING_PEAK) {
            return OperationLevel.NORMAL;
        }
        return OperationLevel.NORMAL;
    }

    // ═══════════════════════════════════════════════════════════════
    // 时段枚举 (简化)
    // ═══════════════════════════════════════════════════════════════
    public enum TimePeriod {
        EARLY_MORNING, MORNING_PEAK, MIDDAY, EVENING_PEAK, NIGHT;

        public static TimePeriod fromSimTime(double simTimeSeconds) {
            int hour = ((int) (simTimeSeconds / 3600)) % 24;
            if (hour <= 6) return EARLY_MORNING;
            if (hour <= 9) return MORNING_PEAK;
            if (hour <= 16) return MIDDAY;
            if (hour <= 19) return EVENING_PEAK;
            return NIGHT;
        }
    }

    // ================================================================
    // 综合优化评估
    // ================================================================

    /**
     * 综合能源优化评估: 检测峰值风险、再生协同机会、惰行机会。
     */
    public EnergyOptimizationResult evaluate(Map<String, TrainState> trains,
                                              double simTimeSeconds,
                                              DispatchEngine dispatchEngine) {
        EnergyOptimizationResult result = new EnergyOptimizationResult();

        // 1. 实时峰值功率检测
        result.currentPeakKw = computeInstantPeakPower(trains);
        result.powerSupplyThresholdKw = POWER_SUPPLY_THRESHOLD_KW;
        result.peakRiskLevel = result.currentPeakKw >= POWER_SUPPLY_THRESHOLD_KW * 0.9 ? "warning"
                : result.currentPeakKw >= POWER_SUPPLY_THRESHOLD_KW ? "danger" : "safe";

        // 2. 同时牵引列车数
        result.tractionCount = countTractionTrains(trains);
        result.maxTractionCount = MAX_SIMULTANEOUS_TRACTION;

        // 3. 再生制动协同
        result.regenCoordinations = detectRegenOpportunities(trains);
        result.totalRecoverableEnergyKw = result.regenCoordinations.stream()
                .mapToDouble(r -> r.recoverableEnergyKw).sum();

        // 4. 惰行机会
        List<CoastingDecision> coastingDecisions = new ArrayList<>();
        for (TrainState t : trains.values()) {
            if (!"CRUISING".equals(t.getStatus()) && !"ACCELERATING".equals(t.getStatus())) continue;
            DispatchEngine.TimetableEntry nextEntry = dispatchEngine.getTimetableEntry(
                    t.getTrainId(), t.getNextStationIndex());
            CoastingDecision cd = evaluateCoasting(t, simTimeSeconds, nextEntry);
            if (cd.shouldCoast) {
                coastingDecisions.add(cd);
            }
        }
        result.coastingOpportunities = coastingDecisions;

        // 5. 节能建议
        List<String> recommendations = new ArrayList<>();
        if (result.peakRiskLevel.equals("danger")) {
            recommendations.add("峰值功率超限! 建议错开发车或限制同时牵引列车数至" + MAX_SIMULTANEOUS_TRACTION + "列");
        }
        if (!result.regenCoordinations.isEmpty()) {
            recommendations.add(String.format("检测到 %d 组再生制动协同机会，可回收约 %.0f kW",
                    result.regenCoordinations.size(), result.totalRecoverableEnergyKw));
        }
        if (!coastingDecisions.isEmpty()) {
            recommendations.add(String.format("%d 列车可插入惰行窗口以节能", coastingDecisions.size()));
        }

        // 客流驱动的节能模式建议
        if (simTimeSeconds > 0 && dispatchEngine.getFlowModel() != null) {
            PassengerFlowModel flowModel = dispatchEngine.getFlowModel();
            double peakFlow = flowModel.getPeakSectionFlow(simTimeSeconds);
            double loadFactor = peakFlow / (PassengerFlowModel.TRAIN_CAPACITY * PassengerFlowModel.TARGET_LOAD_FACTOR);
            result.currentLoadFactor = loadFactor;

            TimePeriod period = TimePeriod.fromSimTime(simTimeSeconds);
            String recommendedLevel = recommendOperationLevel(loadFactor, period);

            if (OperationLevel.ENERGY_SAVE.equals(recommendedLevel) && loadFactor < ENERGY_SAVE_LOAD_THRESHOLD) {
                recommendations.add(String.format("当前满载率 %.0f%% 较低，建议切换节能模式 (55km/h 巡航 + 柔和加减速)",
                        loadFactor * 100));
            }
        }

        result.recommendations = recommendations;
        return result;
    }

    /** 实时峰值功率估算 (简化: 基于加速度与速度) */
    private double computeInstantPeakPower(Map<String, TrainState> trains) {
        double totalPowerKw = 0;
        for (TrainState t : trains.values()) {
            if ("FINISHED".equals(t.getStatus()) || "DEPOT_WAITING".equals(t.getStatus())) continue;
            // 牵引/加速状态才计入峰值
            if (t.getAcceleration() > 0 && t.getSpeed() > 0) {
                double massKg = t.getCarCount() * 35000.0;
                double forceN = massKg * (t.getAcceleration() / 3.6) + 2000.0; // N
                double speedMs = t.getSpeed() / 3.6;
                double powerKw = (forceN * speedMs) / (0.85 * 1000.0); // 牵引效率 85%
                totalPowerKw += powerKw;
            }
        }
        return totalPowerKw;
    }

    private int countTractionTrains(Map<String, TrainState> trains) {
        int count = 0;
        for (TrainState t : trains.values()) {
            if ("ACCELERATING".equals(t.getStatus()) || "DEPARTING".equals(t.getStatus())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 能源优化综合结果 DTO
     */
    public static class EnergyOptimizationResult {
        public double currentPeakKw;
        public double powerSupplyThresholdKw;
        public String peakRiskLevel;           // safe | warning | danger
        public int tractionCount;
        public int maxTractionCount;
        public double totalRecoverableEnergyKw;
        public List<RegenCoordination> regenCoordinations = new ArrayList<>();
        public List<CoastingDecision> coastingOpportunities = new ArrayList<>();
        public List<String> recommendations = new ArrayList<>();
        public double currentLoadFactor;
    }
}
