package com.bjtu.railtransit.vehicle.service;

import com.bjtu.railtransit.vehicle.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;

/**
 * 从 configs/ 加载全线真实线路数据（Seg 拓扑、坡度、限速、隧道），
 * 并按选定起止站构造相对坐标的 {@link LineProfile}。
 */
@Component
public class LineProfileJsonLoader {

    /** Seg 拓扑表：segId → cumulativeMeters（从 Seg=1 起始累积里程） */
    private static final String SEGMENTS_PATH = "/configs/segments.json";
    /** 坡度表 */
    private static final String GRADIENTS_PATH = "/configs/gradients.json";
    /** 静态限速表 */
    private static final String SPEED_LIMITS_PATH = "/configs/static-speed-limits.json";
    /** 隧道表 */
    private static final String TUNNELS_PATH = "/configs/tunnels.json";
    /** 站点表 */
    private static final String LINE_PROFILE_PATH = "/configs/line-profile.json";

    /** 默认区间限速 m/s（无变限速数据时使用） */
    static final double DEFAULT_SPEED_LIMIT_MPS = 70.0 / 3.6; // 70 km/h

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 延迟加载的缓存
    private volatile List<StationEntry> cachedStations;
    private volatile Map<Integer, Double> cachedSegKm;

    public static class StationEntry {
        public final int id;
        public final String name;
        /** 公里标，单位 km */
        public final double km;

        StationEntry(int id, String name, double km) {
            this.id = id; this.name = name; this.km = km;
        }
    }

    // ── Station loading ──

    public List<StationEntry> listStations() {
        if (cachedStations == null) {
            synchronized (this) {
                if (cachedStations == null) {
                    cachedStations = loadStations();
                }
            }
        }
        return cachedStations;
    }

    private List<StationEntry> loadStations() {
        JsonNode root = loadJson(LINE_PROFILE_PATH);
        JsonNode arr = root.path("stations");
        if (!arr.isArray()) throw new IllegalStateException(LINE_PROFILE_PATH + " 缺少 stations 数组");
        List<StationEntry> result = new ArrayList<>();
        for (JsonNode s : arr) {
            int id = s.path("id").asInt(-1);
            String name = s.path("name").asText("");
            double km = s.path("km").asDouble(Double.NaN);
            if (id < 0 || name.isEmpty() || Double.isNaN(km))
                throw new IllegalStateException("站点条目字段缺失: " + s);
            result.add(new StationEntry(id, name, km));
        }
        result.sort(Comparator.comparingInt(a -> a.id));
        return result;
    }

    // ── Seg→km 映射 ──

    /** segId (索引编号) → 起始累积公里标 m */
    private Map<Integer, Double> getSegKm() {
        if (cachedSegKm == null) {
            synchronized (this) {
                if (cachedSegKm == null) {
                    cachedSegKm = buildSegKm();
                }
            }
        }
        return cachedSegKm;
    }

    private Map<Integer, Double> buildSegKm() {
        JsonNode arr = loadJson(SEGMENTS_PATH);
        if (!arr.isArray()) throw new IllegalStateException(SEGMENTS_PATH + " 不是数组");
        // 先按索引编号排序，再累加长度(cm → m)
        List<SegEntry> segs = new ArrayList<>();
        for (JsonNode s : arr) {
            int id = s.path("索引编号").asInt(-1);
            double lenCm = s.path("长度（cm)").asDouble(-1);
            if (id <= 0 || lenCm < 0) throw new IllegalStateException("Seg 条目字段缺失: " + s);
            segs.add(new SegEntry(id, lenCm));
        }
        segs.sort(Comparator.comparingInt(s -> s.id));

        double cum = 0.0;
        Map<Integer, Double> map = new LinkedHashMap<>();
        for (SegEntry s : segs) {
            map.put(s.id, cum);
            cum += s.lenCm / 100.0;
        }
        return map;
    }

    /** 将 (segId + 偏移量 cm) 转换为累积里程 m */
    private double segToKm(int segId, int offsetCm) {
        Map<Integer, Double> km = getSegKm();
        Double base = km.get(segId);
        if (base == null) {
            // segId 不存在：用最近一个已知值
            int fallback = segId;
            while (fallback > 1 && !km.containsKey(fallback)) fallback--;
            base = km.getOrDefault(fallback, 0.0);
        }
        return base + offsetCm / 100.0;
    }

    // ── LineProfile 构造 ──

    public LineProfile buildLineProfile(int fromStationId, int toStationId) {
        if (toStationId <= fromStationId)
            throw new IllegalArgumentException("toStationId 必须 > fromStationId");

        List<StationEntry> stations = listStations();
        StationEntry from = findStation(stations, fromStationId);
        StationEntry to = findStation(stations, toStationId);

        double startAbsM = from.km * 1000.0;
        double targetAbsM = to.km * 1000.0;
        double runDistanceM = targetAbsM - startAbsM;

        if (runDistanceM <= 0)
            throw new IllegalArgumentException("起站 km=" + from.km + " 不小于终站 km=" + to.km);

        double defaultLimit = DEFAULT_SPEED_LIMIT_MPS;
        List<GradeSegment> grades = loadRelativeGradients(startAbsM, runDistanceM);
        List<SpeedLimitSegment> limits = loadRelativeSpeedLimits(startAbsM, runDistanceM, defaultLimit);
        List<TunnelSegment> tunnels = loadRelativeTunnels(startAbsM, runDistanceM);
        List<CurveSegment> curves = Collections.emptyList(); // 曲线数据从 track-geometry.json 暂不加载

        return new LineProfile(0.0, runDistanceM, defaultLimit,
                grades, limits, tunnels, curves);
    }

