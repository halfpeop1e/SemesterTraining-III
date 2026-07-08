package com.bjtu.railtransit.signal;

import com.bjtu.railtransit.signal.domain.AuthorityBasis;
import com.bjtu.railtransit.signal.domain.Direction;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.bjtu.railtransit.signal.domain.SignalAspect;
import com.bjtu.railtransit.signal.domain.SignalEvent;
import com.bjtu.railtransit.signal.domain.TrainState;
import com.bjtu.railtransit.signal.model.*;
import com.bjtu.railtransit.signal.service.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 司机台协议对齐专项自检（沙箱可独立运行 + 供 JUnit 复用）。
 *
 * 范围：仅 MA 模块（轨道线路与信号控制）与「704 司机台协议」之间的对齐冲突点。
 *   1) 方向编码：司机台信号屏 0=上行/1=下行 → Direction（含非法→INVALID 兜底）；
 *   2) 信号机显示：SignalAspect 权威编码(3.3.2) + 到司机台信号屏(红/绿/白)映射；
 *   3) eoaFromSignals 放行逻辑：绿灯放行、停车/灭/断/null 截断；
 *   4) compute 端到端：绿灯信号不误杀通过、非法方向 fail-safe 收紧；
 *   5) 回归守卫：既有 UP/RED 行为不被破坏。
 *
 * 运行：java com.bjtu.railtransit.signal.DriverConsoleAlignmentVerification
 */
public class DriverConsoleAlignmentVerification {

