package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.domain.model.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 模型预测控制器 (Model Predictive Control) —— 调度策略的前瞻优化引擎。
 *
 * 将原有的「当前状态 → 即时规则响应」升级为:
 *   「当前状态 → 预测未来 N 秒 → 枚举多组控制策略 → 评估代价 → 选择最优 → 应用第一步」
 *
 * 参考:
 *   - Predictive Traffic Regulation Model for Railway Mass Transit Lines
 *     Equipped With Continuous Communication Systems (IEEE Access, 2024)
 *   - Real-Time Multi-Train Trajectory Optimisation Using SH-MPC + GA (IET ITS, 2025)
 *
 * 核心流程:
 *   1. 对每列晚点列车，生成候选控制策略集合
 *   2. 对每个候选策略，在预测时域内快速仿真列车状态演化
 *   3. 计算代价函数: delay + energy + propagation + comfort
 *   4. 选择代价最小的策略作为最优解
 */
@Service
public class PredictiveController {

    private final DispatchEngine dispatchEngine;

    // ═══════════════════════════════════════════════════════════════
    // MPC 参数
    // ═══════════════════════════════════════════════════════════════

    /** 预测时域 (秒) */
    public static final int PREDICTION_HORIZON_SEC = 120;
    /** 预测步长 (秒)，粗粒度以降低计算量 */
    public static final int PREDICTION_STEP_SEC = 5;
    /** 每列车最多评估的候选策略数 */
    public static final int MAX_CANDIDATES_PER_TRAIN = 12;

    /** 代价函数权重 */
    public static final double WEIGHT_DELAY       = 1.0;   // 晚点秒数权重
    public static final double WEIGHT_ENERGY      = 0.02;  // 能耗 kWh 权重 (1kWh ≈ 50s 晚点)
    public static final double WEIGHT_PROPAGATION = 2.0;   // 传播影响权重 (后车被牵连比自身晚点更严重)
    public static final double WEIGHT_COMFORT     = 0.5;   // 舒适度权重 (加速度变化惩罚)

    /** 晚点触发 MPC 优化的最小阈值 (秒)，低于此值使用原规则触发 */
    public static final double MPC_DELAY_THRESHOLD = 30.0;

    /** 预测中使用的简化减速度 (m/s²) */
    private static final double PREDICT_DECEL = 1.0;

    // ═══════════════════════════════════════════════════════════════
    // 候选控制策略定义
    // ═══════════════════════════════════════════════════════════════

    /**
     * 控制策略候选 —— 一组可选的调度干预参数。
     */
    public static class ControlCandidate {
        /** 停站缩短量 (秒) */
        public double dwellCut;
        /** 巡航速度增量 (km/h) */
        public double speedBoost;
        /** 是否甩站 */
        public boolean skipStation;
        /** 该策略的标签说明 */
        public String label;

        public ControlCandidate(double dwellCut, double speedBoost, boolean skipStation, String label) {
            this.dwellCut = dwellCut;
            this.speedBoost = speedBoost;
            this.skipStation = skipStation;
            this.label = label;
        }
    }

    /**
     * 预测轨迹点 —— 预测时域内一个时刻的列车状态快照。
     */
    public static class PredictedPoint {
        public double timeSec;        // 相对于预测起点的秒数
        public double positionM;      // 预测位置 (米)
        public double speedKmh;       // 预测速度 (km/h)
        public int stationIndex;      // 预测所在站索引
        public String phase;          // ACCELERATING | CRUISING | BRAKING | DWELLING
        public double cumulativeDelay; // 累积晚点 (秒)
        public double cumulativeEnergy; // 累积牵引能耗 (kWh)
    }

    /**
     * 策略评估结果 —— 一个候选策略在预测时域内的综合评分。
     */
    public static class StrategyEvaluation {
        public ControlCandidate candidate;
        public List<PredictedPoint> predictedTrajectory;
        public double finalDelaySec;       // 预测终点晚点
        public double totalEnergyKwh;      // 预测时域内总牵引能耗
        public double propagationPenalty;  // 对后车传播影响的惩罚
        public double comfortPenalty;      // 加速度剧烈变化的惩罚
        public double totalCost;           // 综合代价
        public int recoveryLevel;          // 对应的恢复等级 (兼容原有四级模型)
    }

