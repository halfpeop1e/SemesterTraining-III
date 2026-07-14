package com.bjtu.railtransit.signal.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LineProfile {
    private String lineId;
    private String name;
    private List<Station> stations;
    private List<Platform> platforms;
    private List<TrackSegment> segments;
    private List<Switch> switches;
    private List<Turnback> turnbacks;
    private List<TemporarySpeedRestriction> tsrs;
    private List<StaticSpeedRestriction> staticSpeedRestrictions;
    private List<Gradient> gradients;
    private List<Signal> signals;
    private List<Balise> balises;
    private List<AxleCounterSection> axleSections;
    private List<LogicalSection> logicalSections;
    private List<Route> routes;
    private List<OverlapSection> overlaps;
    private List<PhysicalSection> physicalSections;
    private double totalLengthM;

    /** Seg 里程索引：segId → {startM, endM}（绝对里程 m，由 buildMileageIndex 计算） */
    private Map<String, double[]> segmentMileage = new HashMap<>();

    /** 相邻/关联 哨兵值（xls 约定 65535 = 无） */
    public static final int NONE_ID = 65535;

    public LineProfile() {}

    public String getLineId() { return lineId; }
    public void setLineId(String lineId) { this.lineId = lineId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<Station> getStations() { return stations; }
    public void setStations(List<Station> stations) { this.stations = stations; }
    public List<Platform> getPlatforms() { return platforms; }
    public void setPlatforms(List<Platform> platforms) { this.platforms = platforms; }
    public List<TrackSegment> getSegments() { return segments; }
    public void setSegments(List<TrackSegment> segments) { this.segments = segments; }
    public List<Switch> getSwitches() { return switches; }
    public void setSwitches(List<Switch> switches) { this.switches = switches; }
    public List<Turnback> getTurnbacks() { return turnbacks; }
    public void setTurnbacks(List<Turnback> turnbacks) { this.turnbacks = turnbacks; }
    public List<TemporarySpeedRestriction> getTsrs() { return tsrs; }
    public void setTsrs(List<TemporarySpeedRestriction> tsrs) { this.tsrs = tsrs; }
    public List<StaticSpeedRestriction> getStaticSpeedRestrictions() { return staticSpeedRestrictions; }
    public void setStaticSpeedRestrictions(List<StaticSpeedRestriction> v) { this.staticSpeedRestrictions = v; }
    public List<Gradient> getGradients() { return gradients; }
    public void setGradients(List<Gradient> gradients) { this.gradients = gradients; }
    public List<Signal> getSignals() { return signals; }
    public void setSignals(List<Signal> signals) { this.signals = signals; }
    public List<Balise> getBalises() { return balises; }
    public void setBalises(List<Balise> balises) { this.balises = balises; }
    public List<AxleCounterSection> getAxleSections() { return axleSections; }
    public void setAxleSections(List<AxleCounterSection> axleSections) { this.axleSections = axleSections; }
    public List<LogicalSection> getLogicalSections() { return logicalSections; }
    public void setLogicalSections(List<LogicalSection> logicalSections) { this.logicalSections = logicalSections; }
    public List<Route> getRoutes() { return routes; }
    public void setRoutes(List<Route> routes) { this.routes = routes; }
    public List<OverlapSection> getOverlaps() { return overlaps; }
    public void setOverlaps(List<OverlapSection> overlaps) { this.overlaps = overlaps; }
    public List<PhysicalSection> getPhysicalSections() { return physicalSections; }
    public void setPhysicalSections(List<PhysicalSection> physicalSections) { this.physicalSections = physicalSections; }
    public double getTotalLengthM() { return totalLengthM; }
    public void setTotalLengthM(double totalLengthM) { this.totalLengthM = totalLengthM; }

    public Map<String, double[]> getSegmentMileage() { return segmentMileage; }

    // ============ 坐标基础设施：里程索引 ============

    /**
     * 依 Seg 邻接链（起点/终点 正向/侧向相邻 SegID）把每条 Seg 链出绝对里程 [startM, endM]。
     * 规则：
     *  - 以“不被任何 Seg 当作相邻点引用”的 Seg 作为线路根（root），startM=0；
     *  - 沿 forwardEndSegId 正向链累加 lengthCm/100；
     *  - 侧向分支（sideEndSegId）挂在当前 Seg 末端，并行延伸；
     *  - 未被根链覆盖的孤立 Seg 以当前游标为起点补链，保证每条 Seg 都有里程。
     * 内部契约单位：m（见 tech-design.md §1.1.1）。
     */
    public void buildMileageIndex() {
        segmentMileage.clear();
        Map<String, TrackSegment> byId = new HashMap<>();
        for (TrackSegment s : segments != null ? segments : new ArrayList<TrackSegment>()) {
            byId.put(s.getId(), s);
        }
        Set<Integer> referenced = new HashSet<>();
        for (TrackSegment s : byId.values()) {
            addRef(referenced, s.getForwardStartSegId());
            addRef(referenced, s.getSideStartSegId());
            addRef(referenced, s.getForwardEndSegId());
            addRef(referenced, s.getSideEndSegId());
        }
        List<TrackSegment> roots = new ArrayList<>();
        for (TrackSegment s : byId.values()) {
            if (!referenced.contains(toInt(s.getId()))) roots.add(s);
        }
        if (roots.isEmpty() && !byId.isEmpty()) {
            // The supplied CBI Seg graph is cyclic, so it has no natural root.
            // Never let HashMap iteration choose a different artificial origin
            // on another JVM; that would make calculated MA endpoints vary.
            roots.add(byId.values().stream()
                    .min(Comparator.comparingInt(s -> toInt(s.getId())))
                    .orElseThrow());
        }
        roots.sort(Comparator.comparingInt(s -> toInt(s.getId())));

        Set<String> visited = new HashSet<>();
        double[] cursor = {0.0};
        for (TrackSegment r : roots) {
            walkMileage(r, byId, visited, cursor[0], cursor);
        }
        // 孤立 Seg 补链
        List<TrackSegment> remaining = new ArrayList<>(byId.values());
        remaining.sort(Comparator.comparingInt(s -> toInt(s.getId())));
        for (TrackSegment s : remaining) {
            if (!visited.contains(s.getId())) {
                walkMileage(s, byId, visited, cursor[0], cursor);
            }
        }
    }

    private void walkMileage(TrackSegment seg, Map<String, TrackSegment> byId,
                             Set<String> visited, double startM, double[] cursor) {
        if (seg == null || visited.contains(seg.getId())) return;
        visited.add(seg.getId());
        double endM = startM + seg.getLengthCm() / 100.0;
        segmentMileage.put(seg.getId(), new double[]{startM, endM});
        if (endM > cursor[0]) cursor[0] = endM;
        int fe = seg.getForwardEndSegId();
        if (fe != NONE_ID) walkMileage(byId.get(String.valueOf(fe)), byId, visited, endM, cursor);
        int se = seg.getSideEndSegId();
        if (se != NONE_ID) walkMileage(byId.get(String.valueOf(se)), byId, visited, endM, cursor);
    }

    private static void addRef(Set<Integer> set, int id) {
        if (id != NONE_ID) set.add(id);
    }

    private static int toInt(String id) {
        try { return Integer.parseInt(id.trim()); }
        catch (Exception e) { return NONE_ID; }
    }

    /** Seg 起点绝对里程 m（未建索引返回 NaN） */
    public double segStartM(String segId) {
        double[] v = segmentMileage.get(segId);
        return v == null ? Double.NaN : v[0];
    }

    /** Seg 终点绝对里程 m（未建索引返回 NaN） */
    public double segEndM(String segId) {
        double[] v = segmentMileage.get(segId);
        return v == null ? Double.NaN : v[1];
    }

    /** (Seg, 偏移cm) → 绝对里程 m */
    public double locateMileage(String segId, double offsetCm) {
        return segStartM(segId) + offsetCm / 100.0;
    }

    public boolean hasMileage(String segId) {
        return segmentMileage.containsKey(segId);
    }
}
