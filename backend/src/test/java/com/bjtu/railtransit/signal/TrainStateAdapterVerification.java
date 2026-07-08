package com.bjtu.railtransit.signal;

import com.bjtu.railtransit.signal.domain.AuthorityBasis;
import com.bjtu.railtransit.signal.domain.Direction;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.bjtu.railtransit.signal.domain.SignalEvent;
import com.bjtu.railtransit.signal.domain.TrainState;
import com.bjtu.railtransit.signal.model.*;
import com.bjtu.railtransit.signal.service.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * TrainStateAdapter（边界转换入口）专项自检。
 *
 * 目的：钉死"段相对+m/s → 绝对里程 m+km/h"的唯一转换口径，防多模块各写一份；
 * 并验证非法 segId → NaN → fail-safe、转换后能正常流入 compute。
 *
 * 运行：java com.bjtu.railtransit.signal.TrainStateAdapterVerification
 */
public class TrainStateAdapterVerification {

    static int passed = 0;
    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAIL: " + msg);
        passed++;
        System.out.println("  [PASS] " + msg);
    }
    static void close(double a, double b, double eps, String msg) {
        boolean ok = Double.isInfinite(a) || Double.isInfinite(b)
                ? Double.compare(a, b) == 0 : Math.abs(a - b) <= eps;
        check(ok, msg + " (got=" + a + ", want=" + b + ")");
    }

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

    static void checkSegmentRuntimeConversion() {
        System.out.println("AD-01 段相对+m/s → 绝对里程 m+km/h：");
        LineProfile lp = multiSegLine(4, 500); // seg2 = [500,1000]
        TrainState t = TrainStateAdapter.fromSegmentRuntime(
                lp, "T1", 2, 100.0, 10.0, Direction.UP, 140.0, 0.0);
        // seg2 起点 500 + 偏移 100 → 绝对里程 600；10 m/s → 36 km/h
        close(t.getPositionM(), 600.0, 1e-9, "seg2@100m → 绝对里程 600 m");
        close(t.getSpeedKmh(), 36.0, 1e-9, "10 m/s → 36 km/h");
        close(t.getLengthM(), 140.0, 1e-9, "车长透传 140 m");
        check(t.getDirection() == Direction.UP, "方向透传 UP");
        check("T1".equals(t.getTrainId()), "trainId 透传");
    }

    static void checkAbsoluteBuilder() {
        System.out.println("AD-02 绝对里程+km/h 直接构建：");
        TrainState t = TrainStateAdapter.fromAbsolute("T2", 1234.5, 60.0, Direction.DOWN, 120.0, 5.0);
        close(t.getPositionM(), 1234.5, 1e-9, "positionM 透传");
        close(t.getSpeedKmh(), 60.0, 1e-9, "speedKmh 透传");
        check(t.getDirection() == Direction.DOWN, "方向 DOWN");
        close(t.getLengthM(), 120.0, 1e-9, "车长 120");
    }

    /** 非法 segId → positionM=NaN → compute fail-safe DEGRADED。 */
    static void checkBadSegFailSafe() {
        System.out.println("AD-03 非法 segId → NaN → fail-safe：");
        LineProfile lp = multiSegLine(4, 500);
        TrainState t = TrainStateAdapter.fromSegmentRuntime(
                lp, "BAD", 999, 0.0, 5.0, Direction.UP, 140.0, 0.0);
        check(Double.isNaN(t.getPositionM()), "非法 segId → positionM=NaN");
        MovingAuthorityService svc = new MovingAuthorityService(MaConfig.exampleConfig());
        MovingAuthority m = svc.compute(lp, Arrays.asList(t)).get("BAD");
        check(m != null, "compute 仍返回 MA");
        check(m.getEvent() == SignalEvent.DEGRADED, "NaN 位置 → event=DEGRADED");
        close(m.getMaxSpeedKmh(), 0.0, 1e-9, "NaN 位置 → 限速 0（须停车）");
    }

    /** 转换后流入 compute：前车用 adapter 构建，后车 EoA 受前车约束。 */
    static void checkFlowsIntoCompute() {
        System.out.println("AD-04 转换后流入 compute（前车追踪）：");
        LineProfile lp = multiSegLine(4, 500);
        MovingAuthorityService svc = new MovingAuthorityService(MaConfig.exampleConfig());
        // 前车 seg3@100m=绝对1100，后车 seg1@100m=绝对100，UP
        TrainState prec = TrainStateAdapter.fromSegmentRuntime(lp, "P", 3, 100.0, 0.0, Direction.UP, 140.0, 0.0);
        TrainState self = TrainStateAdapter.fromSegmentRuntime(lp, "S", 1, 100.0, 0.0, Direction.UP, 140.0, 0.0);
        Map<String, MovingAuthority> ma = svc.compute(lp, Arrays.asList(prec, self));
        MovingAuthority ms = ma.get("S");
        check(ms != null, "后车 S 返回 MA");
        check(ms.getBasis() == AuthorityBasis.PRECEDING_TRAIN, "basis=PRECEDING_TRAIN");
        // 前车车尾 = 1100-140=960；gap=20（0 速）→ EoA=940
        close(ms.getEndOfAuthorityM(), 940.0, 1e-6, "前车@1100(尾960) → 后车 EoA=940");
    }

    public static void main(String[] args) {
        System.out.println("===== TrainStateAdapter 自检开始 =====");
        checkSegmentRuntimeConversion();
        checkAbsoluteBuilder();
        checkBadSegFailSafe();
        checkFlowsIntoCompute();
        System.out.println("===== TrainStateAdapter 自检通过：断言数 = " + passed + " =====");
    }
}