    // ═══════════════════════════════════════════════════════════════

    public PredictiveController(DispatchEngine dispatchEngine) {
        this.dispatchEngine = dispatchEngine;
    }

    // ================================================================
    // 公开入口: 对全线列车执行 MPC 优化
    // ================================================================

    /**
     * 对全线列车执行 MPC 预测优化，返回每列车的最优策略。
     *
     * @param trains          全线列车状态 Map
     * @param simTimeSeconds  当前仿真时刻
     * @param stations        线路车站列表
     * @return trainId → 最优策略评估结果
     */
    public Map<String, StrategyEvaluation> optimizeAll(
            Map<String, TrainState> trains,
            double simTimeSeconds,
            List<LineProfile.Station> stations) {

        Map<String, StrategyEvaluation> results = new LinkedHashMap<>();

        // 仅对晚点超过阈值的列车执行 MPC 优化
        List<TrainState> delayedTrains = trains.values().stream()
                .filter(t -> t.getDelaySeconds() > MPC_DELAY_THRESHOLD)
                .filter(t -> !"FINISHED".equals(t.getStatus()))
                .filter(t -> !"DEPOT_WAITING".equals(t.getStatus()))
                .filter(t -> !"TURNING_BACK".equals(t.getStatus()))
                .collect(Collectors.toList());

        for (TrainState train : delayedTrains) {
            StrategyEvaluation best = optimizeSingleTrain(train, trains, simTimeSeconds, stations);
            if (best != null) {
                results.put(train.getTrainId(), best);
            }
        }

        return results;
    }

    /**
     * 对单列车执行 MPC 优化。
     */
    public StrategyEvaluation optimizeSingleTrain(
            TrainState train,
            Map<String, TrainState> allTrains,
            double simTimeSeconds,
            List<LineProfile.Station> stations) {

        // 1. 生成候选策略集合
        List<ControlCandidate> candidates = generateCandidates(train, stations);

        // 2. 对每个候选策略预测并评估
        List<StrategyEvaluation> evaluations = new ArrayList<>();
        for (ControlCandidate candidate : candidates) {
            StrategyEvaluation eval = evaluateCandidate(
                    train, candidate, allTrains, simTimeSeconds, stations);
            if (eval != null) {
                evaluations.add(eval);
            }
        }

        if (evaluations.isEmpty()) return null;

        // 3. 选择总代价最小的策略
        evaluations.sort(Comparator.comparingDouble(e -> e.totalCost));
        return evaluations.get(0);
    }

    // ================================================================
    // 候选策略生成
    // ================================================================

