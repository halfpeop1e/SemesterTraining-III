package com.bjtu.railtransit.domain.model;

/**
 * 列车牵引系统实时状态 —— 对应 doc.md 架构图中牵引系统的内部状态。
 *
 * CBTC 对应 (IEEE 1474.1): ATP 必须感知列车牵引能力变化以调整安全制动曲线。
 * VVVF 逆变器 → 牵引电机 → 转矩 → 轮轨黏着 → 牵引力/电制动力
 */
public class TractionSystemState {

    private String trainId;
    /** NORMAL | DEGRADED | FAULT */
    private String health = "NORMAL";
    /** VVVF 逆变器当前最大牵引力 (N) */
    private double maxTractiveForceN;
    /** 可用牵引电机数 (默认 carCount × 4) */
    private int availableMotors;
    /** 电制动是否可用 */
    private boolean electricBrakeAvailable = true;
    /** 电制动当前最大能力 (N) */
    private double maxElectricBrakeForceN;
    /** 当前实际施加的电制动力 (N) —— 牵引→制动方向反馈 */
    private double electricBrakeAppliedN;
    /** 故障码 */
    private String faultCode;

    /** 6B编组默认: 6节 × 4电机/节 = 24台 */
    public TractionSystemState() {}

    public static TractionSystemState createDefault(String trainId, int carCount) {
        TractionSystemState s = new TractionSystemState();
        s.trainId = trainId;
        s.availableMotors = carCount * 4;
        // 6B编组恒转矩区最大牵引力 ≈ 310kN (24台×12.9kN)
        s.maxTractiveForceN = s.availableMotors * 12_900.0;
        // 电制动最大能力略低于牵引 (电机作为发电机时能力约80%)
        s.maxElectricBrakeForceN = s.maxTractiveForceN * 0.80;
        return s;
    }

    // ── Getters / Setters ──

    public String getTrainId() { return trainId; }
    public void setTrainId(String v) { this.trainId = v; }

    public String getHealth() { return health; }
    public void setHealth(String v) { this.health = v; }

    public double getMaxTractiveForceN() { return maxTractiveForceN; }
    public void setMaxTractiveForceN(double v) { this.maxTractiveForceN = v; }

    public int getAvailableMotors() { return availableMotors; }
    public void setAvailableMotors(int v) { this.availableMotors = v; }

    public boolean isElectricBrakeAvailable() { return electricBrakeAvailable; }
    public void setElectricBrakeAvailable(boolean v) { this.electricBrakeAvailable = v; }

    public double getMaxElectricBrakeForceN() { return maxElectricBrakeForceN; }
    public void setMaxElectricBrakeForceN(double v) { this.maxElectricBrakeForceN = v; }

    public double getElectricBrakeAppliedN() { return electricBrakeAppliedN; }
    public void setElectricBrakeAppliedN(double v) { this.electricBrakeAppliedN = v; }

    public String getFaultCode() { return faultCode; }
    public void setFaultCode(String v) { this.faultCode = v; }
}
