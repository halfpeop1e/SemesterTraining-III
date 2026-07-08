package com.bjtu.railtransit.evaluation;

import com.bjtu.railtransit.domain.model.PunctualityResult;
import com.bjtu.railtransit.domain.model.SimulationLog;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 准点率评估器
 * 比较计划到站时间与实际到站时间
 */
@Service
public class PunctualityEvaluator {

    /**
     * 评估准点率
     * @param logs 仿真日志
     * @param stationPositions 站点ID → 目标位置(m)
     * @param stationNames 站点ID → 站名
     * @param plannedArrivals 站点ID → 计划到站时间(s)
     * @param tolerance 准点容忍度 (s)
     */
    public PunctualityResult evaluate(
            List<SimulationLog> logs,
            Map<Integer, Double> stationPositions,
            Map<Integer, String> stationNames,
            Map<Integer, Double> plannedArrivals,
            double tolerance) {

        if (tolerance <= 0) tolerance = 30.0;

        // 按列车分组
        Map<Integer, List<SimulationLog>> grouped = logs.stream()
                .collect(Collectors.groupingBy(SimulationLog::getTrainId));

        List<PunctualityResult.StationDelay> allDelays = new ArrayList<>();

        for (Map.Entry<Integer, List<SimulationLog>> entry : grouped.entrySet()) {
            List<SimulationLog> trainLogs = entry.getValue();
            trainLogs.sort(Comparator.comparingLong(SimulationLog::getTimestamp));

            // 找到各站实际到达时间（首次通过目标位置 且 速度≈0）
            for (Map.Entry<Integer, Double> stationEntry : stationPositions.entrySet()) {
                int stationId = stationEntry.getKey();
                double targetPos = stationEntry.getValue();
                Double planned = plannedArrivals.get(stationId);
                if (planned == null) continue;

                SimulationLog arrivalLog = null;
                for (SimulationLog log : trainLogs) {
                    if (log.getSpeed() < 0.1 && Math.abs(log.getPosition() - targetPos) < 2.0) {
                        arrivalLog = log;
                        break;
                    }
                }

                if (arrivalLog != null) {
                    PunctualityResult.StationDelay sd = new PunctualityResult.StationDelay();
                    sd.setStationId(stationId);
                    sd.setStationName(stationNames.getOrDefault(stationId, "Station-" + stationId));
                    sd.setPlannedArrival(planned);
                    double actual = arrivalLog.getTimestamp() / 1000.0; // ms → s
                    sd.setActualArrival(actual);
                    sd.setDelay(actual - planned);
                    allDelays.add(sd);
                }
            }
        }

        PunctualityResult result = new PunctualityResult();
        result.setDelayPerStation(allDelays);

        if (allDelays.isEmpty()) {
            result.setAvgDelay(0);
            result.setMaxDelay(0);
            result.setPunctualityRate(1.0);
            return result;
        }

        double sumDelay = 0;
        double maxDelay = 0;
        int punctualCount = 0;
        for (PunctualityResult.StationDelay sd : allDelays) {
            double absDelay = Math.abs(sd.getDelay());
            sumDelay += sd.getDelay();
            maxDelay = Math.max(maxDelay, sd.getDelay());
            if (absDelay <= tolerance) {
                punctualCount++;
            }
        }

        result.setAvgDelay(sumDelay / allDelays.size());
        result.setMaxDelay(maxDelay);
        result.setPunctualityRate((double) punctualCount / allDelays.size());

        return result;
    }
}
