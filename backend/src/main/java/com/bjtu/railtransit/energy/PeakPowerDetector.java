package com.bjtu.railtransit.energy;

import com.bjtu.railtransit.domain.model.PeakPowerResult;
import com.bjtu.railtransit.domain.model.SimulationLog;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 多车供电峰值检测器
 * 同一时刻多车同时牵引，功率叠加，检测峰值功率
 */
@Service
public class PeakPowerDetector {

    /**
     * 检测多车运行时的功率峰值
     */
    public PeakPowerResult detect(List<SimulationLog> logs, double tractionEfficiency) {
        if (tractionEfficiency <= 0) tractionEfficiency = 0.85;

        // 按时间戳分组，计算每个时刻所有车的总功率
        Map<Long, Map<Integer, Double>> timeToVehiclePower = new LinkedHashMap<>();

        for (SimulationLog log : logs) {
            if ("traction".equals(log.getTractiveBrakeCmd()) && log.getTractionForce() > 0 && log.getSpeed() > 0) {
                double powerKw = (log.getTractionForce() * log.getSpeed()) / (tractionEfficiency * 1000.0);
                timeToVehiclePower
                        .computeIfAbsent(log.getTimestamp(), k -> new HashMap<>())
                        .merge(log.getTrainId(), powerKw, Double::sum);
            }
        }

        if (timeToVehiclePower.isEmpty()) {
            PeakPowerResult result = new PeakPowerResult();
            result.setMaxPeakKw(0);
            result.setTimeOfPeak(0);
            result.setVehiclesAtPeak(Collections.emptyList());
            result.setRiskLevel("safe");
            return result;
        }

        // 找到峰值时刻
        long peakTime = 0;
        double maxPeak = 0;
        Map<Integer, Double> vehiclesAtPeak = null;

        for (Map.Entry<Long, Map<Integer, Double>> entry : timeToVehiclePower.entrySet()) {
            double totalPower = entry.getValue().values().stream().mapToDouble(Double::doubleValue).sum();
            if (totalPower > maxPeak) {
                maxPeak = totalPower;
                peakTime = entry.getKey();
                vehiclesAtPeak = entry.getValue();
            }
        }

        PeakPowerResult result = new PeakPowerResult();
        result.setMaxPeakKw(maxPeak);
        result.setTimeOfPeak(peakTime);
        result.setVehiclesAtPeak(vehiclesAtPeak != null ? new ArrayList<>(vehiclesAtPeak.keySet()) : Collections.emptyList());
        return result;
    }
}