    /**
     * 根据列车当前晚点程度，生成一组候选控制策略。
     *
     * 策略空间: dwellCut × speedBoost × skipStation 的离散组合。
     * 按晚点程度自适应调整候选范围:
     *   - 轻度晚点 (30-60s): 仅缩停站 0/10/15s + 轻加速 0/3/5 km/h
     *   - 中度晚点 (60-180s): 缩停站 10/20/25s + 加速 3/5/8 km/h
     *   - 重度晚点 (>180s): 缩停站 20/25/30s + 加速 5/8/10 km/h + 考虑甩站
     */
    private List<ControlCandidate> generateCandidates(TrainState train, List<LineProfile.Station> stations) {
        List<ControlCandidate> candidates = new ArrayList<>();
        double delay = train.getDelaySeconds();

        // 添加"不做干预"基线候选
        candidates.add(new ControlCandidate(0, 0, false, "基线(不干预)"));

        if (delay <= 60) {
            // 轻度晚点: 温和策略
            for (double dc : new double[]{10, 15}) {
                for (double sb : new double[]{0, 3, 5}) {
                    candidates.add(new ControlCandidate(dc, sb, false,
                            String.format("轻赶点: 缩站%.0fs+提速%.0fkm/h", dc, sb)));
                }
            }
        } else if (delay <= 180) {
            // 中度晚点: 中强度策略
            for (double dc : new double[]{15, 20, 25}) {
                for (double sb : new double[]{3, 5, 8}) {
                    candidates.add(new ControlCandidate(dc, sb, false,
                            String.format("中赶点: 缩站%.0fs+提速%.0fkm/h", dc, sb)));
                }
            }
            // 加入甩站选项（对非枢纽站）
            int nextIdx = train.getNextStationIndex();
            if (nextIdx >= 0 && nextIdx < stations.size()) {
                int stationId = stations.get(nextIdx).getId();
                double weight = PassengerFlowModel.STATION_WEIGHTS.getOrDefault(stationId, 0.8);
                if (weight < 1.3) { // 非枢纽站才考虑甩站
                    candidates.add(new ControlCandidate(20, 5, true,
                            String.format("中赶点+甩站: %s", stations.get(nextIdx).getName())));
                }
            }
        } else {
            // 重度晚点: 强力策略
            for (double dc : new double[]{20, 25, 30}) {
                for (double sb : new double[]{5, 8, 10}) {
                    candidates.add(new ControlCandidate(dc, sb, false,
                            String.format("强赶点: 缩站%.0fs+提速%.0fkm/h", dc, sb)));
                }
            }
            // 甩站策略（更激进）
            int nextIdx = train.getNextStationIndex();
            if (nextIdx >= 0 && nextIdx < stations.size()) {
                candidates.add(new ControlCandidate(25, 8, true,
                        String.format("强赶点+甩站: %s", stations.get(nextIdx).getName())));
            }
        }

        // 限制候选数
        if (candidates.size() > MAX_CANDIDATES_PER_TRAIN) {
            candidates = candidates.subList(0, MAX_CANDIDATES_PER_TRAIN);
        }

        return candidates;
    }

    // ================================================================
    // 候选策略评估
    // ================================================================

