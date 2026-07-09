package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.domain.model.*;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 核心调度引擎 —— 整合时刻表生成、客流模型、安全间隔(ATP)、站停时间、晚点检测与指令生成。
 *
 * 架构对应实际 CBTC 系统:
 *   - TimetablePlanner  → ATS 时刻表管理
 *   - SafetyEnforcer     → ATP 列车自动保护
 *   - PassengerFlowModel → 客流检测
 *   - CommandGenerator   → 调度指令生成
 *
 * 参考:
 *   - IEEE 1474.1 CBTC 性能与功能要求
 *   - 基于动态安全间隔的城轨列车追踪运行仿真研究 (交通运输系统工程与信息, 2025)
 *   - Headway Management Strategies for High-Frequency Urban Rail Transit (TRR, 2026)
 */
@Service
public class DispatchEngine {

    private final PassengerFlowModel flowModel;
    private final List<SimulationSnapshot.DelayEvent> delayEventLog = new ArrayList<>();

    // ═══════════════════════════════════════════════════════════════
    // 物理/线路参数
    // ═══════════════════════════════════════════════════════════════

    public static final double SERVICE_BRAKE_DECEL  = 1.0;   // 常用制动减速度 m/s²
    public static final double EMERGENCY_BRAKE_DECEL = 1.3;  // 紧急制动减速度 m/s²
    public static final double ACCELERATION_RATE    = 1.0;   // 起动加速度 m/s²
    public static final double JERK_LIMIT           = 0.8;   // 冲击率限制 m/s³
    public static final double REACTION_TIME        = 2.5;   // 系统反应+通信延迟 秒
    public static final double SAFETY_MARGIN        = 30.0;  // ATP 安全保护距离 米
    public static final double TRAIN_LENGTH         = 6 * 19.0; // 6B编组长度 米
    public static final double MAX_SPEED            = 80.0;  // 线路最高限速 km/h
    public static final double STATION_APPROACH_DIST = 300.0; // 进站制动起点距站台 米
    public static final double TIMETABLE_SPEED       = 70.0;  // 时刻表计算用巡航速度 km/h (匹配仿真CRUISE_SPEED)

    // ═══════════════════════════════════════════════════════════════
    // 站停参数 (基于北京地铁实际数据调参)
    // ═══════════════════════════════════════════════════════════════

    public static final double DWELL_BASE          = 30.0;  // 平峰基准停站时间 秒
    public static final double DWELL_PEAK_EXTRA    = 15.0;  // 高峰附加 秒
    public static final double DWELL_HUB_EXTRA     = 20.0;  // 枢纽站附加 秒
    public static final double DWELL_CROWDED_EXTRA = 10.0;  // 拥挤附加 秒
    public static final double DWELL_MIN           = 15.0;  // 最小停站(赶点下限) 秒
    public static final double DWELL_MAX           = 90.0;  // 最大停站 秒

    // ═══════════════════════════════════════════════════════════════
    // 晚点参数
    // ═══════════════════════════════════════════════════════════════

    public static final double DELAY_THRESHOLD     = 60.0;  // 晚点判定阈值 秒
    public static final double RECOVERY_THRESHOLD  = 15.0;  // 恢复判定阈值 秒
    public static final double RECOVERY_MARGIN     = 0.05;  // 区间运行可压缩比例 (赶点)

    /**
     * 多级赶点恢复模型。targetValue 编码恢复等级:
     *   0 = RECOVERY_LIGHT   (15–60s 轻度晚点) 缩停站 -15s, 不加速
     *   1 = RECOVERY_MODERATE (60–180s 中度晚点) 缩停站 -20s, 提速至 75 km/h
     *   2 = RECOVERY_AGGRESSIVE(180–360s 重度晚点) 缩停站 -25s, 提速至 78 km/h, 非枢纽站允许甩站
     *   3 = RECOVERY_CRITICAL (>360s 严重晚点) 缩停站 -30s, 提速至 80 km/h, 自动甩站
     */
    public static final int RECOVERY_LIGHT      = 0;
    public static final int RECOVERY_MODERATE   = 1;
    public static final int RECOVERY_AGGRESSIVE = 2;
    public static final int RECOVERY_CRITICAL   = 3;

