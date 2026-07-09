package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.domain.model.LineData;
import com.bjtu.railtransit.domain.model.LineProfile;
import com.bjtu.railtransit.domain.model.StationGeo;
import com.bjtu.railtransit.domain.model.TrackGeometry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 线路数据加载服务 - 加载所有30个Sheet的数据
 */
@Service
public class LineDataService {

    private LineProfile cachedLineProfile;
    private List<StationGeo> cachedStationGeoList;
    private TrackGeometry cachedTrackGeometry;
    private LineData cachedLineData;

    // Speed limit lookup index: sorted list keyed by km position
    private List<SpeedLimitZone> speedLimitIndex;
    // Gradient lookup index: sorted list keyed by km position
    private List<GradientZone> gradientIndex;
    // Tunnel lookup index
    private List<TunnelZone> tunnelIndex;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public LineProfile getLineProfile() {
        if (cachedLineProfile != null) return cachedLineProfile;
        try {
            ClassPathResource resource = new ClassPathResource("configs/line-profile.json");
            try (InputStream is = resource.getInputStream()) {
                cachedLineProfile = objectMapper.readValue(is, LineProfile.class);
            }
            return cachedLineProfile;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load line-profile.json", e);
        }
    }

    public List<StationGeo> getStationGeoList() {
        if (cachedStationGeoList != null) return cachedStationGeoList;
        try {
            ClassPathResource resource = new ClassPathResource("configs/line9-stations-geo.json");
            try (InputStream is = resource.getInputStream()) {
                JsonNode root = objectMapper.readTree(is);
                JsonNode stationsNode = root.get("stations");
                cachedStationGeoList = new ArrayList<>();
                if (stationsNode != null && stationsNode.isArray()) {
                    for (JsonNode node : stationsNode) {
                        StationGeo geo = objectMapper.treeToValue(node, StationGeo.class);
                        cachedStationGeoList.add(geo);
                    }
                }
            }
            return cachedStationGeoList;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load line9-stations-geo.json", e);
        }
    }

    public TrackGeometry getTrackGeometry() {
        if (cachedTrackGeometry != null) return cachedTrackGeometry;
        try {
            ClassPathResource resource = new ClassPathResource("configs/track-geometry.json");
            try (InputStream is = resource.getInputStream()) {
                cachedTrackGeometry = objectMapper.readValue(is, TrackGeometry.class);
            }
            return cachedTrackGeometry;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load track-geometry.json", e);
        }
    }

