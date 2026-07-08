package com.bjtu.railtransit.evaluation;

import com.bjtu.railtransit.domain.model.ComfortResult;
import com.bjtu.railtransit.domain.model.SimulationLog;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 舒适性评估器
 * 基于加速度、减速度、jerk 计算舒适性评分
 * 参考标准：EN 12299 铁路应用-乘坐舒适性
 */
@Service
public class ComfortEvaluator {

    // 舒适性阈值参考 (m/s²)
    private static final double ACC_EXCELLENT = 0.8;    // 优秀
    private static final double ACC_GOOD = 1.0;          // 良好
    private static final double ACC_ACCEPTABLE = 1.3;    // 可接受
    private static final double DEC_EXCELLENT = 0.8;
    private static final double DEC_GOOD = 1.0;
    private static final double DEC_ACCEPTABLE = 1.3;
    private static final double JERK_EXCELLENT = 0.5;    // m/s³
    private static final double JERK_GOOD = 0.8;
    private static final double JERK_ACCEPTABLE = 1.2;

    /**
     * 评估舒适性
     */
    public ComfortResult evaluate(List<SimulationLog> logs) {
        ComfortResult result = new ComfortResult();

        if (logs == null || logs.isEmpty()) {
            result.setComfortScore(100);
            result.setComfortLevel("excellent");
            return result;
        }

        // 按列车分组，每组按时间排序
        Map<Integer, List<SimulationLog>> grouped = logs.stream()
                .collect(Collectors.groupingBy(SimulationLog::getTrainId));

        double globalMaxAcc = 0;
        double globalMaxDec = 0;
        double globalMaxJerk = 0;
        double totalScore = 0;
        int trainCount = 0;

        for (List<SimulationLog> trainLogs : grouped.values()) {
            trainLogs.sort(Comparator.comparingLong(SimulationLog::getTimestamp));

            double maxAcc = 0;
            double maxDec = 0;
            double maxJerk = 0;
            double prevAcc = 0;
            long prevTs = -1;

            for (SimulationLog log : trainLogs) {
                // 从牵引力、制动力、载重反推加速度
                double acc = calculateAcceleration(log);
                if (acc > maxAcc) maxAcc = acc;
                if (acc < maxDec) maxDec = acc;

                // 计算 jerk (加速度变化率)
                if (prevTs > 0) {
                    double dt = (log.getTimestamp() - prevTs) / 1000.0; // ms → s
                    if (dt > 0.001) {
                        double jerk = Math.abs(acc - prevAcc) / dt;
                        if (jerk > maxJerk) maxJerk = jerk;
                    }
                }
                prevAcc = acc;
                prevTs = log.getTimestamp();
            }

            globalMaxAcc = Math.max(globalMaxAcc, maxAcc);
            globalMaxDec = Math.min(globalMaxDec, maxDec);
            globalMaxJerk = Math.max(globalMaxJerk, maxJerk);

            // 各车评分
            double trainScore = calculateTrainScore(maxAcc, Math.abs(maxDec), maxJerk);
            totalScore += trainScore;
            trainCount++;
        }

        result.setMaxAcceleration(globalMaxAcc);
        result.setMaxDeceleration(Math.abs(globalMaxDec));
        result.setMaxJerk(globalMaxJerk);

        double finalScore = trainCount > 0 ? totalScore / trainCount : 100;
        result.setComfortScore(Math.max(0, Math.min(100, finalScore)));

        // 确定等级
        if (finalScore >= 85) result.setComfortLevel("excellent");
        else if (finalScore >= 70) result.setComfortLevel("good");
        else if (finalScore >= 50) result.setComfortLevel("acceptable");
        else result.setComfortLevel("poor");

        return result;
    }

    /**
     * 从受力反推加速度
     */
    private double calculateAcceleration(SimulationLog log) {
        double netForce = log.getTractionForce() - log.getBrakeForce();
        if (netForce == 0) return 0;
        // 等效质量 = 空车质量 + 载重，空车质量假设 200000 kg
        double mass = 200000 + log.getLoadWeight();
        return netForce / mass;
    }

    /**
     * 单列车舒适性评分
     */
    private double calculateTrainScore(double maxAcc, double maxDec, double maxJerk) {
        double accScore = scoreMetric(maxAcc, ACC_EXCELLENT, ACC_GOOD, ACC_ACCEPTABLE);
        double decScore = scoreMetric(maxDec, DEC_EXCELLENT, DEC_GOOD, DEC_ACCEPTABLE);
        double jerkScore = scoreMetric(maxJerk, JERK_EXCELLENT, JERK_GOOD, JERK_ACCEPTABLE);
        return accScore * 0.35 + decScore * 0.35 + jerkScore * 0.3;
    }

    private double scoreMetric(double value, double excellent, double good, double acceptable) {
        if (value <= excellent) return 100;
        if (value <= good) return 85 - (value - excellent) / (good - excellent) * 15;
        if (value <= acceptable) return 70 - (value - good) / (acceptable - good) * 20;
        return Math.max(0, 50 - (value - acceptable) / acceptable * 50);
    }
}
