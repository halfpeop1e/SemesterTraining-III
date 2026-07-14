package com.bjtu.railtransit.signal.model;

import java.util.List;

/**
 * 进路数据模型 —— 对齐工业 CBI 联锁表标准。
 * 增加道岔号+位置要求、接近/离去区段、防护道岔、进路类型、操作来源标记。
 */
public class Route {
    private int id;
    private String name;
    private int startSignalId;
    private int endSignalId;
    private List<Integer> axleSectionIds;    // 进路内计轴区段序列
    private List<Integer> overlapIds;        // 关联保护区段
    private int ciZoneId;

    // ── 工业 CBI 联锁表新增字段 ──
    private int routeType;                   // 0=列车进路, 1=调车进路
    private List<Integer> switchIds;         // 进路上道岔编号
    private List<Integer> switchPositions;   // 进路上道岔要求位置：0=定位(NORMAL), 1=反位(REVERSE)
    private List<Integer> guardSwitchIds;    // 防护道岔编号（不在进路上但需防护）
    private List<Integer> guardSwitchPositions; // 防护道岔要求位置
    private List<Integer> approachSectionIds;// 接近区段（一/二/三接近）
    private List<Integer> departureSectionIds;// 离去区段
    private List<Integer> infringementSectionIds; // 侵限区段
    private List<Integer> conflictRouteIds;  // 敌对进路 ID 列表
    private int serviceId;                   // 关联服务任务 ID

    // ── 运行时状态 ──
    private RouteLockState lockState;        // 进路锁闭状态
    private String source;                   // 操作来源: AUTO / MANUAL

    public Route() {}

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getStartSignalId() { return startSignalId; }
    public void setStartSignalId(int v) { this.startSignalId = v; }
    public int getEndSignalId() { return endSignalId; }
    public void setEndSignalId(int v) { this.endSignalId = v; }
    public List<Integer> getAxleSectionIds() { return axleSectionIds; }
    public void setAxleSectionIds(List<Integer> v) { this.axleSectionIds = v; }
    public List<Integer> getOverlapIds() { return overlapIds; }
    public void setOverlapIds(List<Integer> v) { this.overlapIds = v; }
    public int getCiZoneId() { return ciZoneId; }
    public void setCiZoneId(int v) { this.ciZoneId = v; }

    public int getRouteType() { return routeType; }
    public void setRouteType(int v) { this.routeType = v; }
    public List<Integer> getSwitchIds() { return switchIds; }
    public void setSwitchIds(List<Integer> v) { this.switchIds = v; }
    public List<Integer> getSwitchPositions() { return switchPositions; }
    public void setSwitchPositions(List<Integer> v) { this.switchPositions = v; }
    public List<Integer> getGuardSwitchIds() { return guardSwitchIds; }
    public void setGuardSwitchIds(List<Integer> v) { this.guardSwitchIds = v; }
    public List<Integer> getGuardSwitchPositions() { return guardSwitchPositions; }
    public void setGuardSwitchPositions(List<Integer> v) { this.guardSwitchPositions = v; }
    public List<Integer> getApproachSectionIds() { return approachSectionIds; }
    public void setApproachSectionIds(List<Integer> v) { this.approachSectionIds = v; }
    public List<Integer> getDepartureSectionIds() { return departureSectionIds; }
    public void setDepartureSectionIds(List<Integer> v) { this.departureSectionIds = v; }
    public List<Integer> getInfringementSectionIds() { return infringementSectionIds; }
    public void setInfringementSectionIds(List<Integer> v) { this.infringementSectionIds = v; }
    public List<Integer> getConflictRouteIds() { return conflictRouteIds; }
    public void setConflictRouteIds(List<Integer> v) { this.conflictRouteIds = v; }
    public int getServiceId() { return serviceId; }
    public void setServiceId(int v) { this.serviceId = v; }
    public RouteLockState getLockState() { return lockState; }
    public void setLockState(RouteLockState v) { this.lockState = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
}