    /**
     * 加载所有线路数据（信号系统、道岔、坡度、限速等）
     */
    public LineData getLineData() {
        if (cachedLineData != null) return cachedLineData;
        cachedLineData = new LineData();
        try {
            cachedLineData.setLongShortChains(loadList("configs/long-short-chain.json",
                new TypeReference<List<LineData.LongShortChain>>() {}));
            cachedLineData.setTrackPoints(loadList("configs/points.json",
                new TypeReference<List<LineData.TrackPoint>>() {}));
            cachedLineData.setAxleCounterSections(loadList("configs/axle-counter-sections.json",
                new TypeReference<List<LineData.AxleCounterSection>>() {}));
            cachedLineData.setPhysicalSections(loadList("configs/physical-sections.json",
                new TypeReference<List<LineData.PhysicalSection>>() {}));
            cachedLineData.setLogicalSections(loadList("configs/logical-sections.json",
                new TypeReference<List<LineData.LogicalSection>>() {}));
            cachedLineData.setAxleCounters(loadList("configs/axle-counters.json",
                new TypeReference<List<LineData.AxleCounter>>() {}));
            cachedLineData.setSignals(loadList("configs/signals.json",
                new TypeReference<List<LineData.Signal>>() {}));
            cachedLineData.setBalises(loadList("configs/balises.json",
                new TypeReference<List<LineData.Balise>>() {}));
            cachedLineData.setRoutes(loadList("configs/routes.json",
                new TypeReference<List<LineData.Route>>() {}));
            cachedLineData.setProtectionSections(loadList("configs/protection-sections.json",
                new TypeReference<List<LineData.ProtectionSection>>() {}));
            cachedLineData.setPointApproachSections(loadList("configs/point-approach-sections.json",
                new TypeReference<List<LineData.ApproachSection>>() {}));
            cachedLineData.setCbtcApproachSections(loadList("configs/cbtc-approach-sections.json",
                new TypeReference<List<LineData.ApproachSection>>() {}));
            cachedLineData.setPointTriggerSections(loadList("configs/point-trigger-sections.json",
                new TypeReference<List<LineData.TriggerSection>>() {}));
            cachedLineData.setCbtcTriggerSections(loadList("configs/cbtc-trigger-sections.json",
                new TypeReference<List<LineData.TriggerSection>>() {}));
            cachedLineData.setSwitches(loadList("configs/switches.json",
                new TypeReference<List<LineData.Switch>>() {}));
            cachedLineData.setBumpers(loadList("configs/bumpers.json",
                new TypeReference<List<LineData.Bumper>>() {}));
            cachedLineData.setSpksSwitches(loadList("configs/spks-switches.json",
                new TypeReference<List<LineData.SpksSwitch>>() {}));
            cachedLineData.setPlatformDoors(loadList("configs/platform-doors.json",
                new TypeReference<List<LineData.PlatformDoor>>() {}));
            cachedLineData.setEmergencyButtons(loadList("configs/emergency-buttons.json",
                new TypeReference<List<LineData.EmergencyButton>>() {}));
            cachedLineData.setGradients(loadList("configs/gradients.json",
                new TypeReference<List<LineData.Gradient>>() {}));
            cachedLineData.setStaticSpeedLimits(loadList("configs/static-speed-limits.json",
                new TypeReference<List<LineData.StaticSpeedLimit>>() {}));
            cachedLineData.setTunnels(loadList("configs/tunnels.json",
                new TypeReference<List<LineData.Tunnel>>() {}));
            cachedLineData.setFloodGates(loadList("configs/flood-gates.json",
                new TypeReference<List<LineData.FloodGate>>() {}));
            cachedLineData.setDepotDoors(loadList("configs/depot-doors.json",
                new TypeReference<List<LineData.DepotDoor>>() {}));
            cachedLineData.setCollisionZones(loadList("configs/collision-zones.json",
                new TypeReference<List<LineData.CollisionZone>>() {}));
            cachedLineData.setUniformSpeedLimits(loadList("configs/line-uniform-speed-limit.json",
                new TypeReference<List<LineData.UniformSpeedLimit>>() {}));
            cachedLineData.setUniformGradients(loadList("configs/line-uniform-gradient.json",
                new TypeReference<List<LineData.UniformGradient>>() {}));
            cachedLineData.setZoneProperties(loadList("configs/zone-properties.json",
                new TypeReference<List<LineData.ZoneProperty>>() {}));
            cachedLineData.setDeviceIdMappings(loadList("configs/device-id-mapping.json",
                new TypeReference<List<LineData.DeviceIdMapping>>() {}));
            cachedLineData.setVirtualPoints(loadList("configs/virtual-points.json",
                new TypeReference<List<LineData.VirtualPoint>>() {}));

            // 构建快速查询索引
            buildSpeedLimitIndex();
            buildGradientIndex();
            buildTunnelIndex();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load line data JSON files", e);
        }
        return cachedLineData;
    }

