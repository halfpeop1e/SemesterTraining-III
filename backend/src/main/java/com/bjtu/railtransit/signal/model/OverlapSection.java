package com.bjtu.railtransit.signal.model;

import java.util.List;

public class OverlapSection {
    private int id;
    private List<Integer> axleSectionIds;    // 包含计轴区段

    public OverlapSection() {}

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public List<Integer> getAxleSectionIds() { return axleSectionIds; }
    public void setAxleSectionIds(List<Integer> axleSectionIds) { this.axleSectionIds = axleSectionIds; }
}
