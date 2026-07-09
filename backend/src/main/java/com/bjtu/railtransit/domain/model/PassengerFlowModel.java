package com.bjtu.railtransit.domain.model;

import java.util.*;

/**
 * 客流倍率模型 —— 基于时段 + 站点权重 + 断面客流累积的规则模型。
 *
 * 核心公式:
 *   SectionLoad_i = SectionLoad_{i-1} + Boarding_i - Alighting_i
 *   Boarding_i(t) = StationActivity_i(t) × boardingRatio_i
 *   Alighting_i(t) = StationActivity_i(t) × alightingRatio_i
 *   StationActivity_i(t) = BASE_FLOW × PeriodMultiplier(t) × StationWeight_i × EventMultiplier
 *   P_peak_section(t) = max(SectionLoad_i)
 *   h_demand = (3600 × C × L_target) / P_peak_section(t)
 *
 * 参考:
 *   - 城市轨道交通断面客流预测与运力配置研究 (交通运输系统工程与信息, 2024)
 *   - Transit Capacity and Quality of Service Manual (TCRP Report 165, 3rd Edition)
 */
public class PassengerFlowModel {

    // ================================================================
    // 时段定义
    // ================================================================
    public enum TimePeriod {
        EARLY_MORNING(0, 6, 0.3, "凌晨"),
        MORNING_PEAK(7, 9, 2.0, "早高峰"),
        MIDDAY(10, 16, 0.6, "平峰"),
        EVENING_PEAK(17, 19, 1.8, "晚高峰"),
        NIGHT(20, 23, 0.4, "夜间");

        public final int startHour;
        public final int endHour;
        public final double baseMultiplier;
        public final String label;

        TimePeriod(int startHour, int endHour, double baseMultiplier, String label) {
            this.startHour = startHour;
            this.endHour = endHour;
            this.baseMultiplier = baseMultiplier;
            this.label = label;
        }

        public static TimePeriod fromSimTime(double simTimeSeconds) {
            int hour = ((int) (simTimeSeconds / 3600)) % 24;
            for (TimePeriod tp : values()) {
                if (hour >= tp.startHour && hour <= tp.endHour) return tp;
            }
            return MIDDAY;
        }
    }

    // ================================================================
    // 站点参数
    //     weight: 站点人流密度权重（枢纽站/换乘站更高）
    //     boardingRatio: 上客率 (0~1), 越靠近起点越大
    //     alightingRatio: 下客率 (0~1), 越靠近终点越大
    //     boardingRatio + alightingRatio = 1.0
    // ================================================================
    public static final Map<Integer, Double> STATION_WEIGHTS = new LinkedHashMap<>();
    public static final Map<Integer, Double> BOARDING_RATIO = new LinkedHashMap<>();
    public static final Map<Integer, Double> ALIGHTING_RATIO = new LinkedHashMap<>();

    static {
        // id: name, weight, boarding%, alighting%
        // Direction: 郭公庄(1) → 国家图书馆(13)
        putStation(1,  0.6,  0.95, 0.05); // 郭公庄 始发站
        putStation(2,  0.8,  0.85, 0.15); // 丰台科技园
        putStation(3,  0.7,  0.80, 0.20); // 科怡路
        putStation(4,  0.9,  0.75, 0.25); // 丰台南路
        putStation(5,  0.8,  0.70, 0.30); // 丰台东大街
        putStation(6,  1.0,  0.65, 0.35); // 七里庄
        putStation(7,  1.3,  0.55, 0.45); // 六里桥 换乘10号线
        putStation(8,  1.0,  0.50, 0.50); // 六里桥东
        putStation(9,  2.0,  0.50, 0.50); // 北京西站 枢纽站
        putStation(10, 1.4,  0.45, 0.55); // 军事博物馆 换乘1号线
        putStation(11, 0.8,  0.35, 0.65); // 白堆子
        putStation(12, 1.1,  0.30, 0.70); // 白石桥南 换乘6号线
        putStation(13, 1.3,  0.10, 0.90); // 国家图书馆 终点 换乘4/16号线
    }

    private static void putStation(int id, double weight, double board, double alight) {
        STATION_WEIGHTS.put(id, weight);
        BOARDING_RATIO.put(id, board);
        ALIGHTING_RATIO.put(id, alight);
    }

    /** 返回站点ID列表(按途径顺序) */
    public static List<Integer> getStationIds() {
        return new ArrayList<>(STATION_WEIGHTS.keySet());
    }