    private <T> List<T> loadList(String path, TypeReference<List<T>> typeRef) throws Exception {
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream is = resource.getInputStream()) {
            return objectMapper.readValue(is, typeRef);
        }
    }

    // ---- Speed Limit Index ----

    public static class SpeedLimitZone {
        public double startKm;
        public double endKm;
        public int limitKmh;

        public SpeedLimitZone(double s, double e, int l) {
            this.startKm = s;
            this.endKm = e;
            this.limitKmh = l;
        }
    }

    private void buildSpeedLimitIndex() {
        speedLimitIndex = new ArrayList<>();
        LineData ld = getLineData();
        TrackGeometry tg = getTrackGeometry();
        if (ld.getStaticSpeedLimits() == null || tg.getSegments() == null) return;

        // Build segId -> seg lookup
        Map<Integer, TrackGeometry.TrackSegment> segMap = tg.getSegments().stream()
            .collect(Collectors.toMap(TrackGeometry.TrackSegment::getId, s -> s));

        for (LineData.StaticSpeedLimit sl : ld.getStaticSpeedLimits()) {
            try {
                TrackGeometry.TrackSegment seg = segMap.get(sl.get限速区段所处seg编号());
                if (seg == null) continue;
                // Approximate km by seg start + offset
                // Using km from startPointId waypoint
                double segStartKm = getSegStartKm(seg, tg.getTrackWaypoints());
                double startKm = segStartKm + sl.get起点所处seg偏移量() / 100000.0; // cm->km
                double endKm = segStartKm + sl.get终点所处seg偏移量() / 100000.0;
                if (startKm < 0) startKm = 0;
                if (endKm > 20) endKm = 20;
                speedLimitIndex.add(new SpeedLimitZone(startKm, endKm, sl.get限速值()));
            } catch (Exception ignored) {}
        }
        // Add default limit
        speedLimitIndex.add(new SpeedLimitZone(0, 20, 80));
        // Sort by startKm
        speedLimitIndex.sort(Comparator.comparingDouble(z -> z.startKm));
    }

    private double getSegStartKm(TrackGeometry.TrackSegment seg, List<TrackGeometry.TrackWaypoint> wps) {
        TrackGeometry.TrackWaypoint wp = wps.stream()
            .filter(w -> w.getName() != null)
            .findFirst().orElse(null);
        return wp != null ? wp.getKm() : 0;
    }

    /**
     * 根据位置获取当前限速(km/h)
     */
    public int getSpeedLimitAtKm(double km) {
        if (speedLimitIndex == null) getLineData();
        if (speedLimitIndex == null) return 80;
        for (SpeedLimitZone zone : speedLimitIndex) {
            if (km >= zone.startKm && km <= zone.endKm) {
                return zone.limitKmh;
            }
        }
        return 80; // default
    }

    // ---- Gradient Index ----

    public static class GradientZone {
        public double startKm;
        public double endKm;
        public int gradientPermille; // 千分之几

        public GradientZone(double s, double e, int g) {
            this.startKm = s;
            this.endKm = e;
            this.gradientPermille = g;
        }
    }

    private void buildGradientIndex() {
        gradientIndex = new ArrayList<>();
        LineData ld = getLineData();
        TrackGeometry tg = getTrackGeometry();
        if (ld.getGradients() == null || tg.getSegments() == null) return;

        Map<Integer, TrackGeometry.TrackSegment> segMap = tg.getSegments().stream()
            .collect(Collectors.toMap(TrackGeometry.TrackSegment::getId, s -> s));

        for (LineData.Gradient g : ld.getGradients()) {
            try {
                TrackGeometry.TrackSegment seg = segMap.get(g.get坡度起点所处seg编号());
                if (seg == null) continue;
                double segStartKm = getSegStartKm(seg, tg.getTrackWaypoints());
                double startKm = segStartKm + g.get坡度起点所处seg偏移量() / 100000.0;
                double endKm = segStartKm + g.get坡度终点所处seg偏移量() / 100000.0;
                if (startKm < 0) startKm = 0;
                if (endKm > 20) endKm = 20;
                gradientIndex.add(new GradientZone(startKm, endKm, g.get坡度值()));
            } catch (Exception ignored) {}
        }
        gradientIndex.add(new GradientZone(0, 20, 0)); // default flat
        gradientIndex.sort(Comparator.comparingDouble(z -> z.startKm));
    }

    /**
     * 根据位置获取当前坡度（千分比）
     */
    public int getGradientAtKm(double km) {
        if (gradientIndex == null) getLineData();
        if (gradientIndex == null) return 0;
        for (GradientZone zone : gradientIndex) {
            if (km >= zone.startKm && km <= zone.endKm) {
                return zone.gradientPermille;
            }
        }
        return 0;
    }

    // ---- Tunnel Index ----

    public static class TunnelZone {
        public double startKm;
        public double endKm;

        public TunnelZone(double s, double e) {
            this.startKm = s;
            this.endKm = e;
        }
    }

    private void buildTunnelIndex() {
        tunnelIndex = new ArrayList<>();
        LineData ld = getLineData();
        TrackGeometry tg = getTrackGeometry();
        if (ld.getTunnels() == null || tg.getSegments() == null) return;

        Map<Integer, TrackGeometry.TrackSegment> segMap = tg.getSegments().stream()
            .collect(Collectors.toMap(TrackGeometry.TrackSegment::getId, s -> s));

        for (LineData.Tunnel t : ld.getTunnels()) {
            try {
                TrackGeometry.TrackSegment seg = segMap.get(t.get隧道所处Seg编号());
                if (seg == null) continue;
                double segStartKm = getSegStartKm(seg, tg.getTrackWaypoints());
                double startKm = segStartKm + t.get起点所处Seg偏移量() / 100000.0;
                double endKm = segStartKm + t.get终点所处Seg偏移量() / 100000.0;
                if (startKm < 0) startKm = 0;
                if (endKm > 20) endKm = 20;
                tunnelIndex.add(new TunnelZone(startKm, endKm));
            } catch (Exception ignored) {}
        }
        tunnelIndex.sort(Comparator.comparingDouble(z -> z.startKm));
    }

    /** 判断指定公里标是否在隧道内 */
    public boolean isTunnelAtKm(double km) {
        if (tunnelIndex == null) getLineData();
        if (tunnelIndex == null) return false;
        for (TunnelZone zone : tunnelIndex) {
            if (km >= zone.startKm && km <= zone.endKm) {
                return true;
            }
        }
        return false;
    }
}
