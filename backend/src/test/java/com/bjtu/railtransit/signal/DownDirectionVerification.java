package com.bjtu.railtransit.signal;

import com.bjtu.railtransit.signal.domain.AuthorityBasis;
import com.bjtu.railtransit.signal.domain.Direction;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.bjtu.railtransit.signal.domain.TrainState;
import com.bjtu.railtransit.signal.model.*;
import com.bjtu.railtransit.signal.service.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * DOWN 方向专项自检（沙箱可独立运行 + 供 JUnit 复用）。
 *
 * 目标：钉死「TrackConstraintService 六个几何约束 + 前车追踪」在 DOWN 方向下的镜像语义，
 * 覆盖：折返 / 道岔 gating / 信号机硬边界 / 计轴占用 / 进路+保护区段 / 前车追踪 / 侧向限速。
 * 同时含一个 UP 回归守卫，确保 DOWN 修改不破坏既有 UP 行为。
 *
 * 运行：java com.bjtu.railtransit.signal.DownDirectionVerification
 */
public class DownDirectionVerification {

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

    // ---- 合成线路构造（与 Task3Verification 同套路）----
    static LineProfile multiSegLine(int n, double segLenM) {
        LineProfile lp = new LineProfile();
        List<TrackSegment> segs = new java.util.ArrayList<>();
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

    static TrainState trainDown(String id, double posM, double speedKmh) {
        TrainState t = new TrainState();
        t.setTrainId(id);
        t.setPositionM(posM);
        t.setSpeedKmh(speedKmh);
        t.setAccelerationMps2(0);
        t.setLengthM(140);
        t.setDirection(Direction.DOWN);
        t.setTimestamp(0);
        return t;
    }

    // ================= DOWN 几何约束 =================

    /** DOWN 折返：取当前位「下方」最近折返（无折返则止于线路起点 0）。 */
    static void checkDownTurnback() {
        System.out.println("DOWN-01 折返/线路起点：");
        LineProfile lp = multiSegLine(4, 500); // [0,2000]
        MaConfig cfg = MaConfig.exampleConfig();
        TrackConstraintService svc = new TrackConstraintService(cfg);
        Turnback tb = new Turnback(); tb.setId("TB1"); tb.setPositionM(500); tb.setType(TurnbackType.TERMINAL);
        lp.setTurnbacks(Arrays.asList(tb));
        // train DOWN @1000，下方最近折返在 500 → EoA=500（而非 0）
        close(svc.eoaFromTurnback(lp, trainDown("T", 1000, 0)), 500.0, 1e-9,
                "DOWN 列车@1000、折返@500 → EoA=500（下方最近折返）");
        // 无折返 → 止于线路起点 0
        LineProfile lp2 = multiSegLine(4, 500);
        close(svc.eoaFromTurnback(lp2, trainDown("T", 1000, 0)), 0.0, 1e-9,
                "DOWN 无折返 → EoA=线路起点 0");
    }

    /** DOWN 道岔 gating：取下方最近的反位/失表道岔。 */
    static void checkDownSwitch() {
        System.out.println("DOWN-02 道岔 gating：");
        LineProfile lp = multiSegLine(4, 500);
        MaConfig cfg = MaConfig.exampleConfig();
        TrackConstraintService svc = new TrackConstraintService(cfg);
        Switch sw = new Switch(); sw.setId("1"); sw.setPositionM(500);
        sw.setNormalSegId(1); sw.setReverseSegId(2); sw.setMergeSegId(3);
        sw.setDivergingSpeedLimitKmh(35);
        // 反位 → 截断于 500
        sw.setState(SwitchState.REVERSE);
        lp.setSwitches(Arrays.asList(sw));
        close(svc.eoaFromSwitch(lp, trainDown("T", 1000, 0)), 500.0, 1e-9,
                "DOWN 道岔反位 → EoA 止于下方道岔 500 m");
        // 定位 → 不截断
        sw.setState(SwitchState.NORMAL);
        close(svc.eoaFromSwitch(lp, trainDown("T", 1000, 0)), TrackConstraintService.INF, 1e-9,
                "DOWN 道岔定位 → 不截断 (INF)");
        // 失表(null) → fail-safe 截断
        sw.setState(null);
        close(svc.eoaFromSwitch(lp, trainDown("T", 1000, 0)), 500.0, 1e-9,
                "DOWN 道岔失表 → fail-safe 截断 500 m");
    }

    /** DOWN 信号机：取下方最近的防护信号机，止于机前余量。 */
    static void checkDownSignal() {
        System.out.println("DOWN-03 信号机硬边界：");
        LineProfile lp = multiSegLine(4, 500); // seg2 [500,1000]
        MaConfig cfg = MaConfig.exampleConfig();
        TrackConstraintService svc = new TrackConstraintService(cfg);
        // 信号机 @600m（seg2 起点 500 + 偏移 100m）
        Signal s = new Signal(); s.setId(1); s.setName("S1"); s.setSegId(2); s.setOffsetCm(10000);
        s.setType(1); s.setProtectDir(0xaa);
        lp.setSignals(Arrays.asList(s));
        // train DOWN @1000，下方最近信号机在 600 → 停在机前低里程侧 = 600+10=610
        close(svc.eoaFromSignals(lp, trainDown("T", 1000, 0)), 610.0, 1e-9,
                "DOWN 信号机@600m → EoA=600+10=610 m（机前低里程侧停车）");
    }

    /** DOWN 计轴占用：取下方最近占用区段起点。 */
    static void checkDownAxle() {
        System.out.println("DOWN-04 计轴占用截断：");
        LineProfile lp = multiSegLine(4, 500); // seg2 [500,1000]
        MaConfig cfg = MaConfig.exampleConfig();
        TrackConstraintService svc = new TrackConstraintService(cfg);
        AxleCounterSection a = new AxleCounterSection(); a.setId(1); a.setName("JZ1");
        a.setSegIds(Arrays.asList(2)); a.setOccupied(true);
        lp.setAxleSections(Arrays.asList(a));
        // train DOWN @1000，下方占用区段起点 500 → EoA=500
        close(svc.eoaFromAxleOccupancy(lp, trainDown("T", 1000, 0)), 500.0, 1e-9,
                "DOWN 占用计轴(seg2) → EoA=区段起点 500 m");
        a.setOccupied(false);
        close(svc.eoaFromAxleOccupancy(lp, trainDown("T", 1000, 0)), TrackConstraintService.INF, 1e-9,
                "DOWN 计轴空闲 → 不截断 (INF)");
    }

    /** DOWN 进路+保护区段：授权延伸至「始端信号机 + 保护区段起点」（镜像 UP 的终端+末端）。 */
    static void checkDownRouteOverlap() {
        System.out.println("DOWN-05 进路+保护区段：");
        LineProfile lp = multiSegLine(4, 500); // seg1[0,500]...seg4[1500,2000]
        MaConfig cfg = MaConfig.exampleConfig();
        TrackConstraintService svc = new TrackConstraintService(cfg);
        // 始端信号机 @700m（seg2 起点 500 + 偏移 200m）
        Signal start = new Signal(); start.setId(5); start.setName("X5"); start.setSegId(2); start.setOffsetCm(20000);
        lp.setSignals(Arrays.asList(start));
        // 保护区段覆盖 seg1 → 保护区段起点=0（DOWN 方向最远授权点）
        AxleCounterSection ovAxle = new AxleCounterSection(); ovAxle.setId(50); ovAxle.setName("OV");
        ovAxle.setSegIds(Arrays.asList(1)); ovAxle.setOccupied(false);
        OverlapSection ov = new OverlapSection(); ov.setId(1); ov.setAxleSectionIds(Arrays.asList(50));
        lp.setAxleSections(Arrays.asList(ovAxle));
        lp.setOverlaps(Arrays.asList(ov));
        Route r = new Route(); r.setId(1); r.setName("R1"); r.setStartSignalId(5); r.setEndSignalId(9);
        r.setAxleSectionIds(new java.util.ArrayList<>()); r.setOverlapIds(Arrays.asList(1)); r.setCiZoneId(1);
        // train DOWN @1000 → 沿始端信号(700) + 保护区段起点(0) → EoA=0
        close(svc.eoaFromRoute(lp, trainDown("T", 1000, 0), r), 0.0, 1e-9,
                "DOWN 进路始端@700 + 保护区段至 0 → EoA=0 m");
    }

    /** DOWN 侧向限速：本车与 EoA 之间若存在反位道岔，取侧向限速（镜像 UP）。 */
    static void checkDownSpeedLimit() {
        System.out.println("DOWN-06 侧向限速：");
        LineProfile lp = multiSegLine(4, 500);
        MaConfig cfg = MaConfig.exampleConfig();
        TrackConstraintService svc = new TrackConstraintService(cfg);
        // 道岔 @600（在 EoA=500 与列车=1000 之间），反位 → 套用侧向限速
        Switch sw = new Switch(); sw.setId("1"); sw.setPositionM(600); sw.setDivergingSpeedLimitKmh(35);
        sw.setNormalSegId(1); sw.setReverseSegId(2); sw.setMergeSegId(3);
        sw.setState(SwitchState.REVERSE);
        lp.setSwitches(Arrays.asList(sw));
        double spRev = svc.speedLimitAt(lp, 500.0, Direction.DOWN, 1000.0);
        close(spRev, 35.0, 1e-9, "DOWN 侧向道岔(600 在 500~1000 间) → 限速 35 km/h");
        sw.setState(SwitchState.NORMAL);
        double spNorm = svc.speedLimitAt(lp, 500.0, Direction.DOWN, 1000.0);
        close(spNorm, 80.0, 1e-9, "DOWN 定位道岔 → 不降速 80 km/h");
    }

    // ================= DOWN 前车追踪（端到端 compute）=================

    /** DOWN 前车追踪：self@1000、preceding@300(长140) → EoA=300+140+gap(=20)=460，basis=PRECEDING_TRAIN。 */
    static void checkDownPreceding() {
        System.out.println("DOWN-07 前车追踪（compute 端到端）：");
        LineProfile lp = multiSegLine(4, 500);
        MaConfig cfg = MaConfig.exampleConfig();
        MovingAuthorityService svc = new MovingAuthorityService(cfg);
        TrainState t1 = trainDown("T1", 300, 0);   // 前车（DOWN，更靠下）
        TrainState t2 = trainDown("T2", 1000, 0);  // 本车（DOWN）
        Map<String, MovingAuthority> ma = svc.compute(lp, Arrays.asList(t2, t1));
        MovingAuthority m2 = ma.get("T2");
        check(m2 != null, "DOWN 本车 T2 返回 MA");
        check(m2.getBasis() == AuthorityBasis.PRECEDING_TRAIN, "DOWN 前车追踪 basis=PRECEDING_TRAIN");
        close(m2.getEndOfAuthorityM(), 460.0, 1e-6, "DOWN 前车@300 → EoA=440(车尾)+20(净距)=460 m");
        // 限速默认 80（此处无 TSR/道岔影响）
        close(m2.getMaxSpeedKmh(), 80.0, 1e-9, "DOWN 前车场景下限速=80 km/h");
    }

    // ================= UP 回归守卫（防 DOWN 修改破坏既有行为）=================

    static void checkUpUnaffected() {
        System.out.println("UP 回归守卫（DOWN 修改不得影响 UP）：");
        LineProfile lp = multiSegLine(4, 500);
        MaConfig cfg = MaConfig.exampleConfig();
        TrackConstraintService svc = new TrackConstraintService(cfg);
        // UP 列车 @0，上方信号机 @600 → EoA=590
        Signal s = new Signal(); s.setId(1); s.setName("S1"); s.setSegId(2); s.setOffsetCm(10000);
        s.setType(1); s.setProtectDir(0xaa);
        lp.setSignals(Arrays.asList(s));
        TrainState up = new TrainState();
        up.setTrainId("U"); up.setPositionM(0); up.setSpeedKmh(0); up.setAccelerationMps2(0);
        up.setLengthM(140); up.setDirection(Direction.UP); up.setTimestamp(0);
        close(svc.eoaFromSignals(lp, up), 590.0, 1e-9, "UP 信号机@600 → EoA=590（行为不变）");
        // UP 折返/线路终点
        Turnback tb = new Turnback(); tb.setId("TB1"); tb.setPositionM(500); tb.setType(TurnbackType.TERMINAL);
        lp.setTurnbacks(Arrays.asList(tb));
        close(svc.eoaFromTurnback(lp, up), 500.0, 1e-9, "UP 折返@500 → EoA=500（行为不变）");
    }

    /** DOWN eoaFromLineLimits：方向感知，取下方最近边界=max；+INF 视为无该约束（P2 修复钉死）。 */
    static void checkDownLineLimits() {
        System.out.println("DOWN-08 线路综合约束(方向感知)：");
        LineProfile lp = multiSegLine(4, 500); // [0,2000]
        MaConfig cfg = MaConfig.exampleConfig();
        TrackConstraintService svc = new TrackConstraintService(cfg);
        // 折返@500、反位道岔@600 → DOWN 取下方最近边界 = max(500,600)=600
        Turnback tb = new Turnback(); tb.setId("TB1"); tb.setPositionM(500); tb.setType(TurnbackType.TERMINAL);
        lp.setTurnbacks(Arrays.asList(tb));
        Switch sw = new Switch(); sw.setId("1"); sw.setPositionM(600);
        sw.setNormalSegId(1); sw.setReverseSegId(2); sw.setMergeSegId(3); sw.setDivergingSpeedLimitKmh(35);
        sw.setState(SwitchState.REVERSE);
        lp.setSwitches(Arrays.asList(sw));
        close(svc.eoaFromLineLimits(lp, trainDown("T", 1000, 0)), 600.0, 1e-9,
                "DOWN 折返@500+道岔@600 → 取下方最近=max=600");
        // 道岔定位(无约束) → 仅折返@500 → 500
        sw.setState(SwitchState.NORMAL);
        close(svc.eoaFromLineLimits(lp, trainDown("T", 1000, 0)), 500.0, 1e-9,
                "DOWN 定位道岔(无约束) → 仅折返@500");
    }

    public static void main(String[] args) {
        System.out.println("===== DOWN 方向专项自检开始 =====");
        checkDownTurnback();
        checkDownSwitch();
        checkDownSignal();
        checkDownAxle();
        checkDownRouteOverlap();
        checkDownSpeedLimit();
        checkDownLineLimits();
        checkDownPreceding();
        checkUpUnaffected();
        System.out.println("===== DOWN 方向专项自检通过：断言数 = " + passed + " =====");
    }
}
