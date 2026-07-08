package com.bjtu.railtransit.signal.model;

import java.util.List;

public class AxleCounterSection {
    private int id;
    private String name;                     // JZ1 ...
    private List<Integer> segIds;            // 包含 Seg 列表
    private boolean occupied;                // 动态占用态（每 tick 填充）

    public AxleCounterSection() {}

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<Integer> getSegIds() { return segIds; }
    public void setSegIds(List<Integer> segIds) { this.segIds = segIds; }
    public boolean isOccupied() { return occupied; }
    public void setOccupied(boolean occupied) { this.occupied = occupied; }
}
