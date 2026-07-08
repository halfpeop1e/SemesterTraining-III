package com.bjtu.railtransit.domain.model;

import java.util.List;

/**
 * 交路模式定义 —— 列车运行的服务路径与折返规则。
 *
 * 城轨运营中常用的几种交路模式:
 *   1. FULL     全程交路: 郭公庄 ↔ 国家图书馆 (站站停)
 *   2. SHORT_N  小交路(北段): 六里桥 → 国家图书馆 (仅北段)
 *   3. SHORT_S  小交路(南段): 郭公庄 → 六里桥 (仅南段)
 *   4. EXPRESS  快慢车: 部分车站甩站通过, 不折返差异
 *
 * 折返参数:
 *   TURNAROUND_MIN = 120s (最小折返时间, 含清客+换端)
 *   TURNAROUND_NORM = 180s (标准折返时间)
 *   TURNAROUND_PEAK = 240s (高峰折返附加)
 */
public class RoutePattern {

    /** 全程交路 */
    public static final String FULL = "FULL";
    /** 小交路(北段) */
    public static final String SHORT_N = "SHORT_N";
    /** 小交路(南段) */
    public static final String SHORT_S = "SHORT_S";
    /** 快车 */
    public static final String EXPRESS = "EXPRESS";

    /** 最小折返时间 秒 */
    public static final double TURNAROUND_MIN = 120;
    /** 标准折返时间 秒 */
    public static final double TURNAROUND_NORM = 180;
    /** 高峰折返时间 秒 */
    public static final double TURNAROUND_PEAK = 240;

    private String patternId;
    private String patternName;
    private String description;
    /** 交路起始站索引 (0-based, 各方向) */
    private int upStartStationIndex;
    /** 交路终点站索引 (0-based, 上行) */
    private int upEndStationIndex;
    /** 交路终点站索引 (下行起点, 折返站) */
    private int downStartStationIndex;
    /** 交路起始站索引 (下行终点, 折返站) */
    private int downEndStationIndex;
    /** 甩站列表 (这些站的索引在EXPRESS模式下跳过) */
    private List<Integer> skipStations;

    public RoutePattern() {}

    public RoutePattern(String patternId, String patternName, int upStart, int upEnd, int downStart, int downEnd) {
        this.patternId = patternId;
        this.patternName = patternName;
        this.upStartStationIndex = upStart;
        this.upEndStationIndex = upEnd;
        this.downStartStationIndex = downStart;
        this.downEndStationIndex = downEnd;
    }

    /** 全程交路 (13站: 0-郭公庄 ~ 12-国家图书馆) */
    public static RoutePattern fullRoute() {
        return new RoutePattern(FULL, "全程交路", 0, 12, 12, 0);
    }

    /** 北段小交路 (站6-六里桥 ~ 站12-国家图书馆) */
    public static RoutePattern shortNorth() {
        RoutePattern rp = new RoutePattern(SHORT_N, "北段小交路(六里桥-国图)", 6, 12, 12, 6);
        rp.setDescription("六里桥 ⇄ 国家图书馆 (7站)");
        return rp;
    }

    /** 南段小交路 (站0-郭公庄 ~ 站6-六里桥) */
    public static RoutePattern shortSouth() {
        RoutePattern rp = new RoutePattern(SHORT_S, "南段小交路(郭公庄-六里桥)", 0, 6, 6, 0);
        rp.setDescription("郭公庄 ⇄ 六里桥 (7站)");
        return rp;
    }

    /**
     * 判断某站点在此交路的该方向是否停靠
     */
    public boolean stopsAt(int stationIndex, boolean isUpDirection) {
        int start = isUpDirection ? upStartStationIndex : (downStartStationIndex >= 0 ? downStartStationIndex : upEndStationIndex);
        int end = isUpDirection ? upEndStationIndex : (downEndStationIndex >= 0 ? downEndStationIndex : upStartStationIndex);

        if (isUpDirection) {
            if (stationIndex < start || stationIndex > end) return false;
        } else {
            if (stationIndex > start || stationIndex < end) return false;
        }

        if (skipStations != null && skipStations.contains(stationIndex)) return false;
        return true;
    }

    /**
     * 获取该交路上行方向的站点数
     */
    public int getUpStationCount() {
        return upEndStationIndex - upStartStationIndex + 1;
    }

    /**
     * 获取该交路下行方向的站点数
     */
    public int getDownStationCount() {
        if (downStartStationIndex >= 0 && downEndStationIndex >= 0) {
            return Math.abs(downStartStationIndex - downEndStationIndex) + 1;
        }
        return getUpStationCount();
    }

    // ── Getters / Setters ──

    public String getPatternId() { return patternId; }
    public void setPatternId(String v) { this.patternId = v; }
    public String getPatternName() { return patternName; }
    public void setPatternName(String v) { this.patternName = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public int getUpStartStationIndex() { return upStartStationIndex; }
    public void setUpStartStationIndex(int v) { this.upStartStationIndex = v; }
    public int getUpEndStationIndex() { return upEndStationIndex; }
    public void setUpEndStationIndex(int v) { this.upEndStationIndex = v; }
    public int getDownStartStationIndex() { return downStartStationIndex; }
    public void setDownStartStationIndex(int v) { this.downStartStationIndex = v; }
    public int getDownEndStationIndex() { return downEndStationIndex; }
    public void setDownEndStationIndex(int v) { this.downEndStationIndex = v; }
    public List<Integer> getSkipStations() { return skipStations; }
    public void setSkipStations(List<Integer> v) { this.skipStations = v; }
}
