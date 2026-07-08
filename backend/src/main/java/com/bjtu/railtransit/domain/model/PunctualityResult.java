package com.bjtu.railtransit.domain.model;

import java.util.List;

/**
 * 准点率评估结果
 */
public class PunctualityResult {
    private double avgDelay;                  // 平均晚点 (s), 负值为早到
    private double maxDelay;                  // 最大晚点 (s)
    private double punctualityRate;           // 准点率 (0-1)
    private List<StationDelay> delayPerStation; // 各站准点详情

    public PunctualityResult() {}

    public double getAvgDelay() { return avgDelay; }
    public void setAvgDelay(double avgDelay) { this.avgDelay = avgDelay; }

    public double getMaxDelay() { return maxDelay; }
    public void setMaxDelay(double maxDelay) { this.maxDelay = maxDelay; }

    public double getPunctualityRate() { return punctualityRate; }
    public void setPunctualityRate(double punctualityRate) { this.punctualityRate = punctualityRate; }

    public List<StationDelay> getDelayPerStation() { return delayPerStation; }
    public void setDelayPerStation(List<StationDelay> delayPerStation) { this.delayPerStation = delayPerStation; }

    public static class StationDelay {
        private int stationId;
        private String stationName;
        private double plannedArrival;   // 计划到站时间 (s)
        private double actualArrival;    // 实际到站时间 (s)
        private double delay;            // 延误 (s)

        public int getStationId() { return stationId; }
        public void setStationId(int stationId) { this.stationId = stationId; }
        public String getStationName() { return stationName; }
        public void setStationName(String stationName) { this.stationName = stationName; }
        public double getPlannedArrival() { return plannedArrival; }
        public void setPlannedArrival(double plannedArrival) { this.plannedArrival = plannedArrival; }
        public double getActualArrival() { return actualArrival; }
        public void setActualArrival(double actualArrival) { this.actualArrival = actualArrival; }
        public double getDelay() { return delay; }
        public void setDelay(double delay) { this.delay = delay; }
    }
}
