package com.bjtu.railtransit.evaluation;

import com.bjtu.railtransit.domain.dto.EnergyCalculateRequest;
import com.bjtu.railtransit.domain.dto.EvaluationRequest;
import com.bjtu.railtransit.domain.model.*;
import com.bjtu.railtransit.energy.EnergyCalculator;
import com.bjtu.railtransit.energy.PeakPowerDetector;
import com.bjtu.railtransit.energy.PowerRiskAssessor;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 评估汇总器
 * 统筹调用各子模块，生成综合评估报告
 */
@Service
public class EvaluationAggregator {

    private final EnergyCalculator energyCalculator;
    private final PeakPowerDetector peakPowerDetector;
    private final PowerRiskAssessor powerRiskAssessor;
    private final StopErrorEvaluator stopErrorEvaluator;
    private final PunctualityEvaluator punctualityEvaluator;
    private final ComfortEvaluator comfortEvaluator;
    private final SafetyEventCollector safetyEventCollector;

    public EvaluationAggregator(EnergyCalculator energyCalculator,
                                PeakPowerDetector peakPowerDetector,
                                PowerRiskAssessor powerRiskAssessor,
                                StopErrorEvaluator stopErrorEvaluator,
                                PunctualityEvaluator punctualityEvaluator,
                                ComfortEvaluator comfortEvaluator,
                                SafetyEventCollector safetyEventCollector) {
        this.energyCalculator = energyCalculator;
        this.peakPowerDetector = peakPowerDetector;
        this.powerRiskAssessor = powerRiskAssessor;
        this.stopErrorEvaluator = stopErrorEvaluator;
        this.punctualityEvaluator = punctualityEvaluator;
        this.comfortEvaluator = comfortEvaluator;
        this.safetyEventCollector = safetyEventCollector;
    }

    /**
     * 生成综合评估报告
     */
    public EvaluationReport generateReport(EnergyCalculateRequest energyReq, EvaluationRequest evalReq) {
        EvaluationReport report = new EvaluationReport();
        report.setScenarioName(evalReq.getScenarioName());

        List<SimulationLog> logs = evalReq.getSimulationLogs();
        if (logs == null || logs.isEmpty()) return report;

        // 仿真时长
        long maxTs = logs.stream().mapToLong(SimulationLog::getTimestamp).max().orElse(0);
        report.setSimulationDuration(maxTs);

        // 1. 能耗计算
        double tractionEff = energyReq.getTractionEfficiency() > 0 ? energyReq.getTractionEfficiency() : 0.85;
        double regenEff = energyReq.getRegenEfficiency() > 0 ? energyReq.getRegenEfficiency() : 0.65;
        List<EnergyRecord> energyRecords = energyCalculator.calculate(logs, tractionEff, regenEff);
        report.setEnergyRecords(energyRecords);

        // 2. 峰值检测 + 风险评估
        PeakPowerResult peak = peakPowerDetector.detect(logs, tractionEff);
        double threshold = energyReq.getPowerSupplyThreshold();
        String riskLevel = powerRiskAssessor.assess(peak, threshold);
        report.setPeakPowerResult(peak);
        report.setPowerRiskLevel(riskLevel);
        report.setPowerSupplyThreshold(threshold);

        // 3. 停站误差
        double stopTol = evalReq.getStopWindowTolerance() > 0 ? evalReq.getStopWindowTolerance() : 0.5;
        List<StopErrorResult> stopErrors = stopErrorEvaluator.evaluate(logs,
                evalReq.getStationPositions(), evalReq.getStationNames(),
                evalReq.getStationDirections(), stopTol);
        report.setStopErrors(stopErrors);

        // 4. 准点率
        double puncTol = evalReq.getPunctualityTolerance() > 0 ? evalReq.getPunctualityTolerance() : 30;
        PunctualityResult punctuality = punctualityEvaluator.evaluate(logs,
                evalReq.getStationPositions(), evalReq.getStationNames(),
                evalReq.getPlannedArrivals(), puncTol);
        report.setPunctuality(punctuality);

        // 5. 舒适性
        ComfortResult comfort = comfortEvaluator.evaluate(logs);
        report.setComfort(comfort);

        // 6. 安全事件
        List<SafetyEvent> safetyEvents = safetyEventCollector.collect(logs, evalReq.getSpeedLimits());
        report.setSafetyEvents(safetyEvents);

        // 7. 汇总指标
        Map<String, Double> summary = new LinkedHashMap<>();
        summary.put("总牵引能耗(kWh)", energyRecords.stream().mapToDouble(EnergyRecord::getTotalTractionEnergyKwh).sum());
        summary.put("总再生能量(kWh)", energyRecords.stream().mapToDouble(EnergyRecord::getTotalRegenEnergyKwh).sum());
        summary.put("总净能耗(kWh)", energyRecords.stream().mapToDouble(EnergyRecord::getNetEnergyKwh).sum());
        summary.put("峰值功率(kW)", peak.getMaxPeakKw());
        summary.put("平均停站误差(m)", stopErrors.stream().mapToDouble(e -> Math.abs(e.getError())).average().orElse(0));
        summary.put("准点率", punctuality.getPunctualityRate());
        summary.put("舒适性评分", comfort.getComfortScore());
        summary.put("安全事件数", (double) safetyEvents.size());
        report.setSummary(summary);

        return report;
    }
}
