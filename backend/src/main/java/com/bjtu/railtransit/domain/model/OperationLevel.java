package com.bjtu.railtransit.domain.model;

/**
 * 运行等级定义 —— ATO 驾驶策略与速度曲线选档。
 *
 * 对应真实 CBTC 系统中 ATO 的驾驶模式 (Driving Mode):
 *   - NORMAL      正常运营级 (70 km/h 巡航, 1.0 m/s² 加速)
 *   - ENERGY_SAVE 节能级     (55 km/h 巡航, 0.7 m/s² 加速, 更早惰行)
 *   - EXPRESS     快车级     (75 km/h 巡航, 甩站通过)
 *   - SLOW        慢行级     (45 km/h 限速, 适用于恶劣天气/故障区段)
 *
 * 参考:
 *   - 北京地铁9号线实际运行等级划分
 *   - IEEE 1474.1 CBTC ATO 驾驶模式定义
 */
public class OperationLevel {

    public static final String NORMAL = "NORMAL";
    public static final String ENERGY_SAVE = "ENERGY_SAVE";
    public static final String EXPRESS = "EXPRESS";
    public static final String SLOW = "SLOW";

    private String levelId;
    private String levelName;
    private double cruiseSpeedKmh;    // 巡航速度 km/h
    private double accelMs2;          // 加速度 m/s²
    private double decelMs2;          // 常用制动减速度 m/s²
    private double dwellFactor;       // 停站时间倍率 (1.0=标准)
    private boolean allowSkipStations;// 是否允许甩站
    private String description;

    public OperationLevel() {}

    public OperationLevel(String levelId, String levelName, double cruiseSpeedKmh,
                          double accelMs2, double decelMs2, double dwellFactor) {
        this.levelId = levelId;
        this.levelName = levelName;
        this.cruiseSpeedKmh = cruiseSpeedKmh;
        this.accelMs2 = accelMs2;
        this.decelMs2 = decelMs2;
        this.dwellFactor = dwellFactor;
    }

    /** 正常运营 (70 km/h) */
    public static OperationLevel normal() {
        return new OperationLevel(NORMAL, "正常运营", 70.0, 1.0, 1.0, 1.0);
    }

    /** 节能模式 (55 km/h, 更柔和加减速) */
    public static OperationLevel energySaving() {
        return new OperationLevel(ENERGY_SAVE, "节能模式", 55.0, 0.7, 0.7, 1.1);
    }

    /** 快车模式 (75 km/h, 甩站) */
    public static OperationLevel express() {
        OperationLevel ol = new OperationLevel(EXPRESS, "快车模式", 75.0, 1.0, 1.0, 0.8);
        ol.setAllowSkipStations(true);
        ol.setDescription("提高巡航速度 + 甩站通过");
        return ol;
    }

    /** 慢行模式 (45 km/h, 限速) */
    public static OperationLevel slow() {
        OperationLevel ol = new OperationLevel(SLOW, "慢行模式", 45.0, 0.8, 0.8, 1.2);
        ol.setDescription("适用于恶劣天气或故障区段慢行");
        return ol;
    }

    // ── Getters / Setters ──

    public String getLevelId() { return levelId; }
    public void setLevelId(String v) { this.levelId = v; }
    public String getLevelName() { return levelName; }
    public void setLevelName(String v) { this.levelName = v; }
    public double getCruiseSpeedKmh() { return cruiseSpeedKmh; }
    public void setCruiseSpeedKmh(double v) { this.cruiseSpeedKmh = v; }
    public double getAccelMs2() { return accelMs2; }
    public void setAccelMs2(double v) { this.accelMs2 = v; }
    public double getDecelMs2() { return decelMs2; }
    public void setDecelMs2(double v) { this.decelMs2 = v; }
    public double getDwellFactor() { return dwellFactor; }
    public void setDwellFactor(double v) { this.dwellFactor = v; }
    public boolean isAllowSkipStations() { return allowSkipStations; }
    public void setAllowSkipStations(boolean v) { this.allowSkipStations = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
}