    /** 各恢复等级的站停缩短量 */
    public static final double[] RECOVERY_DWELL_CUTS = {15.0, 20.0, 25.0, 30.0};
    /** 各恢复等级的巡航速度增量 km/h (叠加在 TIMETABLE_SPEED=70 上) */
    public static final double[] RECOVERY_SPEED_BOOST = {0.0, 5.0, 8.0, 10.0};
    /** 各恢复等级的晚点时间窗上界 */
    public static final double[] RECOVERY_UPPER_BOUND = {60.0, 180.0, 360.0, Double.MAX_VALUE};

    // ═══════════════════════════════════════════════════════════════
    // 时刻表缓存
    // ═══════════════════════════════════════════════════════════════

    /** 预计算的完整时刻表: trainId → stationIndex → TimetableEntry */
    private Map<String, Map<Integer, TimetableEntry>> timetable;

    /** 区间计划运行时间: fromStationIndex → 运行秒数 */
    private Map<Integer, Double> sectionRunTimes;

    public DispatchEngine() {
        this.flowModel = new PassengerFlowModel();
    }

    public PassengerFlowModel getFlowModel() { return flowModel; }

    // ═══════════════════════════════════════════════════════════════
    // 折返参数
    // ═══════════════════════════════════════════════════════════════

    public static final double TURNAROUND_TIME = 180.0; // 标准折返时间 秒

    // ================================================================
    // 车次号生成规则
    // ================================================================

    /**
     * 城轨车次号规则: 线路号(2位) + 方向(1位, 1=上行 2=下行) + 列车序号(2位)
     * 示例: 90101 = 9号线, 上行, 第01次列车
     *       90203 = 9号线, 下行, 第03次列车
     */
    public static String generateTrainNumber(int lineNumber, boolean isUp, int trainSeq) {
        int dirCode = isUp ? 1 : 2;
        return String.format("%d%d%02d", lineNumber, dirCode, trainSeq);
    }

    /**
     * 计算折返所需时间 (考虑时段)
     */
    public double calcTurnbackTime(double simTimeSeconds) {
        PassengerFlowModel.TimePeriod period = flowModel.getCurrentPeriod(simTimeSeconds);
        if (period == PassengerFlowModel.TimePeriod.MORNING_PEAK
                || period == PassengerFlowModel.TimePeriod.EVENING_PEAK) {
            return RoutePattern.TURNAROUND_PEAK;
        }
        return RoutePattern.TURNAROUND_NORM;
    }

    // ================================================================
    // 第1步: 生成完整时刻表 (ATS Timetable Generation)
    // ================================================================

