package com.bjtu.railtransit.signal;

import com.bjtu.railtransit.signal.model.*;
import com.bjtu.railtransit.signal.service.LineProfileLoader;
import com.bjtu.railtransit.signal.util.Units;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * TASK-2 自检（沙箱可独立运行 + 供 JUnit 复用）。
 * 覆盖：UT-DATA-01（单位换算）、UT-LOC-01（里程索引导航）、verification.md §4.1（真实数据标定）。
 * 运行：java com.bjtu.railtransit.signal.Task2Verification
 */
public class Task2Verification {

    static final String REAL_JSON =
            "D:/Code/aaaSemesterTraining-III/SemesterTraining-III/backend/src/main/resources/line-profile.json";

    static int passed = 0;
    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAIL: " + msg);
        passed++;
        System.out.println("  [PASS] " + msg);
    }
    static void close(double a, double b, double eps, String msg) {
        check(Math.abs(a - b) <= eps, msg + " (got=" + a + ", want=" + b + ", eps=" + eps + ")");
    }

    // ---------- UT-DATA-01：单位换算 ----------
    static void checkUnits() {
        System.out.println("UT-DATA-01 单位换算：");
        close(Math.round(Units.cmpsToKmh(1333)), 48, 0.5, "1333 cm/s -> 48 km/h");
        close(Math.round(Units.cmpsToKmh(2777)), 100, 0.5, "2777 cm/s -> 100 km/h");
        close(Math.round(Units.cmpsToKmh(972)), 35, 0.5, "972 cm/s -> 35 km/h");
        close(Units.cmToM(75800), 758.0, 1e-9, "75800 cm -> 758 m");
        close(Units.cmpsToKmh(Units.kmhToCmps(100)), 100.0, 1e-9, "km/h<->cm/s 互逆");
        close(Units.parseChainage("K0+372.000"), 372.0, 1e-9, "K0+372.000 -> 372.0 m");
        close(Units.parseChainage("K2+507.610"), 2507.61, 1e-9, "K2+507.610 -> 2507.61 m");
    }

    // ---------- UT-LOC-01：里程索引导航（确定性合成场景） ----------
    static void checkMileageSynthetic() {
        System.out.println("UT-LOC-01 里程索引导航（合成）：");
        LineProfile lp = new LineProfile();
        TrackSegment s = new TrackSegment();
        s.setId("1");
        s.setLengthCm(75800);     // 758 m
        s.setForwardStartSegId(LineProfile.NONE_ID);
        s.setForwardEndSegId(LineProfile.NONE_ID);
        s.setSideStartSegId(LineProfile.NONE_ID);
        s.setSideEndSegId(LineProfile.NONE_ID);
        List<TrackSegment> segs = new ArrayList<>();
        segs.add(s);
        lp.setSegments(segs);
        lp.buildMileageIndex();
        close(lp.segStartM("1"), 0.0, 1e-9, "Seg1 起点里程 = 0 m");
        close(lp.segEndM("1"), 758.0, 1e-9, "Seg1 终点里程 = 758 m");
        close(lp.locateMileage("1", 0), 0.0, 1e-9, "Seg1 偏移 0 -> 0 m");
        close(lp.locateMileage("1", 75800), 758.0, 1e-9, "Seg1 偏移 75800cm -> 758 m");
    }

    // ---------- verification.md §4.1：真实数据标定 ----------
    static LineProfile loadFromClasspath() throws Exception {
        return new LineProfileLoader().loadFromClasspath("line-profile.json");
    }
    static LineProfile loadFromFile(String path) throws Exception {
        return new LineProfileLoader().loadFromJsonFile(path);
    }

    static void checkRealData(LineProfile lp) {
        System.out.println("verification.md §4.1 真实数据标定：");
        // Seg1
        TrackSegment seg1 = lp.getSegments().stream()
                .filter(s -> "1".equals(s.getId())).findFirst().orElse(null);
        check(seg1 != null, "Seg1 存在");
        close(seg1.getLengthCm(), 75800.0, 1e-9, "Seg1 长度 = 75800 cm");
        check(seg1.getForwardStartSegId() == 231, "Seg1 起点正向相邻 = 231");
        check(seg1.getForwardEndSegId() == 2, "Seg1 终点正向相邻 = 2");
        check(seg1.getZcZoneId() == 1 && seg1.getAtsZoneId() == 1 && seg1.getCiZoneId() == 1, "Seg1 区域 = ZC/ATS/CI=1");

        // Switch1
        Switch sw1 = lp.getSwitches().stream()
                .filter(s -> "1".equals(s.getId())).findFirst().orElse(null);
        check(sw1 != null, "道岔1 存在");
        check(sw1.getNormalSegId() == 5, "道岔1 定位SegID = 5");
        check(sw1.getReverseSegId() == 4, "道岔1 反位SegID = 4");
        check(sw1.getMergeSegId() == 6, "道岔1 汇合SegID = 6");
        close(sw1.getDivergingSpeedLimitKmh(), 972 * 0.036, 1e-6, "道岔1 侧向限速 972cm/s -> 34.992 km/h");
        check(sw1.getLinkedSwitchId() == LineProfile.NONE_ID, "道岔1 无联动道岔 (65535)");

        // Signal Z5
        Signal z5 = lp.getSignals().stream()
                .filter(s -> "Z5".equals(s.getName())).findFirst().orElse(null);
        check(z5 != null, "信号机 Z5 存在");
        check(z5.getId() == 1, "Z5 索引编号 = 1");
        check(z5.getType() == 1, "Z5 类型 = 1");
        check(z5.getAttr() == 0x000C, "Z5 属性 = 0x000C (12)");
        check(z5.getSegId() == 1, "Z5 所处Seg = 1");
        close(z5.getOffsetCm(), 0.0, 1e-9, "Z5 偏移 = 0 cm");
        check(z5.getProtectDir() == 0xAA, "Z5 防护方向 = 0xaa (170)");

        // Platform1
        Platform pf1 = lp.getPlatforms().stream()
                .filter(p -> p.getId() == 1).findFirst().orElse(null);
        check(pf1 != null, "站台1 存在");
        check("K0+372.000".equals(pf1.getChainage()), "Track 0 站台1中心公里标 = K0+372.000");
        close(pf1.getCenterM(), 372.0, 1e-9, "Track 0 站台1中心里程 = 372.0 m");
        check(pf1.getSegId() == 13, "站台1 关联Seg = 13");
        check(pf1.getDir() == 0x55, "站台1 方向 = 0x55 (85)");

        // Route XQ1-Z5
        Route rt = lp.getRoutes().stream()
                .filter(r -> "XQ1-Z5".equals(r.getName())).findFirst().orElse(null);
        check(rt != null, "进路 XQ1-Z5 存在");
        check(rt.getStartSignalId() == 3, "XQ1-Z5 始端信号机 = 3 (XQ1)");
        check(rt.getEndSignalId() == 1, "XQ1-Z5 终端信号机 = 1 (Z5)");
        check(rt.getAxleSectionIds().equals(java.util.Collections.singletonList(1)), "XQ1-Z5 计轴区段 = [1]");

        // 里程索引已建 & 总长合理
        check(lp.hasMileage("1"), "Seg1 已纳入里程索引");
        close(lp.getTotalLengthM(), 47503.5, 1.0, "线路总长 ~ 47503.5 m (47.5 km)");
        System.out.println("  里程索引覆盖 Seg 数 = " + lp.getSegmentMileage().size()
                + " / 总 Seg 数 = " + lp.getSegments().size());
    }

    /** 验证坡度：JSON 以 0.1‰ 为整数单位存储（350=35.0‰），加载器换算为真‰ */
    static void checkPermillePassthrough(String jsonPath) throws Exception {
        System.out.println("verification.md §4.1 坡度 0.1‰→‰ 换算校准：");
        ObjectMapper om = new ObjectMapper();
        JsonNode root = om.readTree(new java.io.File(jsonPath));
        JsonNode grads = root.get("gradients");
        LineProfile lp = loadFromFile(jsonPath);
        int n = grads.size();
        check(n == lp.getGradients().size(), "坡度条数一致 = " + n);
        int ok = 0;
        for (int i = 0; i < n; i++) {
            double raw = grads.get(i).get("permille").asDouble();
            double loaded = lp.getGradients().get(i).getPermille();
            if (Math.abs(raw / 10.0 - loaded) < 1e-9) ok++;
        }
        check(ok == n, "全部坡度由 0.1‰ 换算为‰ (" + ok + "/" + n + ")");
        // 反例：换算后应为真‰（如 350→35.0），既不能被当成 cm/s 换算，也不应 >100
        double sample = lp.getGradients().get(0).getPermille();
        check(sample < 100 && sample > -100,
                "permille 换算后为真‰ (样本=" + sample + "‰，应∈(-100,100))");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("===== TASK-2 自检开始 =====");
        LineProfile lp = loadFromFile(REAL_JSON);
        checkUnits();
        checkMileageSynthetic();
        checkRealData(lp);
        checkPermillePassthrough(REAL_JSON);
        System.out.println("===== TASK-2 自检通过：断言数 = " + passed + " =====");
    }
}
