package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.domain.dto.EnergySnapshot;
import com.bjtu.railtransit.domain.model.EnergyRecord;
import com.bjtu.railtransit.domain.model.PeakPowerResult;
import com.bjtu.railtransit.domain.model.SimulationLog;
import com.bjtu.railtransit.energy.EnergyCalculator;
import com.bjtu.railtransit.energy.PeakPowerDetector;
import com.bjtu.railtransit.energy.PowerRiskAssessor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 能源集成服务 —— 桥接调度仿真模块与能源计算模块
 * <p>
 * 从 SimulationService 获取 SimulationLog 数据，
 * 调用 EnergyCalculator / PeakPowerDetector / PowerRiskAssessor 进行实时计算，
 * 输出 EnergySnapshot 供调度页面展示。
 */
@Service
public class EnergyIntegrationService {

    private final EnergyCalculator energyCalculator;
    private final PeakPowerDetector peakPowerDetector;
    private final PowerRiskAssessor powerRiskAssessor;

    private static final double DEFAULT_TRACTION_EFFICIENCY = 0.882;
    private static final double DEFAULT_REGEN_EFFICIENCY = 0.802;
    private static final double DEFAULT_POWER_THRESHOLD_KW = 2000.0;

    public EnergyIntegrationService(EnergyCalculator energyCalculator,
                                     PeakPowerDetector peakPowerDetector,
                                     PowerRiskAssessor powerRiskAssessor) {
        this.energyCalculator = energyCalculator;
        this.peakPowerDetector = peakPowerDetector;
        this.powerRiskAssessor = powerRiskAssessor;
    }

    /**
     * 计算实时能耗快照
     *
     * @param logs              仿真日志
     * @param tractionEfficiency 牵引效率 (≤0 使用默认)
     * @param regenEfficiency    再生制动效率 (≤0 使用默认)
     * @param powerThresholdKw   供电阈值 (≤0 使用默认 = 2000 kW)
     * @return EnergySnapshot
     */
    public EnergySnapshot compute(List<SimulationLog> logs,
                                   double tractionEfficiency,
                                   double regenEfficiency,
                                   double powerThresholdKw) {
        if (logs == null || logs.isEmpty()) {
            return emptySnapshot();
        }

        double eff = tractionEfficiency > 0 ? tractionEfficiency : DEFAULT_TRACTION_EFFICIENCY;
        double regen = regenEfficiency > 0 ? regenEfficiency : DEFAULT_REGEN_EFFICIENCY;
        double threshold = powerThresholdKw > 0 ? powerThresholdKw : DEFAULT_POWER_THRESHOLD_KW;

        // 1. 各列车能耗
        List<EnergyRecord> records = energyCalculator.calculate(logs, eff, regen);

        // 2. 峰值功率
        PeakPowerResult peak = peakPowerDetector.detect(logs, eff);

        // 3. 风险评估
        String riskLevel = powerRiskAssessor.assess(peak, threshold);

        // 4. 汇总
        double totalTraction = records.stream().mapToDouble(EnergyRecord::getTotalTractionEnergyKwh).sum();
        double totalRegen = records.stream().mapToDouble(EnergyRecord::getTotalRegenEnergyKwh).sum();
        double thresholdRatio = threshold > 0 ? peak.getMaxPeakKw() / threshold : 0;

        EnergySnapshot snapshot = new EnergySnapshot();
        snapshot.setEnergyRecords(records);
        snapshot.setTotalTractionKwh(totalTraction);
        snapshot.setTotalRegenKwh(totalRegen);
        snapshot.setNetEnergyKwh(totalTraction - totalRegen);
        snapshot.setMaxPeakKw(peak.getMaxPeakKw());
        snapshot.setTimeOfPeak(peak.getTimeOfPeak());
        snapshot.setVehiclesAtPeak(peak.getVehiclesAtPeak());
        snapshot.setRiskLevel(riskLevel);
        snapshot.setPowerSupplyThreshold(threshold);
        snapshot.setThresholdRatio(thresholdRatio);

        return snapshot;
    }

    private EnergySnapshot emptySnapshot() {
        EnergySnapshot s = new EnergySnapshot();
        s.setEnergyRecords(Collections.emptyList());
        s.setRiskLevel("safe");
        s.setPowerSupplyThreshold(DEFAULT_POWER_THRESHOLD_KW);
        return s;
    }
}