    /**
     * 基于线路参数和客流生成每列车全时刻表 (支持双向+折返)。
     * 包括: 计划发车时刻、各站计划到站/发车时刻、各区间计划运行时分。
     * 上行: 0→12 站索引递增; 下行: 12→0 站索引递减。
     */
    public void generateTimetable(LineProfile lineProfile, int trainCount, double simTimeSeconds) {
        timetable = new LinkedHashMap<>();
        sectionRunTimes = new LinkedHashMap<>();

        List<LineProfile.Station> stations = lineProfile.getStations();
        int stationCount = stations.size();
        double headway = flowModel.calculateDemandHeadway(simTimeSeconds);
        headway = PassengerFlowModel.clamp(headway, PassengerFlowModel.MIN_HEADWAY_SEC, PassengerFlowModel.MAX_HEADWAY_SEC);

        // ── 预计算各区间运行时间 ──
        for (int i = 0; i < stationCount - 1; i++) {
            double distKm = stations.get(i + 1).getKm() - stations.get(i).getKm();
            double distMeters = distKm * 1000.0;
            // 简化的运行时间计算: 加速 + 巡航 + 制动
            double accelTime = (TIMETABLE_SPEED / 3.6) / ACCELERATION_RATE;       // 加速到巡航速度时间
            double accelDist = 0.5 * ACCELERATION_RATE * accelTime * accelTime; // 加速距离
            double brakeTime = (TIMETABLE_SPEED / 3.6) / SERVICE_BRAKE_DECEL;
            double brakeDist = 0.5 * SERVICE_BRAKE_DECEL * brakeTime * brakeTime;
            double cruiseDist = distMeters - accelDist - brakeDist;

            double runTime;
            if (cruiseDist > 0) {
                runTime = accelTime + (cruiseDist / (TIMETABLE_SPEED / 3.6)) + brakeTime;
            } else {
                // 短区间: 纯加减速
                double peakSpeed = Math.sqrt(distMeters * ACCELERATION_RATE * SERVICE_BRAKE_DECEL
                        / (ACCELERATION_RATE + SERVICE_BRAKE_DECEL));
                runTime = peakSpeed / ACCELERATION_RATE + peakSpeed / SERVICE_BRAKE_DECEL;
            }
            sectionRunTimes.put(i, runTime);
        }

        // ── 生成每列车时刻表 ──
        for (int t = 0; t < trainCount; t++) {
            String trainId = "T" + (t + 1);
            double departureTime = t * headway;
            Map<Integer, TimetableEntry> trainSchedule = new LinkedHashMap<>();

            double currentTime = departureTime;
            for (int s = 0; s < stationCount; s++) {
                TimetableEntry entry = new TimetableEntry();
                entry.stationId = stations.get(s).getId();
                entry.stationName = stations.get(s).getName();
                entry.stationIndex = s;
                entry.stationKm = stations.get(s).getKm();

                if (s == 0) {
                    // 始发站
                    entry.plannedArrival = currentTime;
                    double dwell = calcDwellTime(stations.get(s).getId(), currentTime);
                    entry.plannedDwell = dwell;
                    entry.plannedDeparture = currentTime + dwell;
                } else {
                    // 中间站/终点站
                    double runTime = sectionRunTimes.getOrDefault(s - 1, 120.0);
                    currentTime += runTime;
                    entry.plannedArrival = currentTime;
                    double dwell = (s == stationCount - 1) ? 0 : calcDwellTime(stations.get(s).getId(), currentTime);
                    entry.plannedDwell = dwell;
                    entry.plannedDeparture = currentTime + dwell;
                }
                currentTime = entry.plannedDeparture;
                trainSchedule.put(s, entry);
            }
            timetable.put(trainId, trainSchedule);
        }
    }

    /**
     * 获取列车在某站点的时刻表项
     */
    public TimetableEntry getTimetableEntry(String trainId, int stationIndex) {
        if (timetable == null) return null;
        Map<Integer, TimetableEntry> ts = timetable.get(trainId);
        if (ts == null) return null;
        return ts.get(stationIndex);
    }

    /**
     * 获取某区间计划运行时间
     */
    public double getSectionRunTime(int fromStationIndex) {
        return sectionRunTimes.getOrDefault(fromStationIndex, 120.0);
    }

    /**
     * 获取完整时刻表 (供外部构建计划运行图)
     */
    public Map<String, Map<Integer, TimetableEntry>> getTimetable() { return timetable; }

    /**
     * 获取区间运行时间表
     */
    public Map<Integer, Double> getSectionRunTimes() { return sectionRunTimes; }

    // ================================================================
    // 第2步: 客流 → 发车间隔 (6.1.1)
    // ================================================================

    public DispatchResult evaluateDispatch(double simTimeSeconds, int currentOnlineTrains) {
        DispatchResult result = new DispatchResult();
        double demandHeadway = flowModel.calculateDemandHeadway(simTimeSeconds);
        int requiredTrains = flowModel.calculateRequiredTrains(demandHeadway);

        result.recommendedHeadway = demandHeadway;
        result.requiredTrains = requiredTrains;
        result.maxAvailableTrains = PassengerFlowModel.AVAILABLE_TRAINS;
        result.onlineTrains = currentOnlineTrains;
        result.fleetSufficient = requiredTrains <= PassengerFlowModel.AVAILABLE_TRAINS;

        if (!result.fleetSufficient) {
            result.dispatchMode = "EMERGENCY";
            result.message = String.format("车辆不足：需要%d列/可用%d列", requiredTrains, PassengerFlowModel.AVAILABLE_TRAINS);
        } else if (demandHeadway < PassengerFlowModel.MIN_HEADWAY_SEC) {
            result.dispatchMode = "COMPRESS";
            result.recommendedHeadway = PassengerFlowModel.MIN_HEADWAY_SEC;
            result.message = String.format("高密度运行，间隔%.0fs", PassengerFlowModel.MIN_HEADWAY_SEC);
        } else if (demandHeadway > PassengerFlowModel.MAX_HEADWAY_SEC) {
            result.dispatchMode = "STRETCH";
            result.message = "低客流，扩大间隔";
        } else {
            result.dispatchMode = "NORMAL";
            result.message = "正常运营";
        }
        return result;
    }

