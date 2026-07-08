package com.bjtu.railtransit.signal.model;

import java.util.List;

public class Route {
    private int id;
    private String name;                     // XQ1-Z5
    private int startSignalId;
    private int endSignalId;
    private List<Integer> axleSectionIds;    // 进路内计轴区段序列
    private List<Integer> overlapIds;        // 关联保护区段
    private int ciZoneId;

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
}
