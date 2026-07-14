package com.bjtu.railtransit.signal.model;

/**
 * 道岔模型 —— 对齐工业 CBI 标准，增加锁闭/征用/动作计时/四开/挤岔状态。
 */
public class Switch {
    private String id;
    private double positionM;                // 道岔中心里程
    private int normalSegId;                 // 定位接通 Seg
    private int reverseSegId;                // 反位接通 Seg
    private int mergeSegId;                  // 汇合 Seg
    private int linkedSwitchId;              // 联动道岔（65535=无）
    private SwitchState state;               // NORMAL / REVERSE / FAIL
    private double divergingSpeedLimitKmh;   // 侧向限速 km/h

    // ── 动态属性（工业 CBI 标准）──
    private boolean requisitioned;           // 征用标志：是否被进路征用
    private int requisitionedByRouteId;      // 征用此道岔的进路 ID
    private boolean routeLocked;             // 进路锁闭标志
    private boolean functionLocked;          // 功能锁闭（单锁/封锁，人工操作）
    private boolean guideLocked;             // 引导锁闭标志
    private double actionStartSeconds;       // 动作启动时刻
    private double actionDurationSeconds;    // 动作耗时（秒）
    private boolean actionPending;           // 道岔正在转换中
    private boolean jammed;                  // 挤岔状态

    public Switch() {}

    // ── 静态属性 ──
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public double getPositionM() { return positionM; }
    public void setPositionM(double positionM) { this.positionM = positionM; }
    public int getNormalSegId() { return normalSegId; }
    public void setNormalSegId(int v) { this.normalSegId = v; }
    public int getReverseSegId() { return reverseSegId; }
    public void setReverseSegId(int v) { this.reverseSegId = v; }
    public int getMergeSegId() { return mergeSegId; }
    public void setMergeSegId(int v) { this.mergeSegId = v; }
    public int getLinkedSwitchId() { return linkedSwitchId; }
    public void setLinkedSwitchId(int v) { this.linkedSwitchId = v; }
    public SwitchState getState() { return state; }
    public void setState(SwitchState state) { this.state = state; }
    public double getDivergingSpeedLimitKmh() { return divergingSpeedLimitKmh; }
    public void setDivergingSpeedLimitKmh(double v) { this.divergingSpeedLimitKmh = v; }

    // ── 动态属性 ──
    public boolean isRequisitioned() { return requisitioned; }
    public void setRequisitioned(boolean v) { this.requisitioned = v; }
    public int getRequisitionedByRouteId() { return requisitionedByRouteId; }
    public void setRequisitionedByRouteId(int v) { this.requisitionedByRouteId = v; }
    public boolean isRouteLocked() { return routeLocked; }
    public void setRouteLocked(boolean v) { this.routeLocked = v; }
    public boolean isFunctionLocked() { return functionLocked; }
    public void setFunctionLocked(boolean v) { this.functionLocked = v; }
    public boolean isGuideLocked() { return guideLocked; }
    public void setGuideLocked(boolean v) { this.guideLocked = v; }
    public double getActionStartSeconds() { return actionStartSeconds; }
    public void setActionStartSeconds(double v) { this.actionStartSeconds = v; }
    public double getActionDurationSeconds() { return actionDurationSeconds; }
    public void setActionDurationSeconds(double v) { this.actionDurationSeconds = v; }
    public boolean isActionPending() { return actionPending; }
    public void setActionPending(boolean v) { this.actionPending = v; }
    public boolean isJammed() { return jammed; }
    public void setJammed(boolean v) { this.jammed = v; }

    /** 道岔是否被任何锁闭锁定（含进路锁闭/功能锁闭/引导锁闭）。 */
    public boolean isAnyLocked() { return routeLocked || functionLocked || guideLocked; }

    /** 清除所有锁闭和征用标志（进路释放时调用）。 */
    public void clearAllLocks() {
        this.routeLocked = false;
        this.requisitioned = false;
        this.requisitionedByRouteId = 0;
        this.actionPending = false;
    }
}