    // ================================================================
    // 常量参数
    // ================================================================
    public static final double BASE_FLOW_PER_STATION = 800.0;   // 平峰基准 人次/h
    public static final int TRAIN_CAPACITY = 1460;              // 6B定员
    public static final double TARGET_LOAD_FACTOR = 0.85;       // 目标满载率
    public static final double MIN_HEADWAY_SEC = 120;            // 最密间隔 (CBTC限制)
    public static final double MAX_HEADWAY_SEC = 600;            // 最疏间隔
    public static final int AVAILABLE_TRAINS = 12;               // 可用列车数
    public static final double SINGLE_TRIP_TIME_SEC = 33 * 60;
    public static final double TURNAROUND_TIME_SEC = 5 * 60;
    public static final double CYCLE_TIME_SEC = 2 * SINGLE_TRIP_TIME_SEC + 2 * TURNAROUND_TIME_SEC;

    public static final double EVENT_STORM = 1.5;
    public static final double EVENT_HOLIDAY = 0.5;
    public static final double EVENT_NONE = 1.0;

    private double eventMultiplier = EVENT_NONE;

    // ================================================================
    // 公开方法
    // ================================================================

    public void setEventMultiplier(double v) { this.eventMultiplier = v; }
    public double getEventMultiplier() { return eventMultiplier; }
    public TimePeriod getCurrentPeriod(double simTimeSeconds) { return TimePeriod.fromSimTime(simTimeSeconds); }

    /**
     * 站点 i 的总活动量（上下车合计，人次/小时）
     */
    public double getStationActivity(int stationId, double simTimeSeconds) {
        TimePeriod period = TimePeriod.fromSimTime(simTimeSeconds);
        double weight = STATION_WEIGHTS.getOrDefault(stationId, 0.8);
        return BASE_FLOW_PER_STATION * period.baseMultiplier * weight * eventMultiplier;
    }

    /**
     * 站点 i 的上车人数（人次/小时）
     */
    public double getBoarding(int stationId, double simTimeSeconds) {
        double activity = getStationActivity(stationId, simTimeSeconds);
        double ratio = BOARDING_RATIO.getOrDefault(stationId, 0.5);
        return activity * ratio;
    }

    /**
     * 站点 i 的下车人数（人次/小时）
     */
    public double getAlighting(int stationId, double simTimeSeconds) {
        double activity = getStationActivity(stationId, simTimeSeconds);
        double ratio = ALIGHTING_RATIO.getOrDefault(stationId, 0.5);
        return activity * ratio;
    }

    /**
     * 【核心】计算每个区间的断面客流 Load_i。
     * Load_i = Load_{i-1} + Boarding_i - Alighting_i
     *
     * 返回 Map: stationId → 离开该站后的车上人数
     */
    public Map<Integer, Double> calculateSectionLoads(double simTimeSeconds) {
        Map<Integer, Double> loads = new LinkedHashMap<>();
        double cumulative = 0;

        for (int stationId : STATION_WEIGHTS.keySet()) {
            cumulative += getBoarding(stationId, simTimeSeconds);
            cumulative -= getAlighting(stationId, simTimeSeconds);
            cumulative = Math.max(0, cumulative); // 不允许负值
            loads.put(stationId, cumulative);
        }
        return loads;
    }

    /**
     * 【核心】最大断面客流（人次/小时）
     */
    public double getPeakSectionFlow(double simTimeSeconds) {
        Map<Integer, Double> loads = calculateSectionLoads(simTimeSeconds);
        return loads.values().stream().mapToDouble(Double::doubleValue).max().orElse(500.0);
    }

    /**
     * 【核心】获取峰值断面所在站点ID
     */
    public int getPeakSectionStationId(double simTimeSeconds) {
        Map<Integer, Double> loads = calculateSectionLoads(simTimeSeconds);
        return loads.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(9); // 默认北京西站
    }

    /**
     * 基于【最大断面客流】计算推荐发车间隔
     * h_demand = (3600 × C × L_target) / P_peak_section(t)
     */
    public double calculateDemandHeadway(double simTimeSeconds) {
        double peakFlow = getPeakSectionFlow(simTimeSeconds);
        if (peakFlow <= 0) return MAX_HEADWAY_SEC;
        double h = (3600.0 * TRAIN_CAPACITY * TARGET_LOAD_FACTOR) / peakFlow;
        return clamp(h, MIN_HEADWAY_SEC, MAX_HEADWAY_SEC);
    }

    /**
     * 根据发车间隔计算所需上线列车数
     */
    public int calculateRequiredTrains(double headwaySeconds) {
        return (int) Math.ceil(CYCLE_TIME_SEC / headwaySeconds);
    }

