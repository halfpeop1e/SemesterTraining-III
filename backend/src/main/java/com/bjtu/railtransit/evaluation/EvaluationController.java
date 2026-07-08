package com.bjtu.railtransit.evaluation;

import com.bjtu.railtransit.common.ApiResponse;
import com.bjtu.railtransit.domain.dto.EnergyCalculateRequest;
import com.bjtu.railtransit.domain.dto.EvaluationRequest;
import com.bjtu.railtransit.domain.model.*;
import com.bjtu.railtransit.energy.EnergyCalculator;
import com.bjtu.railtransit.energy.PeakPowerDetector;
import com.bjtu.railtransit.energy.PowerRiskAssessor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 能源评估 REST API
 */
@RestController
@RequestMapping("/api/evaluation")
public class EvaluationController {

    private final EnergyCalculator energyCalculator;
    private final PeakPowerDetector peakPowerDetector;
    private final PowerRiskAssessor powerRiskAssessor;
    private final StopErrorEvaluator stopErrorEvaluator;
    private final PunctualityEvaluator punctualityEvaluator;
    private final ComfortEvaluator comfortEvaluator;
    private final SafetyEventCollector safetyEventCollector;
    private final EvaluationAggregator aggregator;

    public EvaluationController(EnergyCalculator energyCalculator,
                                PeakPowerDetector peakPowerDetector,
                                PowerRiskAssessor powerRiskAssessor,
                                StopErrorEvaluator stopErrorEvaluator,
                                PunctualityEvaluator punctualityEvaluator,
                                ComfortEvaluator comfortEvaluator,
                                SafetyEventCollector safetyEventCollector,
                                EvaluationAggregator aggregator) {
        this.energyCalculator = energyCalculator;
        this.peakPowerDetector = peakPowerDetector;
        this.powerRiskAssessor = powerRiskAssessor;
        this.stopErrorEvaluator = stopErrorEvaluator;
        this.punctualityEvaluator = punctualityEvaluator;
        this.comfortEvaluator = comfortEvaluator;
        this.safetyEventCollector = safetyEventCollector;
        this.aggregator = aggregator;
    }

    /**
     * 综合评估报告
     */
    @PostMapping("/report")
    public ApiResponse<EvaluationReport> generateReport(
            @RequestBody ReportRequest request) {
        EvaluationReport report = aggregator.generateReport(
                request.getEnergyRequest(), request.getEvalRequest());
        return ApiResponse.success(report);
    }

    /**
     * 单独计算能耗
     */
    @PostMapping("/energy/calculate")
    public ApiResponse<List<EnergyRecord>> calculateEnergy(
            @RequestBody EnergyCalculateRequest request) {
        double eff = request.getTractionEfficiency() > 0 ? request.getTractionEfficiency() : 0.85;
        double regen = request.getRegenEfficiency() > 0 ? request.getRegenEfficiency() : 0.65;
        List<EnergyRecord> records = energyCalculator.calculate(
                request.getSimulationLogs(), eff, regen);
        return ApiResponse.success(records);
    }

    /**
     * 峰值功率检测
     */
    @PostMapping("/energy/peak")
    public ApiResponse<PeakPowerResult> detectPeak(
            @RequestBody EnergyCalculateRequest request) {
        double eff = request.getTractionEfficiency() > 0 ? request.getTractionEfficiency() : 0.85;
        PeakPowerResult peak = peakPowerDetector.detect(request.getSimulationLogs(), eff);
        powerRiskAssessor.assess(peak, request.getPowerSupplyThreshold());
        return ApiResponse.success(peak);
    }

    /**
     * 停站误差评估
     */
    @PostMapping("/stop-error")
    public ApiResponse<List<StopErrorResult>> evaluateStopError(
            @RequestBody EvaluationRequest request) {
        double tol = request.getStopWindowTolerance() > 0 ? request.getStopWindowTolerance() : 0.5;
        List<StopErrorResult> results = stopErrorEvaluator.evaluate(
                request.getSimulationLogs(), request.getStationPositions(),
                request.getStationNames(), request.getStationDirections(), tol);
        return ApiResponse.success(results);
    }

    /**
     * 准点率评估
     */
    @PostMapping("/punctuality")
    public ApiResponse<PunctualityResult> evaluatePunctuality(
            @RequestBody EvaluationRequest request) {
        double tol = request.getPunctualityTolerance() > 0 ? request.getPunctualityTolerance() : 30;
        PunctualityResult result = punctualityEvaluator.evaluate(
                request.getSimulationLogs(), request.getStationPositions(),
                request.getStationNames(), request.getPlannedArrivals(), tol);
        return ApiResponse.success(result);
    }

    /**
     * 舒适性评估
     */
    @PostMapping("/comfort")
    public ApiResponse<ComfortResult> evaluateComfort(
            @RequestBody EvaluationRequest request) {
        ComfortResult result = comfortEvaluator.evaluate(request.getSimulationLogs());
        return ApiResponse.success(result);
    }

    /**
     * 安全事件汇总
     */
    @PostMapping("/safety")
    public ApiResponse<List<SafetyEvent>> collectSafetyEvents(
            @RequestBody EvaluationRequest request) {
        List<SafetyEvent> events = safetyEventCollector.collect(
                request.getSimulationLogs(), request.getSpeedLimits());
        return ApiResponse.success(events);
    }

    /**
     * 综合报告请求包装
     */
    public static class ReportRequest {
        private EnergyCalculateRequest energyRequest;
        private EvaluationRequest evalRequest;

        public EnergyCalculateRequest getEnergyRequest() { return energyRequest; }
        public void setEnergyRequest(EnergyCalculateRequest energyRequest) { this.energyRequest = energyRequest; }
        public EvaluationRequest getEvalRequest() { return evalRequest; }
        public void setEvalRequest(EvaluationRequest evalRequest) { this.evalRequest = evalRequest; }
    }
}
