package com.bjtu.railtransit.signal.model;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

public class AxleCounterSection {
    private int id;
    private String name;                     // JZ1 ...
    private List<Integer> segIds;            // 包含 Seg 列表
    private boolean occupied;                // 动态占用态（每 tick 填充）
    private final Set<String> occupyingTrainIds = new LinkedHashSet<>();

    public AxleCounterSection() {}

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<Integer> getSegIds() { return segIds; }
    public void setSegIds(List<Integer> segIds) { this.segIds = segIds; }
    public boolean isOccupied() { return occupied; }
    public void setOccupied(boolean occupied) {
        this.occupied = occupied;
        if (!occupied) occupyingTrainIds.clear();
    }
    public Set<String> getOccupyingTrainIds() { return Set.copyOf(occupyingTrainIds); }
    public void addOccupyingTrain(String trainId) {
        occupied = true;
        if (trainId != null && !trainId.isBlank()) occupyingTrainIds.add(trainId);
    }
    public boolean isOccupiedBy(String trainId) { return occupyingTrainIds.contains(trainId); }
    public boolean isOccupiedByOtherThan(String trainId) {
        if (!occupied) return false;
        // Empty owner set means an external/unknown occupancy and must fail safe.
        return occupyingTrainIds.isEmpty()
                || occupyingTrainIds.stream().anyMatch(id -> !id.equals(trainId));
    }
}
