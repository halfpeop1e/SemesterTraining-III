package com.bjtu.railtransit.signal.service;

import com.bjtu.railtransit.signal.model.*;
import com.bjtu.railtransit.signal.util.Units;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 线路静态数据接入层（真实数据 → 模型）。
 *
 * 数据源：教师提供的 CBTC 静态数据导出（线路数据(1)(1).xls，33 个 sheet），
 * 已通过脚本导出为 JSON（{@code src/main/resources/line-profile.json}），
 * 字段映射见 architecture.md §3.1。
 *
 * 本加载器只做两件事：
 *  1) JSON → {@link LineProfile}（Jackson，按字段名绑定）；
 *  2) enrich：把原始口径换算成内部契约口径（km/h + m），并补全派生量
 *     —— 站台中心里程、静态限速 km/h、车站中心里程、里程索引、道岔中心里程、线路总长。
 * 任何换算都必须走 {@link Units}，保证“真实口径 → 契约口径”单一入口。
 */
@Component
public class LineProfileLoader {

    private final ObjectMapper mapper = new ObjectMapper();

    public LineProfile loadFromJsonFile(String path) throws IOException {
        try (InputStream in = new FileInputStream(path)) {
            return load(in);
        }
    }

    public LineProfile loadFromClasspath(String resource) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IOException("classpath 资源未找到: " + resource);
            return load(in);
        }
    }

    public LineProfile load(InputStream in) throws IOException {
        LineProfile lp = mapper.readValue(in, LineProfile.class);
        enrich(lp);
        return lp;
    }

    /** 原始口径 → 内部契约口径（km/h + m），并补全派生量。 */
    private void enrich(LineProfile lp) {
        // 1) 站台：中心公里标 → 绝对里程 m
        for (Platform p : lp.getPlatforms()) {
            p.setCenterM(Units.parseChainage(p.getChainage()));
        }
        // 2) 静态限速：cm/s → km/h（同时校验 JSON 中预存的 km/h 一致）
        for (StaticSpeedRestriction s : lp.getStaticSpeedRestrictions()) {
            s.setSpeedLimitKmh(Units.cmpsToKmh(s.getSpeedLimitCmps()));
        }
        // 3) 车站：中心里程 = 其站台中心里程最小值
        Map<Integer, Double> pfCenter = new HashMap<>();
        for (Platform p : lp.getPlatforms()) pfCenter.put(p.getId(), p.getCenterM());
        for (Station st : lp.getStations()) {
            double min = Double.MAX_VALUE;
            for (Integer pid : st.getPlatformIds()) {
                Double c = pfCenter.get(pid);
                if (c != null) min = Math.min(min, c);
            }
            if (min != Double.MAX_VALUE) st.setPositionM(min);
        }
        // 4) 里程索引（(Seg,偏移cm) ↔ m）
        lp.buildMileageIndex();
        // 5) 道岔：中心里程 = 汇合 Seg 起点里程
        Map<String, double[]> mi = lp.getSegmentMileage();
        for (Switch sw : lp.getSwitches()) {
            double[] m = mi.get(String.valueOf(sw.getMergeSegId()));
            if (m != null) sw.setPositionM(m[0]);
        }
        // 6) 线路总长 = 全部 Seg 长度之和（物理总长，独立于里程索引覆盖度）
        double total = 0.0;
        for (TrackSegment s : lp.getSegments()) total += s.getLengthCm() / 100.0;
        lp.setTotalLengthM(total);
        // 7) 坡度：JSON 中 permille 以 0.1‰ 为整数单位存储（如 350 表示 35.0‰），
        //    换算为内部契约口径（真‰）。教师 CBTC 导出表字段口径，见 architecture.md §3.1。
        if (lp.getGradients() != null) {
            for (Gradient g : lp.getGradients()) {
                g.setPermille(Units.tenthsPermilleToPermille(g.getPermille()));
            }
        }
    }
}