    static int passed = 0;
    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAIL: " + msg);
        passed++;
        System.out.println("  [PASS] " + msg);
    }
    static void close(double a, double b, double eps, String msg) {
        boolean ok;
        if (Double.isInfinite(a) || Double.isInfinite(b)) {
            ok = Double.compare(a, b) == 0;
        } else {
            ok = Math.abs(a - b) <= eps;
        }
        check(ok, msg + " (got=" + a + ", want=" + b + ")");
    }

    // ---- 合成线路（与 DownDirectionVerification 同套路）----
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

    static Signal sig(int id, int segId, double offsetM, SignalAspect aspect) {
        Signal s = new Signal();
        s.setId(id); s.setName("S" + id); s.setSegId(segId); s.setOffsetCm(offsetM * 100);
        s.setType(1); s.setProtectDir(0xaa); s.setAspect(aspect);
        return s;
    }

    static TrainState trainUp(String id, double posM, double speedKmh) {
        TrainState t = new TrainState();
        t.setTrainId(id); t.setPositionM(posM); t.setSpeedKmh(speedKmh); t.setAccelerationMps2(0);
        t.setLengthM(140); t.setDirection(Direction.UP); t.setTimestamp(0);
        return t;
    }

    // ================= 1. 方向编解码 =================

    static void checkDirectionCodec() {
        System.out.println("DC-01 司机台方向编码 → Direction：");
        check(Direction.fromDriverConsole(0) == Direction.UP, "0 → UP");
        check(Direction.fromDriverConsole(1) == Direction.DOWN, "1 → DOWN");
        check(Direction.fromDriverConsole(2) == Direction.INVALID, "2(非法) → INVALID");
        check(Direction.fromDriverConsole(0xFF) == Direction.INVALID, "0xFF → INVALID");
    }

    // ================= 2. 信号机显示编码 =================

    static void checkSignalAspectDecode() {
        System.out.println("DC-02 SignalAspect 解码（3.3.2 权威编码）：");
        check(SignalAspect.decodeSignalSystem(0x01) == SignalAspect.RED, "0x01 → RED");
        check(SignalAspect.decodeSignalSystem(0x04) == SignalAspect.GREEN, "0x04 → GREEN");
        check(SignalAspect.decodeSignalSystem(0x08) == SignalAspect.WHITE, "0x08 → WHITE");
        check(SignalAspect.decodeSignalSystem(0x03) == SignalAspect.RED_YELLOW, "0x03 → RED_YELLOW");
        check(SignalAspect.decodeSignalSystem(0x06) == SignalAspect.RED_DARK, "0x06 → RED_DARK(灭)");
        check(SignalAspect.decodeSignalSystem(0x09) == SignalAspect.RED_BROKEN, "0x09 → RED_BROKEN(断)");
        check(SignalAspect.decodeSignalSystem(0x99) == null, "未知 0x99 → null(fail-safe)");
    }

    static void checkSignalAspectToDriverConsole() {
        System.out.println("DC-03 SignalAspect → 司机台信号屏(红/绿/白)：");
        check(SignalAspect.GREEN.toDriverConsoleScreen() == 0x02, "GREEN → 绿(0x02)");
        check(SignalAspect.WHITE.toDriverConsoleScreen() == 0x03, "WHITE → 白(0x03)");
        check(SignalAspect.RED.toDriverConsoleScreen() == 0x01, "RED → 红(0x01)");
        check(SignalAspect.YELLOW.toDriverConsoleScreen() == 0x01, "YELLOW → 红(聚合,最严格)");
        check(SignalAspect.RED_YELLOW.toDriverConsoleScreen() == 0x01, "RED_YELLOW → 红");
        check(SignalAspect.BLUE.toDriverConsoleScreen() == 0x01, "BLUE → 红");
        check(SignalAspect.RED_DARK.toDriverConsoleScreen() == 0x01, "灭灯 → 红");
        check(SignalAspect.RED_BROKEN.toDriverConsoleScreen() == 0x01, "断灯 → 红");
    }

    static void checkIsProceed() {
        System.out.println("DC-04 isProceed（放行判定）：");
        check(SignalAspect.GREEN.isProceed(), "GREEN 放行");
        check(!SignalAspect.RED.isProceed(), "RED 不放行");
        check(!SignalAspect.RED_YELLOW.isProceed(), "RED_YELLOW 不放行");
        check(!SignalAspect.WHITE.isProceed(), "WHITE 不放行(保守)");
        check(!SignalAspect.YELLOW.isProceed(), "YELLOW 不放行");
        check(!SignalAspect.RED_DARK.isProceed(), "灭灯不放行");
    }

    // ================= 3. eoaFromSignals 放行逻辑 =================

    static void checkSignalProceedUp() {
        System.out.println("DC-05 eoaFromSignals 放行（UP）：");
        LineProfile lp = multiSegLine(4, 500); // seg2[500,1000]，信号@600m
        MaConfig cfg = MaConfig.exampleConfig();
        TrackConstraintService svc = new TrackConstraintService(cfg);
        // 绿灯 → 不截断
        lp.setSignals(Arrays.asList(sig(1, 2, 100, SignalAspect.GREEN)));
        close(svc.eoaFromSignals(lp, trainUp("T", 0, 0)), TrackConstraintService.INF, 1e-9,
                "UP 绿灯信号@600 → 不截断(INF)，列车可越过");
        // 红灯 → 截断于 600-10=590
        lp.setSignals(Arrays.asList(sig(1, 2, 100, SignalAspect.RED)));
        close(svc.eoaFromSignals(lp, trainUp("T", 0, 0)), 590.0, 1e-9,
                "UP 红灯信号@600 → EoA=590（机前停车）");
        // null（未接入）→ fail-safe 截断 590
        lp.setSignals(Arrays.asList(sig(1, 2, 100, null)));
        close(svc.eoaFromSignals(lp, trainUp("T", 0, 0)), 590.0, 1e-9,
                "UP 信号未接入 → fail-safe 截断 590");
        // 灭灯/断灯 → 截断
        lp.setSignals(Arrays.asList(sig(1, 2, 100, SignalAspect.RED_DARK)));
        close(svc.eoaFromSignals(lp, trainUp("T", 0, 0)), 590.0, 1e-9,
                "UP 灭灯 → 截断 590");
    }

    /** 绿灯在前、红灯更远 → EoA 应取更远的红灯（绿灯被越过不构成边界）。 */
    static void checkNearestStopSignal() {
        System.out.println("DC-06 取前方最近停车信号（绿灯不截断）：");
        LineProfile lp = multiSegLine(4, 500);
        MaConfig cfg = MaConfig.exampleConfig();
        TrackConstraintService svc = new TrackConstraintService(cfg);
        // 绿灯@300(seg1)+红灯@900(seg2)，列车@0 → 越过绿灯、停在红灯前 890
        Signal green = sig(1, 1, 300, SignalAspect.GREEN);
        Signal red = sig(2, 2, 400, SignalAspect.RED);
        lp.setSignals(Arrays.asList(green, red));
        close(svc.eoaFromSignals(lp, trainUp("T", 0, 0)), 890.0, 1e-9,
                "UP 绿灯@300+红灯@900 → EoA=890（越过绿灯停在红灯前）");
    }

    // ================= 4. compute 端到端 =================

    static void checkComputeGreenThrough() {
        System.out.println("DC-07 compute 端到端（绿灯不误杀通过）：");
        LineProfile lp = multiSegLine(4, 500);
        MaConfig cfg = MaConfig.exampleConfig();
        MovingAuthorityService svc = new MovingAuthorityService(cfg);
        // 绿灯@300，无其他约束 → UP 列车应越过信号、EoA=线路终点 2000（basis=TURNBACK_END）
        lp.setSignals(Arrays.asList(sig(1, 1, 300, SignalAspect.GREEN)));
        TrainState up = trainUp("T", 0, 0);
        Map<String, MovingAuthority> ma = svc.compute(lp, Arrays.asList(up));
        MovingAuthority m = ma.get("T");
        check(m != null, "compute 返回 MA");
        check(m.getBasis() == AuthorityBasis.TURNBACK_END, "绿灯@300 无停车信号 → basis=TURNBACK_END");
        close(m.getEndOfAuthorityM(), 2000.0, 1e-6, "EoA=线路终点 2000（未在绿灯误停）");
        // 对比：红灯@300 → 截断在 290
        lp.setSignals(Arrays.asList(sig(1, 1, 300, SignalAspect.RED)));
        MovingAuthority m2 = svc.compute(lp, Arrays.asList(up)).get("T");
        check(m2.getBasis() == AuthorityBasis.SIGNAL, "红灯@300 → basis=SIGNAL");
        close(m2.getEndOfAuthorityM(), 290.0, 1e-6, "红灯@300 → EoA=290");
        check(m2.getCapSignalId() != null && m2.getCapSignalId() == 1, "红灯 capSignalId=1");
    }

    static void checkInvalidDirectionFailSafe() {
        System.out.println("DC-08 非法方向 fail-safe：");
        LineProfile lp = multiSegLine(4, 500);
        MaConfig cfg = MaConfig.exampleConfig();
        MovingAuthorityService svc = new MovingAuthorityService(cfg);
        TrainState bad = new TrainState();
        bad.setTrainId("X"); bad.setPositionM(0); bad.setSpeedKmh(0); bad.setAccelerationMps2(0);
        bad.setLengthM(140); bad.setDirection(Direction.fromDriverConsole(9)); bad.setTimestamp(0);
        MovingAuthority m = svc.compute(lp, Arrays.asList(bad)).get("X");
        check(m != null, "非法方向返回 MA");
        check(m.getEvent() == SignalEvent.DEGRADED, "非法方向 → event=DEGRADED");
        close(m.getEndOfAuthorityM(), 0.0, 1e-9, "非法方向 → EoA 收紧至当前位置 0");
        close(m.getMaxSpeedKmh(), 0.0, 1e-9, "非法方向 → 限速 0");
    }

    // ================= 5. 回归守卫 =================

    static void checkUpRegression() {
        System.out.println("UP 回归守卫（对齐改动不得破坏既有行为）：");
        LineProfile lp = multiSegLine(4, 500);
        MaConfig cfg = MaConfig.exampleConfig();
        TrackConstraintService svc = new TrackConstraintService(cfg);
        // 信号未设 aspect（默认 null）→ 维持既有硬截断 590
        Signal s = new Signal(); s.setId(1); s.setName("S1"); s.setSegId(2); s.setOffsetCm(10000);
        s.setType(1); s.setProtectDir(0xaa);  // aspect 默认 null
        lp.setSignals(Arrays.asList(s));
        close(svc.eoaFromSignals(lp, trainUp("T", 0, 0)), 590.0, 1e-9,
                "UP 信号未设 aspect(默认 null) → 仍截断 590（行为不变）");
    }

    public static void main(String[] args) {
        System.out.println("===== 司机台协议对齐自检开始 =====");
        checkDirectionCodec();
        checkSignalAspectDecode();
        checkSignalAspectToDriverConsole();
        checkIsProceed();
        checkSignalProceedUp();
        checkNearestStopSignal();
        checkComputeGreenThrough();
        checkInvalidDirectionFailSafe();
        checkUpRegression();
        System.out.println("===== 司机台协议对齐自检通过：断言数 = " + passed + " =====");
    }
}