    /**
     * 对单个候选策略在预测时域内进行前向仿真，评估其效果。
     */
    private StrategyEvaluation evaluateCandidate(
            TrainState train,
            ControlCandidate candidate,
            Map<String, TrainState> allTrains,
            double simTimeSeconds,
            List<LineProfile.Station> stations) {

        StrategyEvaluation eval = new StrategyEvaluation();
        eval.candidate = candidate;
        eval.predictedTrajectory = new ArrayList<>();

        // ── 初始化预测状态 (复制当前列车状态) ──
        PredictState state = new PredictState();
        state.pos = train.getPositionMeters();
        state.speed = train.getSpeed();
        state.stationIdx = train.getCurrentStationIndex();
        int nextIdx = train.getNextStationIndex();
        state.phase = "CRUISING"; // 默认运行中
        if (train.getSpeed() < 5 && "DWELLING".equals(train.getStatus())) {
            state.phase = "DWELLING";
        }

        double baseCruiseSpeed = getCruiseSpeed(train);
        double targetCruise = Math.min(baseCruiseSpeed + candidate.speedBoost, 80.0); // 不超80km/h限速
        double accelKmhPerS = DispatchEngine.ACCELERATION_RATE * 3.6 * 0.8; // 预测用简化加速度

        double dwellRemaining = 0;
        if ("DWELLING".equals(state.phase) || "TERMINAL_DWELL".equals(state.phase)) {
            dwellRemaining = Math.max(0, train.getPlannedDwellSeconds() - train.getActualDwellSeconds()
                    - candidate.dwellCut);
        }

        boolean isUp = train.isUpDirection();
        int dirSign = isUp ? 1 : -1;

        double cumulativeDelay = train.getDelaySeconds();
        double cumulativeEnergy = 0;
        double prevAccel = train.getAcceleration();

        // ── 前向预测循环 ──
        for (int t = 0; t <= PREDICTION_HORIZON_SEC; t += PREDICTION_STEP_SEC) {
            double stepSec = PREDICTION_STEP_SEC;

            switch (state.phase) {
                case "DWELLING":
                    dwellRemaining -= stepSec;
                    if (dwellRemaining <= 0) {
                        // 发车
                        state.phase = "ACCELERATING";
                        // 检查是否甩站
                        if (candidate.skipStation && nextIdx >= 0 && nextIdx < stations.size()) {
                            // 甩站: 跳过当前站，直接设下一站为目标
                            if (isUp) nextIdx = Math.min(nextIdx + 1, stations.size() - 1);
                            else nextIdx = Math.max(nextIdx - 1, 0);
                            cumulativeDelay -= dispatchEngine.calcDwellTime(
                                    stations.get(state.stationIdx).getId(), simTimeSeconds + t);
                        }
                    }
                    break;

                case "ACCELERATING":
                    state.speed = Math.min(targetCruise, state.speed + accelKmhPerS * stepSec);
                    cumulativeEnergy += estimateEnergy(state.speed, state.speed - accelKmhPerS * stepSec, stepSec);
                    if (state.speed >= targetCruise - 1) {
                        state.phase = "CRUISING";
                    }
                    state.pos += dirSign * (state.speed / 3.6) * stepSec;
                    break;

                case "CRUISING":
                    state.speed = targetCruise;
                    cumulativeEnergy += estimateEnergy(state.speed, state.speed, stepSec);
                    state.pos += dirSign * (state.speed / 3.6) * stepSec;
                    // 检查是否需要制动进站
                    if (nextIdx >= 0 && nextIdx < stations.size()) {
                        double distToStation = isUp
                                ? (stations.get(nextIdx).getKm() * 1000.0 - state.pos)
                                : (state.pos - stations.get(nextIdx).getKm() * 1000.0);
                        double brakeDist = (state.speed / 3.6) * (state.speed / 3.6) / (2 * PREDICT_DECEL);
                        if (distToStation <= brakeDist * 1.1 && distToStation > 0) {
                            state.phase = "BRAKING";
                        }
                    }
                    break;

                case "BRAKING":
                    state.speed = Math.max(0, state.speed - PREDICT_DECEL * 3.6 * stepSec);
                    cumulativeEnergy += estimateEnergy(state.speed,
                            state.speed + PREDICT_DECEL * 3.6 * stepSec, stepSec);
                    state.pos += dirSign * (state.speed / 3.6) * stepSec;
                    if (state.speed <= 0.5) {
                        // 到站
                        state.speed = 0;
                        state.phase = "DWELLING";
                        state.stationIdx = nextIdx;
                        // 计算停站时间（应用 candidate.dwellCut）
                        if (nextIdx >= 0 && nextIdx < stations.size()) {
                            int stationId = stations.get(nextIdx).getId();
                            double baseDwell = dispatchEngine.calcDwellTime(stationId, simTimeSeconds + t);
                            double minDwell = dispatchEngine.calcMinDwellTime(stationId);
                            dwellRemaining = Math.max(minDwell, baseDwell - candidate.dwellCut);
                        }
                        // 更新到下一站
                        if (isUp) nextIdx = Math.min(nextIdx + 1, stations.size() - 1);
                        else nextIdx = Math.max(nextIdx - 1, 0);

                        // ── 评估晚点: 比较预测到站时间与计划到站时间 ──
                        DispatchEngine.TimetableEntry entry = dispatchEngine.getTimetableEntry(
                                train.getTrainId(), state.stationIdx);
                        if (entry != null && entry.plannedArrival > 0) {
                            double predictedArrival = simTimeSeconds + t;
                            double newDelay = Math.max(0, predictedArrival - entry.plannedArrival);
                            cumulativeDelay = Math.min(cumulativeDelay, newDelay); // 取最优(最小晚点)
                        }
                    }
                    break;
            }

            // ── 记录预测轨迹点 ──
            PredictedPoint pt = new PredictedPoint();
            pt.timeSec = t;
            pt.positionM = state.pos;
            pt.speedKmh = state.speed;
            pt.stationIndex = state.stationIdx;
            pt.phase = state.phase;
            pt.cumulativeDelay = cumulativeDelay;
            pt.cumulativeEnergy = cumulativeEnergy;
            eval.predictedTrajectory.add(pt);

            // 记录加速度变化用于舒适度惩罚
            double currentAccel = (state.speed - (t > 0 ? eval.predictedTrajectory.get(
                    eval.predictedTrajectory.size() - 2 >= 0
                            ? eval.predictedTrajectory.size() - 2 : 0).speedKmh : state.speed)) / stepSec;
            if (t > 0 && Math.abs(currentAccel - prevAccel) > 3.0) {
                eval.comfortPenalty += Math.abs(currentAccel - prevAccel) * WEIGHT_COMFORT;
            }
            prevAccel = currentAccel;
        }

        // ── 计算最终代价 ──
        eval.finalDelaySec = cumulativeDelay;
        eval.totalEnergyKwh = cumulativeEnergy;

        // 传播影响: 预估对后方列车的影响
        eval.propagationPenalty = estimatePropagationImpact(train, allTrains, candidate);

        // 总代价
        eval.totalCost = WEIGHT_DELAY * eval.finalDelaySec
                + WEIGHT_ENERGY * eval.totalEnergyKwh
                + WEIGHT_PROPAGATION * eval.propagationPenalty
                + eval.comfortPenalty;

        // 映射到原有四级恢复等级（兼容现有 DispatchEngine 接口）
        eval.recoveryLevel = mapToRecoveryLevel(candidate);

        return eval;
    }