    // ================================================================
    // 第3步: ATP 安全间隔模型 (6.2)
    // ================================================================

    /**
     * CBTC 移动闭塞安全制动距离。
     * S_safe = v²/(2b) + v·t_react + S_margin + L_train
     */
    public double calcBrakingDistance(double speedMs, double decel) {
        if (speedMs <= 0 || decel <= 0) return 0;
        return (speedMs * speedMs) / (2 * decel);
    }

    public double calcSafeDistance(double speedMs) {
        double brakeDist = calcBrakingDistance(speedMs, EMERGENCY_BRAKE_DECEL);
        double reactDist = speedMs * REACTION_TIME;
        return brakeDist + reactDist + SAFETY_MARGIN + TRAIN_LENGTH;
    }

    /**
     * 计算后车的移动授权终点 (Movement Authority)。
     * MA = 前车位置 - 前车制动距离 - 安全余量 - 列车长度
     * 如果后车位置 >= MA，则 EB (紧急制动)。
     */
    public double calcMovementAuthority(TrainState leading, TrainState following) {
        double leadingSpeedMs = leading.getSpeed() / 3.6;
        double leadingBrakeDist = calcBrakingDistance(leadingSpeedMs, EMERGENCY_BRAKE_DECEL);
        return leading.getPositionMeters() - leadingBrakeDist - SAFETY_MARGIN - TRAIN_LENGTH;
    }

    public boolean needsEmergencyBrake(TrainState following, double movementAuthority) {
        double followingBrakeDist = calcBrakingDistance(following.getSpeed() / 3.6, EMERGENCY_BRAKE_DECEL);
        double stopPosition = following.getPositionMeters() + followingBrakeDist;
        return stopPosition >= movementAuthority;
    }

    public SpacingResult evaluateSpacing(double distanceMeters, double followingSpeedMs) {
        SpacingResult sr = new SpacingResult();
        sr.safetyDistance = calcSafeDistance(followingSpeedMs);
        sr.actualDistance = distanceMeters;

        if (distanceMeters < sr.safetyDistance * 0.5) {
            sr.level = "DANGER";
            sr.command = "EMERGENCY_BRAKE";
        } else if (distanceMeters < sr.safetyDistance * 0.8) {
            sr.level = "WARNING";
            sr.command = "SLOW";
        } else if (distanceMeters < sr.safetyDistance) {
            sr.level = "CAUTION";
            sr.command = "RUN";
        } else {
            sr.level = "SAFE";
            sr.command = "RUN";
        }
        return sr;
    }

    // ================================================================
    // 第4步: 站停时间模型 (6.3)
    // ================================================================

    /**
     * D_i(t) = D_base + α×高峰 + β×枢纽 + γ×拥挤 + δ×换乘
     */
    public double calcDwellTime(int stationId, double simTimeSeconds) {
        double dwell = DWELL_BASE;

        PassengerFlowModel.TimePeriod period = flowModel.getCurrentPeriod(simTimeSeconds);
        double stationWeight = PassengerFlowModel.STATION_WEIGHTS.getOrDefault(stationId, 0.8);

        // 高峰附加
        if (period == PassengerFlowModel.TimePeriod.MORNING_PEAK
                || period == PassengerFlowModel.TimePeriod.EVENING_PEAK) {
            dwell += DWELL_PEAK_EXTRA;
        }

        // 枢纽站/换乘站附加
        if (stationWeight >= 1.8) {
            dwell += DWELL_HUB_EXTRA;        // 北京西站级别
        } else if (stationWeight >= 1.3) {
            dwell += DWELL_HUB_EXTRA * 0.75; // 六里桥、国图、军博
        } else if (stationWeight >= 1.0) {
            dwell += DWELL_HUB_EXTRA * 0.3;  // 普通换乘站
        }

        // 拥挤附加 (高峰+大站)
        if ((period == PassengerFlowModel.TimePeriod.MORNING_PEAK
                || period == PassengerFlowModel.TimePeriod.EVENING_PEAK) && stationWeight >= 1.2) {
            dwell += DWELL_CROWDED_EXTRA;
        }

        return PassengerFlowModel.clamp(dwell, DWELL_MIN, DWELL_MAX);
    }

