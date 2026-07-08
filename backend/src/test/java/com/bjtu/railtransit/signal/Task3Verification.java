package com.bjtu.railtransit.signal;

import com.bjtu.railtransit.signal.domain.Direction;
import com.bjtu.railtransit.signal.domain.TrainState;
import com.bjtu.railtransit.signal.model.*;
import com.bjtu.railtransit.signal.service.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * TASK-3 自检（沙箱可独立运行 + 供 JUnit 复用）。
 * 用合成线路覆盖：UT-MA-01/03/04/05/06/07/08/09/10、§2.5 数值算例的 requiredGap。
 * 运行：java com.bjtu.railtransit.signal.Task3Verification
 */
public class Task3Verification {

    static int passed = 0;
    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAIL: " + msg);
        passed++;
        System.out.println("  [PASS] " + msg);
    }
    static void close(double a, double b, double eps, String msg) {
        boolean ok;
        if (Double.isInfinite(a) || Double.isInfinite(b)) {
            ok = Double.compare(a, b) == 0;  // INF==INF 视为相等；避免 Infinity-Infinity=NaN
        } else {
            ok = Math.abs(a - b) <= eps;
        }
        check(ok, msg + " (got=" + a + ", want=" + b + ")");
    }

    // ---- 合成线路构造 ----
    static LineProfile multiSegLine(int n, double segLenM) {
        LineProfile lp = new LineProfile();
        List<TrackSegment> segs = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            TrackSegment s = new TrackSegment();
            s.setId(String.valueOf(i));
            s.setLengthCm(segLenM * 100);
            s.setForwardStartSegId(i == 1 ? LineProfile.NONE_ID : i - 1);
            s.setForwardEndSegId(i == n ? LineProfile.NONE_ID : i + 1);
            s.setSideStartSegId(LineProfile.NONE_ID);
            s.setSideEndSegId(LineProfile.NONE_ID);
            segs.add(s);
        }
        lp.setSegments(segs);
        lp.buildMileageIndex();
        lp.setTotalLengthM(n * segLenM);
        return lp;
    }

    static TrainState trainAt(double posM, double speedKmh) {
        TrainState t = new TrainState();
        t.setTrainId("T");
        t.setPositionM(posM);
        t.setSpeedKmh(speedKmh);
        t.setAccelerationMps2(0);
        t.setLengthM(140);
        t.setDirection(Direction.UP);
        t.setTimestamp(0);
        return t;
    }

    // ---- UT-MA-02 / §2.5：requiredGap 平坡 = 110m ----
    static void checkRequiredGapFlat() {
        System.out.println("requiredGap 平坡（§2.5 数值算例）：");
        LineProfile lp = multiSegLine(1, 2000);
        MaConfig cfg = MaConfig.exampleConfig();
        TrackConstraintService svc = new TrackConstraintService(cfg);
        TrainState t = trainAt(0, 64.8); // 18 m/s
        double g = svc.requiredGap(t, null, lp);
        close(g, 110.0, 1e-6, "平坡 requiredGap = 20 + 18*5 = 110 m");
    }

    // ---- UT-MA-10：下坡坡度更大 → gap 比平坡更大 ----
    static void checkDownhillLoosens() {
        System.out.println("UT-MA-10 下坡更早收紧：");
        MaConfig cfg = MaConfig.exampleConfig();
        TrackConstraintService svc = new TrackConstraintService(cfg);
        TrainState t = trainAt(0, 64.8);
        LineProfile flat = multiSegLine(1, 2000);
        double gFlat = svc.requiredGap(t, null, flat);
        // 下坡 -20‰ 覆盖列车位置
        LineProfile down = multiSegLine(1, 2000);
        Gradient gr = new Gradient();
        gr.setId(1); gr.setStartSegId(1); gr.setStartOffsetCm(0);
        gr.setEndSegId(1); gr.setEndOffsetCm(200000); gr.setPermille(-20.0);
        down.setGradients(Arrays.asList(gr));
        double gDown = svc.requiredGap(t, null, down);
        System.out.println("  gFlat=" + gFlat + " gDown=" + gDown);
        check(gDown > gFlat, "下坡 requiredGap 大于平坡（更早收紧）");
    }

    // ---- UT-MA-01 / UT-MA-06：空线/折返 → EoA = 线路终点 ----
    static void checkTurnbackLineEnd() {
        System.out.println("UT-MA-01/06 线路终点/折返：");
        LineProfile lp = multiSegLine(1, 2000);
        MaConfig cfg = MaConfig.exampleConfig();
        TrackConstraintService svc = new TrackConstraintService(cfg);
        double eoa = svc.eoaFromLineLimits(lp, trainAt(0, 0));
        close(eoa, 2000.0, 1e-9, "空线单车 EoA = 线路终点 2000 m");
        // 折返线限制
        Turnback tb = new Turnback(); tb.setId("TB1"); tb.setPositionM(1500); tb.setType(TurnbackType.TERMINAL);
        lp.setTurnbacks(Arrays.asList(tb));
        double eoa2 = svc.eoaFromLineLimits(lp, trainAt(0, 0));
        close(eoa2, 1500.0, 1e-9, "存在折返线(1500m) → EoA 不越折返 = 1500 m");
    }

    // ---- UT-MA-05 / UT-SAFE-02：道岔状态不符 → 止于道岔前 ----
    static void checkSwitchGating() {
        System.out.println("UT-MA-05/SAFE-02 道岔 gating：");
        LineProfile lp = multiSegLine(1, 1000);
        MaConfig cfg = MaConfig.exampleConfig();
        TrackConstraintService svc = new TrackConstraintService(cfg);
        Switch sw = new Switch(); sw.setId("1"); sw.setPositionM(500);
        sw.setNormalSegId(1); sw.setReverseSegId(2); sw.setMergeSegId(3);
        sw.setDivergingSpeedLimitKmh(35);
        // 反位 → 应截断
        sw.setState(SwitchState.REVERSE);
        lp.setSwitches(Arrays.asList(sw));
        close(svc.eoaFromSwitch(lp, trainAt(0, 0)), 500.0, 1e-9, "道岔反位 → EoA 止于道岔 500 m");
        // 定位 → 不截断
        sw.setState(SwitchState.NORMAL);
        close(svc.eoaFromSwitch(lp, trainAt(0, 0)), TrackConstraintService.INF, 1e-9, "道岔定位 → 不截断 (INF)");
        // 失表(null) → fail-safe 截断
        sw.setState(null);
        close(svc.eoaFromSwitch(lp, trainAt(0, 0)), 500.0, 1e-9, "道岔失表 → fail-safe 截断 500 m");
    }

    // ---- UT-MA-07：前方防护信号机 → 止于机前(留余量) ----
    static void checkSignalBoundary() {
        System.out.println("UT-MA-07 信号机硬边界：");
        LineProfile lp = multiSegLine(1, 1000);
        MaConfig cfg = MaConfig.exampleConfig();
        TrackConstraintService svc = new TrackConstraintService(cfg);
        Signal s = new Signal(); s.setId(1); s.setName("S1"); s.setSegId(1); s.setOffsetCm(60000);
        s.setType(1); s.setProtectDir(0xaa);
        lp.setSignals(Arrays.asList(s));
        close(svc.eoaFromSignals(lp, trainAt(0, 0)), 590.0, 1e-9, "信号机@600m → EoA=600-10=590 m");
    }

    // ---- UT-MA-08：前方计轴区段占用 → 止于区段起点 ----
    static void checkAxleOccupancy() {
        System.out.println("UT-MA-08 计轴占用截断：");
        LineProfile lp = multiSegLine(2, 500); // seg1[0,500], seg2[500,1000]
        MaConfig cfg = MaConfig.exampleConfig();
        TrackConstraintService svc = new TrackConstraintService(cfg);
        AxleCounterSection a = new AxleCounterSection(); a.setId(1); a.setName("JZ1");
        a.setSegIds(Arrays.asList(2)); a.setOccupied(true);
        lp.setAxleSections(Arrays.asList(a));
        close(svc.eoaFromAxleOccupancy(lp, trainAt(0, 0)), 500.0, 1e-9, "占用计轴(seg2) → EoA=区段起点 500 m");
        a.setOccupied(false);
        close(svc.eoaFromAxleOccupancy(lp, trainAt(0, 0)), TrackConstraintService.INF, 1e-9, "计轴空闲 → 不截断 (INF)");
    }

    // ---- UT-MA-09：沿进路 + 保护区段 → 终端信号机 + 保护区段末端 ----
    static void checkRouteOverlap() {
        System.out.println("UT-MA-09 进路+保护区段：");
        LineProfile lp = multiSegLine(3, 500); // seg3 end=1500
        MaConfig cfg = MaConfig.exampleConfig();
        TrackConstraintService svc = new TrackConstraintService(cfg);
        // 终端信号机 @1000m (seg3 start)
        Signal end = new Signal(); end.setId(9); end.setName("Z9"); end.setSegId(3); end.setOffsetCm(0);
        lp.setSignals(Arrays.asList(end));
        // 保护区段覆盖 seg3
        AxleCounterSection ovAxle = new AxleCounterSection(); ovAxle.setId(50); ovAxle.setName("OV");
        ovAxle.setSegIds(Arrays.asList(3)); ovAxle.setOccupied(false);
        OverlapSection ov = new OverlapSection(); ov.setId(1); ov.setAxleSectionIds(Arrays.asList(50));
        lp.setAxleSections(Arrays.asList(ovAxle));
        lp.setOverlaps(Arrays.asList(ov));
        Route r = new Route(); r.setId(1); r.setName("R1"); r.setStartSignalId(0); r.setEndSignalId(9);
        r.setAxleSectionIds(new ArrayList<>()); r.setOverlapIds(Arrays.asList(1)); r.setCiZoneId(1);
        close(svc.eoaFromRoute(lp, trainAt(0, 0), r), 1500.0, 1e-9, "进路终端@1000 + 保护区段至 1500 → EoA=1500 m");
    }

    // ---- UT-MA-04：SSR / 激活 TSR 覆盖限速 ----
    static void checkSpeedLimit() {
        System.out.println("UT-MA-04 限速覆盖：");
        LineProfile lp = multiSegLine(1, 1000);
        MaConfig cfg = MaConfig.exampleConfig();
        TrackConstraintService svc = new TrackConstraintService(cfg);
        // 固定限速 [200,800] = 50 km/h
        StaticSpeedRestriction ssr = new StaticSpeedRestriction();
        ssr.setId(1); ssr.setSegId(1); ssr.setStartOffsetCm(20000); ssr.setEndOffsetCm(80000);
        ssr.setSpeedLimitCmps(50 / 0.036); ssr.setSpeedLimitKmh(50);
        lp.setStaticSpeedRestrictions(Arrays.asList(ssr));
        close(svc.speedLimitAt(lp, 500, Direction.UP, 0), 50.0, 1e-9, "SSR 覆盖 → 限速 50 km/h");
        close(svc.speedLimitAt(lp, 100, Direction.UP, 0), 80.0, 1e-9, "SSR 外 → 默认 80 km/h");
        // 激活 TSR [300,600] = 30 km/h
        TemporarySpeedRestriction tsr = new TemporarySpeedRestriction();
        tsr.setStartM(300); tsr.setEndM(600); tsr.setSpeedLimitKmh(30); tsr.setActive(true);
        lp.setTsrs(Arrays.asList(tsr));
        close(svc.speedLimitAt(lp, 500, Direction.UP, 0), 30.0, 1e-9, "激活 TSR 覆盖 → 限速 30 km/h");
        tsr.setActive(false);
        close(svc.speedLimitAt(lp, 500, Direction.UP, 0), 50.0, 1e-9, "TSR 取消 → 回落 SSR 50 km/h");
    }

    public static void main(String[] args) {
        System.out.println("===== TASK-3 自检开始 =====");
        checkRequiredGapFlat();
        checkDownhillLoosens();
        checkTurnbackLineEnd();
        checkSwitchGating();
        checkSignalBoundary();
        checkAxleOccupancy();
        checkRouteOverlap();
        checkSpeedLimit();
        System.out.println("===== TASK-3 自检通过：断言数 = " + passed + " =====");
    }
}