    public static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * 快照 DTO
     */
    public PassengerFlowInfo getFlowInfo(double simTimeSeconds) {
        PassengerFlowInfo info = new PassengerFlowInfo();
        info.setSimTimeSeconds(simTimeSeconds);
        info.setPeriod(getCurrentPeriod(simTimeSeconds).label);
        info.setPeriodMultiplier(getCurrentPeriod(simTimeSeconds).baseMultiplier);
        info.setEventMultiplier(eventMultiplier);

        // 断面客流
        Map<Integer, Double> sectionLoads = calculateSectionLoads(simTimeSeconds);
        info.setPeakSectionFlow(getPeakSectionFlow(simTimeSeconds));
        info.setPeakSectionStationId(getPeakSectionStationId(simTimeSeconds));

        // 断面客流明细: key = "fromStationId-toStationId", value = load
        List<PassengerFlowInfo.SectionFlowItem> items = new ArrayList<>();
        List<Integer> ids = getStationIds();
        for (int i = 0; i < ids.size() - 1; i++) {
            int fromId = ids.get(i);
            int toId = ids.get(i + 1);
            PassengerFlowInfo.SectionFlowItem item = new PassengerFlowInfo.SectionFlowItem();
            item.setFromStationId(fromId);
            item.setToStationId(toId);
            item.setLoad(sectionLoads.getOrDefault(fromId, 0.0));
            item.setBoarding(getBoarding(fromId, simTimeSeconds));
            item.setAlighting(getAlighting(fromId, simTimeSeconds));
            item.setLoadFactor(sectionLoads.getOrDefault(fromId, 0.0) / (TRAIN_CAPACITY * TARGET_LOAD_FACTOR));
            items.add(item);
        }
        info.setSectionFlows(items);

        info.setDemandHeadway(calculateDemandHeadway(simTimeSeconds));
        info.setRequiredTrains(calculateRequiredTrains(info.getDemandHeadway()));
        info.setAvailableTrains(AVAILABLE_TRAINS);
        info.setTargetLoadFactor(TARGET_LOAD_FACTOR);
        return info;
    }

    /**
     * 客流状态快照
     */
    public static class PassengerFlowInfo {
        private double simTimeSeconds;
        private String period;
        private double periodMultiplier;
        private double eventMultiplier;
        private double peakSectionFlow;
        private int peakSectionStationId;
        private List<SectionFlowItem> sectionFlows = new ArrayList<>();
        private double demandHeadway;
        private int requiredTrains;
        private int availableTrains;
        private double targetLoadFactor;

        // getters / setters
        public double getSimTimeSeconds() { return simTimeSeconds; }
        public void setSimTimeSeconds(double v) { this.simTimeSeconds = v; }
        public String getPeriod() { return period; }
        public void setPeriod(String v) { this.period = v; }
        public double getPeriodMultiplier() { return periodMultiplier; }
        public void setPeriodMultiplier(double v) { this.periodMultiplier = v; }
        public double getEventMultiplier() { return eventMultiplier; }
        public void setEventMultiplier(double v) { this.eventMultiplier = v; }
        public double getPeakSectionFlow() { return peakSectionFlow; }
        public void setPeakSectionFlow(double v) { this.peakSectionFlow = v; }
        public int getPeakSectionStationId() { return peakSectionStationId; }
        public void setPeakSectionStationId(int v) { this.peakSectionStationId = v; }
        public List<SectionFlowItem> getSectionFlows() { return sectionFlows; }
        public void setSectionFlows(List<SectionFlowItem> v) { this.sectionFlows = v; }
        public double getDemandHeadway() { return demandHeadway; }
        public void setDemandHeadway(double v) { this.demandHeadway = v; }
        public int getRequiredTrains() { return requiredTrains; }
        public void setRequiredTrains(int v) { this.requiredTrains = v; }
        public int getAvailableTrains() { return availableTrains; }
        public void setAvailableTrains(int v) { this.availableTrains = v; }
        public double getTargetLoadFactor() { return targetLoadFactor; }
        public void setTargetLoadFactor(double v) { this.targetLoadFactor = v; }

        public static class SectionFlowItem {
            private int fromStationId;
            private int toStationId;
            private double load;          // 断面客流人次/h
            private double boarding;      // 上客量
            private double alighting;     // 下客量
            private double loadFactor;    // 满载率

            public int getFromStationId() { return fromStationId; }
            public void setFromStationId(int v) { this.fromStationId = v; }
            public int getToStationId() { return toStationId; }
            public void setToStationId(int v) { this.toStationId = v; }
            public double getLoad() { return load; }
            public void setLoad(double v) { this.load = v; }
            public double getBoarding() { return boarding; }
            public void setBoarding(double v) { this.boarding = v; }
            public double getAlighting() { return alighting; }
            public void setAlighting(double v) { this.alighting = v; }
            public double getLoadFactor() { return loadFactor; }
            public void setLoadFactor(double v) { this.loadFactor = v; }
        }
    }
}
