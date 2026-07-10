package com.bjtu.railtransit.domain.model;

/**
 * 列车制动系统实时状态 —— 对应 doc.md 架构图中制动系统的内部状态。
 *
 * 电空配合逻辑 (Blending):
 *   电制动优先 (再生→电网/电阻消耗), 不足时空气制动补充。
 *   低速 (~5km/h) 电制动退出, 完全切换至空气制动。
 */
public class BrakingSystemState {

    private String trainId;
    /** NORMAL | DEGRADED | FAULT */
    private String health = "NORMAL";
    /** 空气制动最大能力 (N) */
    private double maxAirBrakeForceN;
    /** 电制动请求值 (N) —— 制动→牵引方向 */
    private double electricBrakeRequestN;
    /** 当前电空配合模式: ELEC_ONLY | BLEND | AIR_ONLY */
    private String blendingMode = "BLEND";
    /** 故障码 */
    private String faultCode;

    /** 6B编组空气制动能力 ≈ 380kN (常用全制动) */
    public BrakingSystemState() {}

    public static BrakingSystemState createDefault(String trainId, int carCount) {
        BrakingSystemState s = new BrakingSystemState();
        s.trainId = trainId;
        // 常用全制动减速度 ≈ 1.2 m/s², 取 85% 为最大能力 (含余量)
        double massKg = carCount * 35_000.0;
        s.maxAirBrakeForceN = massKg * 1.2 * 0.85;
        return s;
    }

    // ── Getters / Setters ──

    public String getTrainId() { return trainId; }
    public void setTrainId(String v) { this.trainId = v; }

    public String getHealth() { return health; }
    public void setHealth(String v) { this.health = v; }

    public double getMaxAirBrakeForceN() { return maxAirBrakeForceN; }
    public void setMaxAirBrakeForceN(double v) { this.maxAirBrakeForceN = v; }

    public double getElectricBrakeRequestN() { return electricBrakeRequestN; }
    public void setElectricBrakeRequestN(double v) { this.electricBrakeRequestN = v; }

    public String getBlendingMode() { return blendingMode; }
    public void setBlendingMode(String v) { this.blendingMode = v; }

    public String getFaultCode() { return faultCode; }
    public void setFaultCode(String v) { this.faultCode = v; }
}
