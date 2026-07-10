package com.bjtu.railtransit.domain.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 供电分区状态 —— 用于按供电分区统计能耗峰值和风险等级。
 *
 * 当前 MVP 阶段简化设置三个分区:
 *   PowerSection A: 郭公庄—七里庄 (站1-6)
 *   PowerSection B: 七里庄—北京西站 (站6-9)
 *   PowerSection C: 北京西站—国家图书馆 (站9-13)
 *
 * 每个仿真步计算: P_section(t) = Σ P_train_in_section(t)
 * 如果某个供电分区超过阈值，则该分区进入 WARNING 或 DANGER 状态。
 *
 * 注意: 能耗计算的具体逻辑由 EnergyOptimizer 模块负责(组员负责),
 * 本数据结构为协作接口预留。
 */
public class PowerSectionStatus {

    private String sectionId;
    private String startStationName;
    private String endStationName;
    private int startStationId;
    private int endStationId;
    private List<String> trainsInSection;
    private double totalTractionPowerKw;
    private double peakThresholdKw;
    private String riskLevel; // NORMAL | WARNING | DANGER

    public PowerSectionStatus() {
        this.trainsInSection = new ArrayList<>();
    }

    /** 预定义三个供电分区 */
    public static List<PowerSectionStatus> createDefaultSections() {
        List<PowerSectionStatus> sections = new ArrayList<>();

        PowerSectionStatus a = new PowerSectionStatus();
        a.sectionId = "PS_A";
        a.startStationName = "郭公庄";
        a.endStationName = "七里庄";
        a.startStationId = 1;
        a.endStationId = 6;
        a.peakThresholdKw = 3000.0;
        a.riskLevel = "NORMAL";
        sections.add(a);

        PowerSectionStatus b = new PowerSectionStatus();
        b.sectionId = "PS_B";
        b.startStationName = "七里庄";
        b.endStationName = "北京西站";
        b.startStationId = 6;
        b.endStationId = 9;
        b.peakThresholdKw = 3000.0;
        b.riskLevel = "NORMAL";
        sections.add(b);

        PowerSectionStatus c = new PowerSectionStatus();
        c.sectionId = "PS_C";
        c.startStationName = "北京西站";
        c.endStationName = "国家图书馆";
        c.startStationId = 9;
        c.endStationId = 13;
        c.peakThresholdKw = 3000.0;
        c.riskLevel = "NORMAL";
        sections.add(c);

        return sections;
    }

    // ── Getters / Setters ──

    public String getSectionId() { return sectionId; }
    public void setSectionId(String v) { this.sectionId = v; }

    public String getStartStationName() { return startStationName; }
    public void setStartStationName(String v) { this.startStationName = v; }

    public String getEndStationName() { return endStationName; }
    public void setEndStationName(String v) { this.endStationName = v; }

    public int getStartStationId() { return startStationId; }
    public void setStartStationId(int v) { this.startStationId = v; }

    public int getEndStationId() { return endStationId; }
    public void setEndStationId(int v) { this.endStationId = v; }

    public List<String> getTrainsInSection() { return trainsInSection; }
    public void setTrainsInSection(List<String> v) { this.trainsInSection = v; }

    public double getTotalTractionPowerKw() { return totalTractionPowerKw; }
    public void setTotalTractionPowerKw(double v) { this.totalTractionPowerKw = v; }

    public double getPeakThresholdKw() { return peakThresholdKw; }
    public void setPeakThresholdKw(double v) { this.peakThresholdKw = v; }

    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String v) { this.riskLevel = v; }
}