    // ================================================================
    // 辅助方法
    // ================================================================

    /**
     * 预测状态 —— 用于前向仿真的轻量级列车状态。
     */
    private static class PredictState {
        double pos;
        double speed;
        int stationIdx;
        String phase;
    }

    /**
     * 估算牵引能耗 (kWh) —— 简化版，仅用于 MPC 候选策略比较。
     */
    private double estimateEnergy(double speedKmh, double prevSpeedKmh, double stepSec) {
        if (speedKmh <= 0.5) return 0;
        // 简化: 仅估算加速/巡航能耗，按质量×速度²估算相对量
        double massKg = 6 * 35000.0;
        double speedMs = speedKmh / 3.6;
        double prevSpeedMs = prevSpeedKmh / 3.6;
        double deltaKE = 0.5 * massKg * (speedMs * speedMs - prevSpeedMs * prevSpeedMs);
        if (deltaKE > 0) {
            // 加速: 动能增量 + 阻力功耗
            double resistancePower = massKg * 0.03 * speedMs; // 简化阻力
            return (deltaKE / 1000.0 + resistancePower * stepSec / 1000.0) / 3600.0;
        }
        // 巡航: 仅克服阻力
        double resistancePower = massKg * 0.03 * speedMs;
        return (resistancePower * stepSec / 1000.0) / 3600.0;
    }

    /**
     * 预估该策略对后方列车的传播影响。
     * - 若该策略使本车显著加速，后车间距缩小风险降低 → 低惩罚
     * - 若本车继续慢行，后车被迫跟随慢行 → 高惩罚
     */
    private double estimatePropagationImpact(
            TrainState train, Map<String, TrainState> allTrains, ControlCandidate candidate) {
        // 查找紧邻后方同向列车
        TrainState following = null;
        double minGap = Double.MAX_VALUE;
        for (TrainState other : allTrains.values()) {
            if (other.getTrainId().equals(train.getTrainId())) continue;
            if (!train.getDirection().equals(other.getDirection())) continue;
            double gap = train.isUpDirection()
                    ? train.getPositionMeters() - other.getPositionMeters()
                    : other.getPositionMeters() - train.getPositionMeters();
            if (gap > 0 && gap < minGap) {
                minGap = gap;
                following = other;
            }
        }

        if (following == null) return 0;

        // 预测间距变化: 速度越快间距越大，后车受影响越小
        double currentSpeed = train.getSpeed();
        double predictedSpeed = currentSpeed + candidate.speedBoost;
        double speedRatio = predictedSpeed / Math.max(1, currentSpeed);
        double safeDist = dispatchEngine.calcSafeDistance(following.getSpeed() / 3.6);
        double gapRatio = minGap / Math.max(1, safeDist);

        // 间距越小 + 速度提升越少 → 传播风险越大
        if (gapRatio < 0.6 && speedRatio < 1.05) {
            return 30.0; // 高传播风险
        } else if (gapRatio < 0.8) {
            return 15.0;
        }
        return 5.0;
    }

