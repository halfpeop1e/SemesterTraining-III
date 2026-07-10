package com.bjtu.railtransit.energy;

import com.bjtu.railtransit.domain.model.EnergyRecord;
import com.bjtu.railtransit.domain.model.SimulationLog;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 能耗计算器
 * 公式：
 *   牵引功率 = 牵引力(N) × 速度(m/s) / 牵引效率 → W
 *   牵引能耗 = Σ(牵引功率 × Δt) → J → kWh
 *   回收能量 = Σ(制动能量 × 再生制动效率) → J → kWh
 */
@Service
public class EnergyCalculator {

    private static final double DEFAULT_TRACTION_EFFICIENCY = 0.85;
    private static final double DEFAULT_REGEN_EFFICIENCY = 0.65;

    /**
     * 按列车分组计算能耗
     */
    public List<EnergyRecord> calculate(List<SimulationLog> logs, double tractionEfficiency, double regenEfficiency) {
        if (tractionEfficiency <= 0) tractionEfficiency = DEFAULT_TRACTION_EFFICIENCY;
        if (regenEfficiency <= 0) regenEfficiency = DEFAULT_REGEN_EFFICIENCY;

        // 按 trainId 分组
        Map<Integer, List<SimulationLog>> grouped = logs.stream()
                .collect(Collectors.groupingBy(SimulationLog::getTrainId));

        List<EnergyRecord> records = new ArrayList<>();
        for (Map.Entry<Integer, List<SimulationLog>> entry : grouped.entrySet()) {
            int trainId = entry.getKey();
            List<SimulationLog> trainLogs = entry.getValue();
            // 按时间排序
            trainLogs.sort(Comparator.comparingLong(SimulationLog::getTimestamp));

            double totalTractionEnergyJ = 0;
            double totalRegenEnergyJ = 0;
            double maxPowerKw = 0;
            double sumPowerKw = 0;
            int tractionSteps = 0;

            for (int i = 0; i < trainLogs.size(); i++) {
                SimulationLog log = trainLogs.get(i);

                // 计算时间步长：用相邻两条日志的时间差，默认 1.0s
                double deltaTimeS = 1.0;
                if (i + 1 < trainLogs.size()) {
                    long currentTs = log.getTimestamp();
                    long nextTs = trainLogs.get(i + 1).getTimestamp();
                    long diffMs = nextTs - currentTs;
                    if (diffMs > 0 && diffMs <= 5000) {
                        deltaTimeS = diffMs / 1000.0;
                    }
                }
                // 牵引工况：计算牵引功率和能耗
                if ("traction".equals(log.getTractiveBrakeCmd()) && log.getTractionForce() > 0 && log.getSpeed() > 0) {
                    // 功率 (W) = 力 (N) × 速度 (m/s)
                    double powerW = log.getTractionForce() * log.getSpeed();
                    // 考虑牵引效率
                    double powerKw = powerW / (tractionEfficiency * 1000.0);
                    totalTractionEnergyJ += powerW * deltaTimeS / tractionEfficiency;

                    maxPowerKw = Math.max(maxPowerKw, powerKw);
                    sumPowerKw += powerKw;
                    tractionSteps++;
                }

                // 制动工况：计算再生制动
                if ("brake".equals(log.getTractiveBrakeCmd()) && log.getBrakeForce() > 0 && log.getSpeed() > 0) {
                    double brakePowerW = log.getBrakeForce() * log.getSpeed();
                    totalRegenEnergyJ += brakePowerW * regenEfficiency * deltaTimeS;
                }
            }

            EnergyRecord record = new EnergyRecord();
            record.setTrainId(trainId);
            record.setTotalTractionEnergyKwh(totalTractionEnergyJ / 3_600_000.0);
            record.setTotalRegenEnergyKwh(totalRegenEnergyJ / 3_600_000.0);
            record.setNetEnergyKwh(record.getTotalTractionEnergyKwh() - record.getTotalRegenEnergyKwh());
            record.setMaxTractionPowerKw(maxPowerKw);
            record.setAvgTractionPowerKw(tractionSteps > 0 ? sumPowerKw / tractionSteps : 0);

            records.add(record);
        }

        records.sort(Comparator.comparingInt(EnergyRecord::getTrainId));
        return records;
    }
}