    // ================================================================
    // 第5步: 晚点检测、多级赶点恢复、协同传播模型 (6.4)
    // ================================================================

    /**
     * 基于实际到站时刻 vs 计划到站时刻计算晚点。
     */
    public double computeDelay(TrainState train) {
        if (timetable == null) return 0;
        int stationIdx = train.getCurrentStationIndex();
        if (stationIdx < 0) return 0;
        TimetableEntry entry = getTimetableEntry(train.getTrainId(), stationIdx);
        if (entry == null) return 0;
        double planned = entry.plannedArrival;
        double actual = train.getActualArrivalAtStation();
        if (actual > 0 && planned > 0) {
            return Math.max(0, actual - planned);
        }
        return 0;
    }

    /**
     * 计算列车赶点恢复潜力 (秒)。
     * 恢复潜力 = Σ(后续各站可缩短停站时间) + Σ(后续各区间可通过提速节省的时间)
     *
     * @param recoveryLevel 恢复等级 (0-3)
     * @param remainingStops 剩余停站数
     * @param remainingDistanceKm 剩余运行距离
     */
    public double calcRecoveryPotential(int recoveryLevel, int remainingStops, double remainingDistanceKm) {
        if (recoveryLevel < 0 || remainingStops <= 0) return 0;
        int level = Math.min(recoveryLevel, RECOVERY_CRITICAL);

        // 停站缩短的恢复量
        double dwellRecovery = remainingStops * RECOVERY_DWELL_CUTS[level];

        // 提速节省的区间运行时间
        // 原运行时间 ≈ distance / (TIMETABLE_SPEED/3.6)
        // 新运行时间 ≈ distance / ((TIMETABLE_SPEED+boost)/3.6)
        // 节省 = 原时间 - 新时间
        double boost = RECOVERY_SPEED_BOOST[level];
        double speedRecovery = 0;
        if (boost > 0 && remainingDistanceKm > 0) {
            double origTime = (remainingDistanceKm * 1000.0) / (TIMETABLE_SPEED / 3.6);
            double newTime = (remainingDistanceKm * 1000.0) / ((TIMETABLE_SPEED + boost) / 3.6);
            speedRecovery = Math.max(0, origTime - newTime);
        }

        // 甩站节省 (AGGRESSIVE和CRITICAL级别, 假设跳过30%-60%非枢纽站)
        double skipRecovery = 0;
        if (level >= RECOVERY_AGGRESSIVE) {
            int skipCount = level == RECOVERY_CRITICAL ? Math.max(1, remainingStops / 2) : Math.max(1, remainingStops / 3);
            double avgDwell = DWELL_BASE + DWELL_PEAK_EXTRA * 0.3; // 平均停站时间
            double avgRunTime = remainingDistanceKm > 0 && remainingStops > 0
                    ? (remainingDistanceKm * 1000.0 / remainingStops) / (TIMETABLE_SPEED / 3.6) : 100;
            skipRecovery = skipCount * (avgDwell + avgRunTime * 0.3);
        }

        return dwellRecovery + speedRecovery + skipRecovery;
    }

    /**
     * 根据晚点秒数判定恢复等级。
     */
    public int getRecoveryLevel(double delaySeconds) {
        if (delaySeconds <= 0) return -1;
        for (int i = 0; i < RECOVERY_UPPER_BOUND.length; i++) {
            if (delaySeconds <= RECOVERY_UPPER_BOUND[i]) return i;
        }
        return RECOVERY_CRITICAL;
    }

