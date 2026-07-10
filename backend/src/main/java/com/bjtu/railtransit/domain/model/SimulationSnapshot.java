package com.bjtu.railtransit.domain.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SimulationSnapshot {

    private String simulationId = "BJ-L9-LAB";
    private String lineId = "BJ-L9";
    private double simulationTime;
    private String simTimeFormatted;
    private boolean running;
    private int totalTrains;
    private int activeTrains;
    private List<TrainState> trains;
    private List<HeadwayInfo> headways;
    private List<TrainCommand> commands;
    private List<TrainPositionPoint> positionHistory;
    private List<StationArrival> stationArrivals;
    private double totalEnergyKwh;
    private double totalTractionKwh;
    private double totalRegenKwh;
    private double totalAuxKwh;
    private double totalCruisingKwh;
    /** 惰行节省能耗 kWh */
    private double coastingSavedKwh;
    /** 能耗历史趋势数据 (每5秒一条) */
    private List<EnergyDataPoint> energyHistory = new ArrayList<>();
    /** 供电状态: 每列车电压/电流/功率 */
    private List<PowerSupplyData> powerSupplyStatus = new ArrayList<>();
    private double peakPowerKw;
    private int maxSpeedLimit;
    private List<DelayEvent> delayEvents;
    private PassengerFlowModel.PassengerFlowInfo passengerFlow;
    private DispatchInfo dispatchInfo;
    /** 计划运行图点位 (所有列车全时刻表 → 前端画计划线) */
    private List<TrainPositionPoint> plannedDiagramPoints;
    /** 各列车在各站的计划时刻与偏差 */
    private List<StationArrival> planDeviations;
    /** 能源优化信息 */
    private EnergyOptimizationInfo energyOptimization;
    /** 供电分区状态列表 */
    private List<PowerSectionStatus> powerSections;
    /** 移动授权列表 */
    private List<MovementAuthorityInfo> movementAuthorities;
    private Object dispatchPlan;
    private List<com.bjtu.railtransit.domain.model.TrainCommand> integrationCommands = new ArrayList<>();
    private List<com.bjtu.railtransit.domain.model.TrainCommand> pendingManualConfirmations = new ArrayList<>();
    private List<StatusReport> onboardReports = new ArrayList<>();
    private List<OnboardEvent> onboardEvents = new ArrayList<>();
    private Object energySummary;
    private Object evaluationSummary;
    private Object protocolAdapterStatus;
    /** MPC预测优化结果列表 */
    private List<Map<String, Object>> mpcOptimizations;

    public SimulationSnapshot() {
    }
    public String getSimulationId() { return simulationId; }
    public void setSimulationId(String v) { simulationId=v; }
    public String getLineId() { return lineId; }
    public void setLineId(String v) { lineId=v; }
    public double getCurrentTimeSeconds() { return simulationTime; }

    public double getSimulationTime() {
        return simulationTime;
    }

    public void setSimulationTime(double simulationTime) {
        this.simulationTime = simulationTime;
    }

    public String getSimTimeFormatted() {
        return simTimeFormatted;
    }

    public void setSimTimeFormatted(String simTimeFormatted) {
        this.simTimeFormatted = simTimeFormatted;
    }

    public boolean isRunning() { return running; }
    public void setRunning(boolean v) { this.running = v; }

    public int getTotalTrains() {
        return totalTrains;
    }

    public void setTotalTrains(int totalTrains) {
        this.totalTrains = totalTrains;
    }

    public int getActiveTrains() {
        return activeTrains;
    }

    public void setActiveTrains(int activeTrains) {
        this.activeTrains = activeTrains;
    }

    public List<TrainState> getTrains() {
        return trains;
    }

    public void setTrains(List<TrainState> trains) {
        this.trains = trains;
    }

    public List<HeadwayInfo> getHeadways() {
        return headways;
    }

    public void setHeadways(List<HeadwayInfo> headways) {
        this.headways = headways;
    }

    public List<TrainCommand> getCommands() {
        return commands;
    }

    public void setCommands(List<TrainCommand> commands) {
        this.commands = commands;
    }

    public List<TrainPositionPoint> getPositionHistory() {
        return positionHistory;
    }

    public void setPositionHistory(List<TrainPositionPoint> positionHistory) {
        this.positionHistory = positionHistory;
    }

    public List<StationArrival> getStationArrivals() {
        return stationArrivals;
    }

    public void setStationArrivals(List<StationArrival> stationArrivals) {
        this.stationArrivals = stationArrivals;
    }

    public double getTotalEnergyKwh() {
        return totalEnergyKwh;
    }

    public void setTotalEnergyKwh(double totalEnergyKwh) {
        this.totalEnergyKwh = totalEnergyKwh;
    }

    public double getTotalTractionKwh() { return totalTractionKwh; }
    public void setTotalTractionKwh(double v) { this.totalTractionKwh = v; }

    public double getTotalRegenKwh() { return totalRegenKwh; }
    public void setTotalRegenKwh(double v) { this.totalRegenKwh = v; }
    public double getTotalAuxKwh() { return totalAuxKwh; }
    public void setTotalAuxKwh(double v) { this.totalAuxKwh = v; }
    public double getTotalCruisingKwh() { return totalCruisingKwh; }
    public void setTotalCruisingKwh(double v) { this.totalCruisingKwh = v; }
    public double getCoastingSavedKwh() { return coastingSavedKwh; }
    public void setCoastingSavedKwh(double v) { this.coastingSavedKwh = v; }
    public List<EnergyDataPoint> getEnergyHistory() { return energyHistory; }
    public void setEnergyHistory(List<EnergyDataPoint> v) { this.energyHistory = v; }
    public List<PowerSupplyData> getPowerSupplyStatus() { return powerSupplyStatus; }
    public void setPowerSupplyStatus(List<PowerSupplyData> v) { this.powerSupplyStatus = v; }

    public double getPeakPowerKw() { return peakPowerKw; }
    public void setPeakPowerKw(double v) { this.peakPowerKw = v; }

    public int getMaxSpeedLimit() { return maxSpeedLimit; }
    public void setMaxSpeedLimit(int v) { this.maxSpeedLimit = v; }

    public List<DelayEvent> getDelayEvents() { return delayEvents; }
    public void setDelayEvents(List<DelayEvent> v) { this.delayEvents = v; }
    public PassengerFlowModel.PassengerFlowInfo getPassengerFlow() { return passengerFlow; }
    public void setPassengerFlow(PassengerFlowModel.PassengerFlowInfo v) { this.passengerFlow = v; }
    public DispatchInfo getDispatchInfo() { return dispatchInfo; }
    public void setDispatchInfo(DispatchInfo v) { this.dispatchInfo = v; }
    public List<TrainPositionPoint> getPlannedDiagramPoints() { return plannedDiagramPoints; }
    public void setPlannedDiagramPoints(List<TrainPositionPoint> v) { this.plannedDiagramPoints = v; }
    public List<StationArrival> getPlanDeviations() { return planDeviations; }
    public void setPlanDeviations(List<StationArrival> v) { this.planDeviations = v; }

    public EnergyOptimizationInfo getEnergyOptimization() { return energyOptimization; }
    public void setEnergyOptimization(EnergyOptimizationInfo v) { this.energyOptimization = v; }

    public List<PowerSectionStatus> getPowerSections() { return powerSections; }
    public void setPowerSections(List<PowerSectionStatus> v) { this.powerSections = v; }

    public List<MovementAuthorityInfo> getMovementAuthorities() { return movementAuthorities; }
    public void setMovementAuthorities(List<MovementAuthorityInfo> v) { this.movementAuthorities = v; }
    public Object getDispatchPlan() { return dispatchPlan; } public void setDispatchPlan(Object v) { dispatchPlan=v; }
    public List<com.bjtu.railtransit.domain.model.TrainCommand> getIntegrationCommands() { return integrationCommands; }
    public void setIntegrationCommands(List<com.bjtu.railtransit.domain.model.TrainCommand> v) { integrationCommands=v; }
    public List<com.bjtu.railtransit.domain.model.TrainCommand> getPendingManualConfirmations() { return pendingManualConfirmations; }
    public void setPendingManualConfirmations(List<com.bjtu.railtransit.domain.model.TrainCommand> v) { pendingManualConfirmations=v; }
    public List<StatusReport> getOnboardReports() { return onboardReports; } public void setOnboardReports(List<StatusReport> v) { onboardReports=v; }
    public List<OnboardEvent> getOnboardEvents() { return onboardEvents; } public void setOnboardEvents(List<OnboardEvent> v) { onboardEvents=v; }
    public Object getEnergySummary() { return energySummary; } public void setEnergySummary(Object v) { energySummary=v; }
    public Object getEvaluationSummary() { return evaluationSummary; } public void setEvaluationSummary(Object v) { evaluationSummary=v; }
    public Object getProtocolAdapterStatus() { return protocolAdapterStatus; } public void setProtocolAdapterStatus(Object v) { protocolAdapterStatus=v; }
    public List<Map<String, Object>> getMpcOptimizations() { return mpcOptimizations; }
    public void setMpcOptimizations(List<Map<String, Object>> v) { this.mpcOptimizations = v; }

    // ═══════════════════════════════════════════════════════════════
    // 内嵌类
    // ═══════════════════════════════════════════════════════════════

    public static class MovementAuthorityInfo {
        private String trainId;
        private String direction;
        private double authorityEndMeters;
        private double permittedSpeedKmh;

        public String getTrainId() { return trainId; }
        public void setTrainId(String v) { this.trainId = v; }
        public String getDirection() { return direction; }
        public void setDirection(String v) { this.direction = v; }
        public double getAuthorityEndMeters() { return authorityEndMeters; }
        public void setAuthorityEndMeters(double v) { this.authorityEndMeters = v; }
        public double getPermittedSpeedKmh() { return permittedSpeedKmh; }
        public void setPermittedSpeedKmh(double v) { this.permittedSpeedKmh = v; }
    }

    public static class HeadwayInfo {
        private String fromTrainId;
        private String toTrainId;
        private double distanceMeters;
        private double timeSeconds;
        private String status;
        private double safetyDistanceMeters;

        public HeadwayInfo() {
        }

        public String getFromTrainId() {
            return fromTrainId;
        }

        public void setFromTrainId(String fromTrainId) {
            this.fromTrainId = fromTrainId;
        }

        public String getToTrainId() {
            return toTrainId;
        }

        public void setToTrainId(String toTrainId) {
            this.toTrainId = toTrainId;
        }

        public double getDistanceMeters() {
            return distanceMeters;
        }

        public void setDistanceMeters(double distanceMeters) {
            this.distanceMeters = distanceMeters;
        }

        public double getTimeSeconds() {
            return timeSeconds;
        }

        public void setTimeSeconds(double timeSeconds) {
            this.timeSeconds = timeSeconds;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public double getSafetyDistanceMeters() { return safetyDistanceMeters; }
        public void setSafetyDistanceMeters(double v) { this.safetyDistanceMeters = v; }
    }

    public static class TrainCommand {
        private String trainId;
        private String commandType;
        private double targetValue;
        private String reason;
        private double issuedTime;       // 下达时刻 (sim seconds)
        private double effectiveTime;    // 生效时刻
        private double completedTime;    // 完成时刻
        private String status;           // ISSUED | ACKED | EXECUTING | COMPLETED | REJECTED

        public TrainCommand() { this.status = "ISSUED"; }

        public String getTrainId() { return trainId; }
        public void setTrainId(String v) { this.trainId = v; }
        public String getCommandType() { return commandType; }
        public void setCommandType(String v) { this.commandType = v; }
        public double getTargetValue() { return targetValue; }
        public void setTargetValue(double v) { this.targetValue = v; }
        public String getReason() { return reason; }
        public void setReason(String v) { this.reason = v; }
        public double getIssuedTime() { return issuedTime; }
        public void setIssuedTime(double v) { this.issuedTime = v; }
        public double getEffectiveTime() { return effectiveTime; }
        public void setEffectiveTime(double v) { this.effectiveTime = v; }
        public double getCompletedTime() { return completedTime; }
        public void setCompletedTime(double v) { this.completedTime = v; }
        public String getStatus() { return status; }
        public void setStatus(String v) { this.status = v; }
    }

    public static class TrainPositionPoint {
        private String trainId;
        private double timeSeconds;
        private double positionKm;
        private String direction; // UP | DOWN

        public TrainPositionPoint() {
        }

        public String getTrainId() {
            return trainId;
        }

        public void setTrainId(String trainId) {
            this.trainId = trainId;
        }

        public double getTimeSeconds() {
            return timeSeconds;
        }

        public void setTimeSeconds(double timeSeconds) {
            this.timeSeconds = timeSeconds;
        }

        public double getPositionKm() {
            return positionKm;
        }

        public void setPositionKm(double positionKm) {
            this.positionKm = positionKm;
        }

        public String getDirection() { return direction; }
        public void setDirection(String v) { this.direction = v; }
    }

    public static class StationArrival {
        private String trainId;
        private String stationName;
        private int stationIndex;
        private double arrivalTimeSeconds;
        private double departureTimeSeconds;
        private double dwellSeconds;
        // ── 计划时刻 (用于计划图 vs 实绩图对比) ──
        private double plannedArrivalSeconds;
        private double plannedDepartureSeconds;
        private double plannedDwellSeconds;
        private double arrivalDeviation;   // 到站偏差秒 (actual - planned, >0 = 晚点)
        private double departureDeviation; // 发车偏差秒

        public StationArrival() {}

        public String getTrainId() { return trainId; }
        public void setTrainId(String v) { this.trainId = v; }
        public String getStationName() { return stationName; }
        public void setStationName(String v) { this.stationName = v; }
        public int getStationIndex() { return stationIndex; }
        public void setStationIndex(int v) { this.stationIndex = v; }
        public double getArrivalTimeSeconds() { return arrivalTimeSeconds; }
        public void setArrivalTimeSeconds(double v) { this.arrivalTimeSeconds = v; }
        public double getDepartureTimeSeconds() { return departureTimeSeconds; }
        public void setDepartureTimeSeconds(double v) { this.departureTimeSeconds = v; }
        public double getDwellSeconds() { return dwellSeconds; }
        public void setDwellSeconds(double v) { this.dwellSeconds = v; }
        public double getPlannedArrivalSeconds() { return plannedArrivalSeconds; }
        public void setPlannedArrivalSeconds(double v) { this.plannedArrivalSeconds = v; }
        public double getPlannedDepartureSeconds() { return plannedDepartureSeconds; }
        public void setPlannedDepartureSeconds(double v) { this.plannedDepartureSeconds = v; }
        public double getPlannedDwellSeconds() { return plannedDwellSeconds; }
        public void setPlannedDwellSeconds(double v) { this.plannedDwellSeconds = v; }
        public double getArrivalDeviation() { return arrivalDeviation; }
        public void setArrivalDeviation(double v) { this.arrivalDeviation = v; }
        public double getDepartureDeviation() { return departureDeviation; }
        public void setDepartureDeviation(double v) { this.departureDeviation = v; }
    }

    /**
     * 晚点传播事件
     */
    public static class DelayEvent {
        private double timeSeconds;
        private String trainId;
        private double delaySeconds;
        private String cause;
        private String affectedTrainId;
        private double positionKm;
        private String eventType; // PRIMARY_DELAY, PROPAGATED, RECOVERED

        public double getTimeSeconds() { return timeSeconds; }
        public void setTimeSeconds(double v) { this.timeSeconds = v; }
        public String getTrainId() { return trainId; }
        public void setTrainId(String v) { this.trainId = v; }
        public double getDelaySeconds() { return delaySeconds; }
        public void setDelaySeconds(double v) { this.delaySeconds = v; }
        public String getCause() { return cause; }
        public void setCause(String v) { this.cause = v; }
        public String getAffectedTrainId() { return affectedTrainId; }
        public void setAffectedTrainId(String v) { this.affectedTrainId = v; }
        public double getPositionKm() { return positionKm; }
        public void setPositionKm(double v) { this.positionKm = v; }
        public String getEventType() { return eventType; }
        public void setEventType(String v) { this.eventType = v; }
    }

    /**
     * 调度汇总信息
     */
    public static class DispatchInfo {
        private double recommendedHeadway;   // 推荐发车间隔(秒)
        private double actualHeadway;        // 实际发车间隔(秒)
        private int onlineTrains;            // 当前在线列车数
        private int maxAvailableTrains;      // 最大可用列车数
        private int requiredTrains;          // 客流所需列车数
        private boolean fleetSufficient;     // 车辆是否充足
        private String dispatchMode;         // NORMAL, COMPRESS, STRETCH, EMERGENCY

        public double getRecommendedHeadway() { return recommendedHeadway; }
        public void setRecommendedHeadway(double v) { this.recommendedHeadway = v; }
        public double getActualHeadway() { return actualHeadway; }
        public void setActualHeadway(double v) { this.actualHeadway = v; }
        public int getOnlineTrains() { return onlineTrains; }
        public void setOnlineTrains(int v) { this.onlineTrains = v; }
        public int getMaxAvailableTrains() { return maxAvailableTrains; }
        public void setMaxAvailableTrains(int v) { this.maxAvailableTrains = v; }
        public int getRequiredTrains() { return requiredTrains; }
        public void setRequiredTrains(int v) { this.requiredTrains = v; }
        public boolean isFleetSufficient() { return fleetSufficient; }
        public void setFleetSufficient(boolean v) { this.fleetSufficient = v; }
        public String getDispatchMode() { return dispatchMode; }
        public void setDispatchMode(String v) { this.dispatchMode = v; }
    }

    /**
     * 能源优化摘要信息 (供前端能耗看板实时展示)
     */
    public static class EnergyOptimizationInfo {
        private double currentPeakKw;
        private double powerSupplyThresholdKw;
        private String peakRiskLevel;           // safe | warning | danger
        private int tractionCount;
        private int maxTractionCount;
        private double totalRecoverableEnergyKw;
        private int regenCoordinationCount;
        private int coastingOpportunityCount;
        private List<String> recommendations = new ArrayList<>();
        private double currentLoadFactor;
        // ── 实时累计能耗 ──
        private double totalTractionEnergyKwh;   // 累计牵引能耗 kWh
        private double totalRegenEnergyKwh;      // 累计再生制动回收 kWh
        private double netEnergyKwh;             // 净能耗 kWh
        private double simulationTimeSeconds;    // 当前仿真时间(s)
        private double auxiliaryEnergyKwh;       // 累计辅助能耗(空调/照明) kWh
        private double cruisingEnergyKwh;        // 累计巡航能耗 kWh

        public double getCurrentPeakKw() { return currentPeakKw; }
        public void setCurrentPeakKw(double v) { this.currentPeakKw = v; }
        public double getPowerSupplyThresholdKw() { return powerSupplyThresholdKw; }
        public void setPowerSupplyThresholdKw(double v) { this.powerSupplyThresholdKw = v; }
        public String getPeakRiskLevel() { return peakRiskLevel; }
        public void setPeakRiskLevel(String v) { this.peakRiskLevel = v; }
        public int getTractionCount() { return tractionCount; }
        public void setTractionCount(int v) { this.tractionCount = v; }
        public int getMaxTractionCount() { return maxTractionCount; }
        public void setMaxTractionCount(int v) { this.maxTractionCount = v; }
        public double getTotalRecoverableEnergyKw() { return totalRecoverableEnergyKw; }
        public void setTotalRecoverableEnergyKw(double v) { this.totalRecoverableEnergyKw = v; }
        public int getRegenCoordinationCount() { return regenCoordinationCount; }
        public void setRegenCoordinationCount(int v) { this.regenCoordinationCount = v; }
        public int getCoastingOpportunityCount() { return coastingOpportunityCount; }
        public void setCoastingOpportunityCount(int v) { this.coastingOpportunityCount = v; }
        public List<String> getRecommendations() { return recommendations; }
        public void setRecommendations(List<String> v) { this.recommendations = v; }
        public double getCurrentLoadFactor() { return currentLoadFactor; }
        public void setCurrentLoadFactor(double v) { this.currentLoadFactor = v; }

        public double getTotalTractionEnergyKwh() { return totalTractionEnergyKwh; }
        public void setTotalTractionEnergyKwh(double v) { this.totalTractionEnergyKwh = v; }
        public double getTotalRegenEnergyKwh() { return totalRegenEnergyKwh; }
        public void setTotalRegenEnergyKwh(double v) { this.totalRegenEnergyKwh = v; }
        public double getNetEnergyKwh() { return netEnergyKwh; }
        public void setNetEnergyKwh(double v) { this.netEnergyKwh = v; }
        public double getSimulationTimeSeconds() { return simulationTimeSeconds; }
        public void setSimulationTimeSeconds(double v) { this.simulationTimeSeconds = v; }

        public double getAuxiliaryEnergyKwh() { return auxiliaryEnergyKwh; }
        public void setAuxiliaryEnergyKwh(double v) { this.auxiliaryEnergyKwh = v; }
        public double getCruisingEnergyKwh() { return cruisingEnergyKwh; }
        public void setCruisingEnergyKwh(double v) { this.cruisingEnergyKwh = v; }
    }

    /** 能耗趋势数据点 */
    public static class EnergyDataPoint {
        private double timeSeconds;
        private double totalKwh;
        private double tractionKwh;
        private double regenKwh;
        private double auxKwh;
        public EnergyDataPoint() {}
        public EnergyDataPoint(double t, double total, double trac, double reg, double aux) {
            this.timeSeconds = t; this.totalKwh = total; this.tractionKwh = trac; this.regenKwh = reg; this.auxKwh = aux;
        }
        public double getTimeSeconds() { return timeSeconds; }
        public void setTimeSeconds(double v) { this.timeSeconds = v; }
        public double getTotalKwh() { return totalKwh; }
        public void setTotalKwh(double v) { this.totalKwh = v; }
        public double getTractionKwh() { return tractionKwh; }
        public void setTractionKwh(double v) { this.tractionKwh = v; }
        public double getRegenKwh() { return regenKwh; }
        public void setRegenKwh(double v) { this.regenKwh = v; }
        public double getAuxKwh() { return auxKwh; }
        public void setAuxKwh(double v) { this.auxKwh = v; }
    }

    /** 供电状态数据 */
    public static class PowerSupplyData {
        private String trainId;
        private double voltage;
        private double current;
        private double powerKw;
        private String nearestSubstation;
        private boolean voltageOK;
        public PowerSupplyData() {}
        public PowerSupplyData(String tid, double v, double c, double p, String ns, boolean ok) {
            this.trainId = tid; this.voltage = v; this.current = c; this.powerKw = p; this.nearestSubstation = ns; this.voltageOK = ok;
        }
        public String getTrainId() { return trainId; }
        public void setTrainId(String v) { this.trainId = v; }
        public double getVoltage() { return voltage; }
        public void setVoltage(double v) { this.voltage = v; }
        public double getCurrent() { return current; }
        public void setCurrent(double v) { this.current = v; }
        public double getPowerKw() { return powerKw; }
        public void setPowerKw(double v) { this.powerKw = v; }
        public String getNearestSubstation() { return nearestSubstation; }
        public void setNearestSubstation(String v) { this.nearestSubstation = v; }
        public boolean isVoltageOK() { return voltageOK; }
        public void setVoltageOK(boolean v) { this.voltageOK = v; }
    }
}
