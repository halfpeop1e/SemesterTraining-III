package com.bjtu.railtransit.evaluation;

import com.bjtu.railtransit.domain.model.SimulationLog;
import com.bjtu.railtransit.domain.model.StopErrorResult;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 停站误差评估器
 * 根据站点目标位置和列车实际停车位置计算误差
 */
@Service
public class StopErrorEvaluator {

    /**
     * 评估停站误差
     * @param logs 仿真日志
     * @param stationPositions 站点ID → 目标位置(m)
     * @param stationNames 站点ID → 站名
     * @param stationDirections 站点ID → 方向
     * @param tolerance 停车窗容忍度 (m)
     */
    public List<StopErrorResult> evaluate(
            List<SimulationLog> logs,
            Map<Integer, Double> stationPositions,
            Map<Integer, String> stationNames,
            Map<Integer, String> stationDirections,
            double tolerance) {

        if (tolerance <= 0) tolerance = 0.5;
        List<StopErrorResult> results = new ArrayList<>();

        // 按 trainId → timestamp 排序
        Map<Integer, List<SimulationLog>> grouped = logs.stream()
                .collect(Collectors.groupingBy(SimulationLog::getTrainId));

        for (Map.Entry<Integer, List<SimulationLog>> entry : grouped.entrySet()) {
            List<SimulationLog> trainLogs = entry.getValue();
            trainLogs.sort(Comparator.comparingLong(SimulationLog::getTimestamp));

            for (Map.Entry<Integer, Double> stationEntry : stationPositions.entrySet()) {
                int stationId = stationEntry.getKey();
                double targetPos = stationEntry.getValue();

                // 找到列车在目标位置附近的最近点（首次速度降为0且位置接近目标）
                SimulationLog stopPoint = null;
                double minDist = Double.MAX_VALUE;

                for (SimulationLog log : trainLogs) {
                    if (log.getSpeed() < 0.01) { // 近似停车状态
                        double dist = Math.abs(log.getPosition() - targetPos);
                        if (dist < minDist) {
                            minDist = dist;
                            stopPoint = log;
                        }
                    }
                }

                if (stopPoint != null) {
                    StopErrorResult result = new StopErrorResult();
                    result.setStationId(stationId);
                    result.setStationName(stationNames.getOrDefault(stationId, "Station-" + stationId));
                    result.setDirection(stationDirections.getOrDefault(stationId, "unknown"));
                    result.setTargetPosition(targetPos);
                    result.setActualPosition(stopPoint.getPosition());
                    double error = stopPoint.getPosition() - targetPos;
                    result.setError(error);
                    if (Math.abs(error) <= tolerance) {
                        result.setStatus("in_window");
                    } else if (error > 0) {
                        result.setStatus("over");
                    } else {
                        result.setStatus("under");
                    }
                    results.add(result);
                }
            }
        }

        results.sort(Comparator.comparingInt(StopErrorResult::getStationId));
        return results;
    }
}