    /**
     * 全量晚点评估 (所有列车 vs 时刻表) —— 多级赶点版本。
     * 根据晚点程度自动分配合适的恢复策略:
     *   LIGHT→仅缩停站, MODERATE→缩停站+提速, AGGRESSIVE→缩停站+提速+甩站, CRITICAL→全力恢复
     */
    public DelayResult evaluateAllDelays(List<TrainState> trains, double simTimeSeconds) {
        DelayResult dr = new DelayResult();
        dr.commands = new ArrayList<>();
        dr.events = new ArrayList<>();

        for (TrainState train : trains) {
            if ("FINISHED".equals(train.getStatus()) || "DEPOT_WAITING".equals(train.getStatus())) continue;

            double delay = computeDelay(train);
            train.setDelaySeconds(delay);

            if (delay > DELAY_THRESHOLD) {
                int level = getRecoveryLevel(delay);
                String levelLabel = level == RECOVERY_LIGHT ? "轻度" : level == RECOVERY_MODERATE ? "中度"
                        : level == RECOVERY_AGGRESSIVE ? "重度" : "严重";

                SimulationSnapshot.DelayEvent event = new SimulationSnapshot.DelayEvent();
                event.setTimeSeconds(simTimeSeconds);
                event.setTrainId(train.getTrainId());
                event.setDelaySeconds(delay);
                event.setCause("运行晚点(" + levelLabel + ")");
                event.setPositionKm(train.getPositionMeters() / 1000.0);
                event.setEventType("PRIMARY_DELAY");
                dr.events.add(event);

                SimulationSnapshot.TrainCommand cmd = new SimulationSnapshot.TrainCommand();
                cmd.setTrainId(train.getTrainId());
                cmd.setCommandType("SPEED_UP");
                cmd.setTargetValue(level); // 编码恢复等级
                cmd.setReason(String.format("%s晚点%.0fs → %s赶点: 缩停站-%.0fs%s%s",
                        levelLabel, delay,
                        level == RECOVERY_LIGHT ? "L1" : level == RECOVERY_MODERATE ? "L2" : level == RECOVERY_AGGRESSIVE ? "L3" : "L4",
                        RECOVERY_DWELL_CUTS[level],
                        RECOVERY_SPEED_BOOST[level] > 0 ? String.format(", 区间提速+%.0fkm/h", RECOVERY_SPEED_BOOST[level]) : "",
                        level >= RECOVERY_AGGRESSIVE ? ", 允许甩站" : ""));
                dr.commands.add(cmd);

            } else if (delay > 0 && delay < RECOVERY_THRESHOLD) {
                SimulationSnapshot.DelayEvent event = new SimulationSnapshot.DelayEvent();
                event.setTimeSeconds(simTimeSeconds);
                event.setTrainId(train.getTrainId());
                event.setDelaySeconds(delay);
                event.setCause("晚点已恢复至" + String.format("%.0f", delay) + "s");
                event.setPositionKm(train.getPositionMeters() / 1000.0);
                event.setEventType("RECOVERED");
                dr.events.add(event);
            }
        }
        return dr;
    }

