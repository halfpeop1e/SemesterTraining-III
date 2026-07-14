package com.bjtu.railtransit.signal.model;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class AxleCounterSection {
    private int id;
    private String name;                     // JZ1 ...
    private List<Integer> segIds;            // 包含 Seg 列表
    private boolean occupied;                // 动态占用态（每 tick 填充）
    private final Set<String> occupyingTrainIds = new LinkedHashSet<>();
    /** 占用列车ID → 方向 (0x55=UP, 0xAA=DOWN)，用于上下行隔离判断 */
    private final Map<String, Integer> trainDirections = new LinkedHashMap<>();

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
        if (!occupied) {
            occupyingTrainIds.clear();
            trainDirections.clear();
        }
    }
    public Set<String> getOccupyingTrainIds() { return Set.copyOf(occupyingTrainIds); }

    public void addOccupyingTrain(String trainId) {
        addOccupyingTrain(trainId, 0);
    }

    /** 记录占用列车及其方向，方向未知时传 0 */
    public void addOccupyingTrain(String trainId, int direction) {
        occupied = true;
        if (trainId != null && !trainId.isBlank()) {
            occupyingTrainIds.add(trainId);
            trainDirections.put(trainId, direction);
        }
    }

    public boolean isOccupiedBy(String trainId) { return occupyingTrainIds.contains(trainId); }

    /** @deprecated 使用方向感知版本 isOccupiedByOtherThan(trainId, direction) */
    public boolean isOccupiedByOtherThan(String trainId) {
        if (!occupied) return false;
        return occupyingTrainIds.isEmpty()
                || occupyingTrainIds.stream().anyMatch(id -> !id.equals(trainId));
    }

    /**
     * 方向感知占用检查：只检查同向列车的占用。
     * 上下行列车使用不同物理轨道，异向占用不应阻塞进路建立。
     *
     * @param trainId  本车ID
     * @param direction 进路方向 (0x55=UP, 0xAA=DOWN)
     */
    public boolean isOccupiedByOtherThan(String trainId, int direction) {
        if (!occupied) return false;
        if (occupyingTrainIds.isEmpty()) return true; // 未知占用，安全侧保守阻塞
        for (String id : occupyingTrainIds) {
            if (id.equals(trainId)) continue;
            int dir = trainDirections.getOrDefault(id, 0);
            // 方向未知(0)的占用按安全侧处理 → 阻塞
            if (dir == 0) return true;
            // 同向占用才阻塞
            if (dir == direction) return true;
        }
        return false;
    }
}