    /**
     * 将 MPC 候选策略映射到原有四级恢复等级。
     */
    private int mapToRecoveryLevel(ControlCandidate candidate) {
        if (candidate.dwellCut <= 0 && candidate.speedBoost <= 0 && !candidate.skipStation) {
            return -1; // 不干预
        }
        double intensity = candidate.dwellCut + candidate.speedBoost * 2; // 综合强度
        if (candidate.skipStation) intensity += 20;
        if (intensity <= 20) return DispatchEngine.RECOVERY_LIGHT;
        if (intensity <= 35) return DispatchEngine.RECOVERY_MODERATE;
        if (intensity <= 50) return DispatchEngine.RECOVERY_AGGRESSIVE;
        return DispatchEngine.RECOVERY_CRITICAL;
    }

    /**
     * 获取列车当前运行等级的巡航速度。
     */
    private double getCruiseSpeed(TrainState train) {
        String level = train.getOperationLevel();
        if (OperationLevel.ENERGY_SAVE.equals(level)) return 55.0;
        if (OperationLevel.EXPRESS.equals(level)) return 75.0;
        if (OperationLevel.SLOW.equals(level)) return 45.0;
        return 70.0; // NORMAL
    }

    /**
     * 将 MPC 最优策略转换为 DispatchEngine 兼容的 TrainCommand。
     *
     * @param eval       最优策略评估结果
     * @param trainId    目标列车ID
     * @param simTime    当前仿真时刻
     * @return 兼容的 TrainCommand
     */
    public SimulationSnapshot.TrainCommand toTrainCommand(
            StrategyEvaluation eval, String trainId, double simTime) {
        SimulationSnapshot.TrainCommand cmd = new SimulationSnapshot.TrainCommand();
        cmd.setTrainId(trainId);
        cmd.setCommandType("SPEED_UP");
        cmd.setTargetValue(eval.recoveryLevel >= 0 ? eval.recoveryLevel : 0);
        cmd.setIssuedTime(simTime);
        cmd.setStatus("PENDING");

        ControlCandidate c = eval.candidate;
        StringBuilder reason = new StringBuilder();
        reason.append(String.format("[MPC优化] %s → 预计恢复%.0fs",
                c.label, Math.max(0, eval.finalDelaySec - eval.predictedTrajectory.get(0).cumulativeDelay)));
        reason.append(String.format(" | 代价=%.1f", eval.totalCost));
        if (c.skipStation) reason.append(" | 含甩站策略");
        cmd.setReason(reason.toString());
        return cmd;
    }

    /**
     * 获取预测时域内各时刻的最优轨迹预览（供前端可视化）。
     */
    public List<Map<String, Object>> getTrajectoryPreview(StrategyEvaluation eval) {
        List<Map<String, Object>> preview = new ArrayList<>();
        for (PredictedPoint pt : eval.predictedTrajectory) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("timeSec", pt.timeSec);
            point.put("positionM", Math.round(pt.positionM * 10) / 10.0);
            point.put("speedKmh", Math.round(pt.speedKmh * 10) / 10.0);
            point.put("stationIndex", pt.stationIndex);
            point.put("phase", pt.phase);
            point.put("cumulativeDelay", Math.round(pt.cumulativeDelay * 10) / 10.0);
            preview.add(point);
        }
        return preview;
    }
}