    public StationEntry[] findStationPair(int fromStationId, int toStationId) {
        List<StationEntry> stations = listStations();
        return new StationEntry[]{
                findStation(stations, fromStationId),
                findStation(stations, toStationId)
        };
    }

    // ── 坡度 ──

    private List<GradeSegment> loadRelativeGradients(double startAbsM, double runDistanceMj) {
        List<GradeSegment> res = new ArrayList<>();
        JsonNode arr = loadJson(GRADIENTS_PATH);
        if (!arr.isArray()) return res;

        for (JsonNode g : arr) {
            int segId = g.path("坡度起点所处seg编号").asInt(-1);
            int startOff = g.path("坡度起点所处seg偏移量").asInt(0);
            int endSegId = g.path("坡度终点所处seg编号").asInt(-1);
            int endOff = g.path("坡度终点所处seg偏移量").asInt(0);
            int permille = g.path("坡度值").asInt(0);
            if (segId < 0 || endSegId < 0) continue;

            double absStart = segToKm(segId, startOff);
            double absEnd = segToKm(endSegId, endOff);
            double relStart = absStart - startAbsM;
            double relEnd = absEnd - startAbsM;
            // 裁剪到区间内
            relStart = Math.max(relStart, 0);
            relEnd = Math.min(relEnd, runDistanceMj);
            if (relEnd <= relStart) continue;

            // 坡度值 permille → 弧度 atan(i/1000)，用于 sin(θ) ≈ i/1000 (小角度)
            double gradeRad = Math.atan(permille / 1000.0);
            res.add(new GradeSegment(relStart, relEnd, gradeRad));
        }
        return res;
    }

    // ── 限速 ──

    private List<SpeedLimitSegment> loadRelativeSpeedLimits(double startAbsM, double runDistanceM,
                                                             double defaultMps) {
        List<SpeedLimitSegment> res = new ArrayList<>();
        JsonNode arr = loadJson(SPEED_LIMITS_PATH);
        if (!arr.isArray()) return res;

        for (JsonNode l : arr) {
            int segId = l.path("限速区段所处seg编号").asInt(-1);
            int startOff = l.path("起点所处seg偏移量").asInt(0);
            int endOff = l.path("终点所处seg偏移量").asInt(0);
            int limitRaw = l.path("限速值").asInt(-1);
            if (segId < 0 || limitRaw <= 0) continue;

            double absStart = segToKm(segId, startOff);
            double absEnd = segToKm(segId, endOff);
            double relStart = absStart - startAbsM;
            double relEnd = absEnd - startAbsM;
            relStart = Math.max(relStart, 0);
            relEnd = Math.min(relEnd, runDistanceM);
            if (relEnd <= relStart) continue;

            double limitMps = limitRaw / 100.0; // cm/s → m/s
            res.add(new SpeedLimitSegment(relStart, relEnd, limitMps));
        }
        return res;
    }

    // ── 隧道 ──

    private List<TunnelSegment> loadRelativeTunnels(double startAbsM, double runDistanceM) {
        List<TunnelSegment> res = new ArrayList<>();
        JsonNode arr = loadJson(TUNNELS_PATH);
        if (!arr.isArray()) return res;

        for (JsonNode t : arr) {
            int segId = t.path("隧道所处Seg编号").asInt(-1);
            int startOff = t.path("起点所处Seg偏移量").asInt(0);
            int endOff = t.path("终点所处Seg偏移量").asInt(0);
            if (segId < 0) continue;

            double absStart = segToKm(segId, startOff);
            double absEnd = segToKm(segId, endOff);
            double relStart = absStart - startAbsM;
            double relEnd = absEnd - startAbsM;
            relStart = Math.max(relStart, 0);
            relEnd = Math.min(relEnd, runDistanceM);
            if (relEnd <= relStart) continue;

            res.add(new TunnelSegment(relStart, relEnd));
        }
        return res;
    }

    // ── helpers ──

    private StationEntry findStation(List<StationEntry> stations, int stationId) {
        return stations.stream()
                .filter(s -> s.id == stationId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("站点 id=" + stationId + " 不存在"));
    }

    private JsonNode loadJson(String classpath) {
        try (InputStream is = getClass().getResourceAsStream(classpath)) {
            if (is == null) throw new IllegalStateException("找不到 " + classpath);
            return objectMapper.readTree(is);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取 JSON 失败: " + classpath, e);
        }
    }

    private static class SegEntry {
        final int id;
        final double lenCm;
        SegEntry(int id, double lenCm) { this.id = id; this.lenCm = lenCm; }
    }
}
