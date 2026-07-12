package com.bjtu.railtransit.vehicle.service;

import com.bjtu.railtransit.vehicle.model.LineProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 从 configs/line-profile.json 读取北京地铁9号线站点公里标数据，
 * 并按选定起止站构造 {@link LineProfile}（相对坐标）。
 *
 * <p><b>读取字段：</b> stations[].id / name / km</p>
 *
 * <p><b>未读取字段（JSON 中不存在）：</b>
 * <ul>
 *   <li>限速（speed_limit_mps）：JSON 中无此字段，本轮继续使用
 *       {@link DemoScenarioProvider#SPEED_LIMIT} 默认值 20.0 m/s，标注为假设值。</li>
 *   <li>坡度（gradient_permille）：JSON 中无此字段，本轮使用空坡度段列表（全程平坡），
 *       标注为假设值。若需要真实坡度，需待成员二或配置团队补充字段后再接入。</li>
 * </ul>
 * </p>
 *
 * <p><b>约束：</b>
 * <ul>
 *   <li>不修改 configs/line-profile.json。</li>
 *   <li>不伪造限速或坡度数据——当前不存在于 JSON 中的字段继续使用默认假设值，
 *       且在注释和进度账本中明确标注为"假设值"。</li>
 * </ul>
 * </p>
 */
@Component
public class LineProfileJsonLoader {

    /**
     * 区间限速假设值，单位 m/s。
     * 当前 configs/line-profile.json 中没有 speed_limit_mps 字段，
     * 复用 DemoScenarioProvider 的默认限速，明确标注为假设值。
     */
    public static final double ASSUMED_SPEED_LIMIT_MPS = 20.0;

    /** line-profile.json 在 classpath 中的路径（相对于 resources/）。 */
    private static final String JSON_PATH = "/configs/line-profile.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 站点原始数据，用于 id/name/km 查询。 */
    public static class StationEntry {
        public final int id;
        public final String name;
        /** 公里标，单位 km，来自 JSON line-profile.stations[].km。 */
        public final double km;

        StationEntry(int id, String name, double km) {
            this.id = id;
            this.name = name;
            this.km = km;
        }
    }

    /**
     * 解析 configs/line-profile.json，返回全部站点列表（按 id 升序）。
     *
     * @throws RuntimeException 若文件不可读或格式不符合预期
     */
    public List<StationEntry> listStations() {
        JsonNode root = loadJson();
        JsonNode stationsNode = root.path("stations");
        if (!stationsNode.isArray()) {
            throw new IllegalStateException("line-profile.json 缺少 stations 数组");
        }
        List<StationEntry> result = new ArrayList<>();
        for (JsonNode s : stationsNode) {
            int id = s.path("id").asInt(-1);
            String name = s.path("name").asText("");
            double km = s.path("km").asDouble(Double.NaN);
            if (id < 0 || name.isEmpty() || Double.isNaN(km)) {
                throw new IllegalStateException(
                        "line-profile.json stations 中存在字段缺失的条目，无法解析: " + s);
            }
            result.add(new StationEntry(id, name, km));
        }
        result.sort((a, b) -> Integer.compare(a.id, b.id));
        return result;
    }

    /**
     * 按起止站 id 构造 {@link LineProfile}（相对坐标，起点 = 0，终点 = 区间里程）。
     *
     * <p>坐标换算：
     * <pre>
     *   startAbsM     = fromStation.km * 1000
     *   targetAbsM    = toStation.km * 1000
     *   runDistanceM  = targetAbsM - startAbsM
     *   startPosition = 0.0          （相对）
     *   targetStop    = runDistanceM  （相对）
     * </pre>
     * </p>
     *
     * <p><b>假设值说明：</b>
     * <ul>
     *   <li>speedLimit = 20.0 m/s（JSON 无限速字段，假设值）</li>
     *   <li>gradeSegments = emptyList（JSON 无坡度字段，全程平坡假设值）</li>
     * </ul>
     * </p>
     *
     * @param fromStationId 起始站 id（必须存在于 JSON）
     * @param toStationId   终止站 id（必须存在于 JSON，且 > fromStationId）
     * @return 相对坐标 LineProfile
     * @throws IllegalArgumentException 站点不存在或 toStationId <= fromStationId
     */
    public LineProfile buildLineProfile(int fromStationId, int toStationId) {
        if (toStationId <= fromStationId) {
            throw new IllegalArgumentException(
                    "toStationId(" + toStationId + ") 必须大于 fromStationId(" + fromStationId
                            + ")。本轮只支持正向运行（郭公庄→国家图书馆方向），不支持反向。");
        }

        List<StationEntry> stations = listStations();
        StationEntry fromStation = findStation(stations, fromStationId);
        StationEntry toStation = findStation(stations, toStationId);

        double startAbsM = fromStation.km * 1000.0;
        double targetAbsM = toStation.km * 1000.0;
        double runDistanceM = targetAbsM - startAbsM;

        if (runDistanceM <= 0) {
            throw new IllegalArgumentException(
                    "起站 km=" + fromStation.km + " 不小于终站 km=" + toStation.km
                            + "，无法构造正向区间。");
        }

        // speedLimit: 假设值 20.0 m/s（JSON 中无 speed_limit_mps 字段）
        // gradeSegments: 空列表，全程平坡假设（JSON 中无坡度字段）
        return new LineProfile(
                0.0,
                runDistanceM,
                ASSUMED_SPEED_LIMIT_MPS,
                Collections.emptyList()
        );
    }

    /**
     * 按起止站 id 返回两端站名，供 summary 填写绝对里程和站名。
     *
     * @return 二元数组 [fromStation, toStation]
     */
    public StationEntry[] findStationPair(int fromStationId, int toStationId) {
        List<StationEntry> stations = listStations();
        StationEntry from = findStation(stations, fromStationId);
        StationEntry to = findStation(stations, toStationId);
        return new StationEntry[]{from, to};
    }

    private StationEntry findStation(List<StationEntry> stations, int stationId) {
        return stations.stream()
                .filter(s -> s.id == stationId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "站点 id=" + stationId + " 不存在于 configs/line-profile.json。"
                                + "有效 id 范围为 1~" + stations.size()));
    }

    private JsonNode loadJson() {
        try (InputStream is = getClass().getResourceAsStream(JSON_PATH)) {
            if (is == null) {
                throw new IllegalStateException(
                        "找不到 " + JSON_PATH + "，请确认 configs/line-profile.json 已放置在"
                                + " backend/src/main/resources/configs/ 目录或 classpath 中");
            }
            return objectMapper.readTree(is);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取 line-profile.json 失败: " + e.getMessage(), e);
        }
    }
}
