package com.bjtu.railtransit.signal.model;

import java.util.List;

public class PhysicalSection {
    private int id;
    private String name;
    private List<Integer> axleSectionIds;

    public PhysicalSection() {}

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<Integer> getAxleSectionIds() { return axleSectionIds; }
    public void setAxleSectionIds(List<Integer> axleSectionIds) { this.axleSectionIds = axleSectionIds; }
}