    /**
     * 晚点传播评估 —— 协同优化版本。
     *
     * 传统做法: 前车晚点 → 无条件给后车 SLOW/HOLD。
     * 协同优化: 先判断前车的恢复潜力。若前车能在剩余区间内自行恢复，则后车仅用轻度限速；
     * 若前车恢复无望，则后车用更强力的减速/扣车以避免追尾和晚点传播链。
     *
     * 前车恢复潜力 = calcRecoveryPotential(level, remainingStops, remainingDist)
     * - 潜力 ≥ 当前晚点 → 前车可自愈, 后车仅 SLOW_LIGHT (限速到 80% 当前速度)
     * - 潜力 < 当前晚点 → 前车无法自愈, 后车按传统逻辑 SLOW/HOLD
     */
    public DelayResult evaluateDelayPropagation(List<TrainState> sortedTrains, double simTimeSeconds) {
        DelayResult dr = new DelayResult();
        dr.commands = new ArrayList<>();
        dr.events = new ArrayList<>();

        for (int i = 0; i < sortedTrains.size() - 1; i++) {
            TrainState following = sortedTrains.get(i);
            TrainState leading = sortedTrains.get(i + 1);

            if ("FINISHED".equals(leading.getStatus()) || "FINISHED".equals(following.getStatus())) continue;

            if (leading.getDelaySeconds() > DELAY_THRESHOLD) {
                double gap = leading.getPositionMeters() - following.getPositionMeters();
                // 方向感知: 若两车方向不同，不触发常规传播
                if (gap < 0) continue;
                double safeDist = calcSafeDistance(following.getSpeed() / 3.6);

                if (gap < safeDist * 0.9) {
                    // ── 评估前车恢复潜力 ──
                    int leadLevel = getRecoveryLevel(leading.getDelaySeconds());
                    int totalStations = 13; // 北京地铁9号线
                    int remainingStops;
                    double remainingDistKm;
                    if (leading.isUpDirection()) {
                        remainingStops = Math.max(1, totalStations - leading.getCurrentStationIndex() - 1);
                        remainingDistKm = 16.05 - (leading.getPositionMeters() / 1000.0);
                    } else {
                        remainingStops = Math.max(1, leading.getCurrentStationIndex());
                        remainingDistKm = leading.getPositionMeters() / 1000.0;
                    }
                    double recoveryPotential = calcRecoveryPotential(leadLevel, remainingStops, remainingDistKm);

                    SimulationSnapshot.TrainCommand cmd = new SimulationSnapshot.TrainCommand();
                    cmd.setTrainId(following.getTrainId());

                    if (gap < safeDist * 0.4) {
                        // 紧急间距: 必须扣车
                        cmd.setCommandType("HOLD");
                        cmd.setReason(String.format("前车%s晚点%.0fs, 紧急扣车(间距%.0f/安全%.0f, 预测恢复%.0fs)",
                                leading.getTrainId(), leading.getDelaySeconds(), gap, safeDist, recoveryPotential));
                    } else if (recoveryPotential >= leading.getDelaySeconds() * 0.7) {
                        // 前车可恢复: 轻度限速, 避免误伤后车
                        cmd.setCommandType("SLOW");
                        double mildSpeed = Math.max(45, following.getSpeed() * 0.8);
                        cmd.setTargetValue(mildSpeed);
                        cmd.setReason(String.format("前车%s晚点%.0fs但可自愈(潜力%.0fs), 后车轻度限速%.0fkm/h",
                                leading.getTrainId(), leading.getDelaySeconds(), recoveryPotential, mildSpeed));
                    } else {
                        // 前车无法恢复: 强力减速
                        cmd.setCommandType("SLOW");
                        double limitSpeed = Math.max(25, following.getSpeed() * 0.5);
                        cmd.setTargetValue(limitSpeed);
                        cmd.setReason(String.format("前车%s晚点%.0fs且恢复潜力不足(潜力%.0fs), 后车限速%.0fkm/h",
                                leading.getTrainId(), leading.getDelaySeconds(), recoveryPotential, limitSpeed));
                    }
                    dr.commands.add(cmd);

                    SimulationSnapshot.DelayEvent event = new SimulationSnapshot.DelayEvent();
                    event.setTimeSeconds(simTimeSeconds);
                    event.setTrainId(following.getTrainId());
                    event.setDelaySeconds(following.getDelaySeconds());
                    event.setCause("前车" + leading.getTrainId() + "晚点(" + String.format("%.0f", leading.getDelaySeconds()) + "s)传播"
                            + (recoveryPotential >= leading.getDelaySeconds() * 0.7 ? " [协同优化: 后车仅轻度限速]" : ""));
                    event.setAffectedTrainId(leading.getTrainId());
                    event.setPositionKm(following.getPositionMeters() / 1000.0);
                    event.setEventType("PROPAGATED");
                    dr.events.add(event);
                }
            }
        }
        return dr;
    }

    // ================================================================
    // 日志管理
    // ================================================================

    public void logDelayEvents(List<SimulationSnapshot.DelayEvent> events) {
        delayEventLog.addAll(events);
    }
    public List<SimulationSnapshot.DelayEvent> getDelayEventLog() {
        return new ArrayList<>(delayEventLog);
    }
    public void clearLogs() {
        delayEventLog.clear();
    }

    // ================================================================
    // 内嵌 DTO
    // ================================================================

    /** 时刻表条目 —— 一列车在一个站点的计划时刻 */
    public static class TimetableEntry {
        public int stationId;
        public String stationName;
        public int stationIndex;
        public double stationKm;
        public double plannedArrival;    // 计划到站时刻 (sim seconds)
        public double plannedDeparture;  // 计划发车时刻
        public double plannedDwell;      // 计划停站时间
    }

    public static class DispatchResult {
        public double recommendedHeadway;
        public int requiredTrains;
        public int maxAvailableTrains;
        public int onlineTrains;
        public boolean fleetSufficient;
        public String dispatchMode;
        public String message;
    }

    public static class SpacingResult {
        public double safetyDistance;
        public double actualDistance;
        public String level;    // SAFE | CAUTION | WARNING | DANGER
        public String command;  // RUN | SLOW | EMERGENCY_BRAKE
    }

    public static class DelayResult {
        public List<SimulationSnapshot.TrainCommand> commands;
        public List<SimulationSnapshot.DelayEvent> events;
    }
}
