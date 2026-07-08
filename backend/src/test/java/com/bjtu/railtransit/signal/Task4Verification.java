package com.bjtu.railtransit.signal;

import com.bjtu.railtransit.signal.domain.AuthorityBasis;
import com.bjtu.railtransit.signal.domain.Direction;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.bjtu.railtransit.signal.domain.SignalEvent;
import com.bjtu.railtransit.signal.domain.TrainState;
import com.bjtu.railtransit.signal.model.LineProfile;
import com.bjtu.railtransit.signal.service.MaConfig;
import com.bjtu.railtransit.signal.service.MovingAuthorityService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TASK-4 自检（沙箱可独立运行）：
 *  - UT-MA-01 空线单车 → EoA=线路终点，basis=TURNBACK_END，maxSpeed=线路限速
 *  - UT-MA-02 数值算例（§2.5）→ T2 EoA=750m，basis=PRECEDING_TRAIN
 *  - UT-MA-03 前车匀速前移 → 后车 EoA 随之前移且始终留 requiredGap
 *  - UT-SAFE-01 前车状态未知 / 本车 MA 过期 → EoA=当前位置、maxSpeed 极低、event=DEGRADED/MA_EXPIRED
 * 运行：java com.bjtu.railtransit.signal.Task4Verification
 */
public class Task4Verification {

    static int passed = 0;
    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAIL: " + msg);
        passed++;
        System.out.println("  [PASS] " + msg);
    }
    static void close(double a, double b, double eps, String msg) {
        boolean ok;
        if (Double.isInfinite(a) || Double.isInfinite(b)) ok = Double.compare(a, b) == 0;
        else ok = Math.abs(a - b) <= eps;
        check(ok, msg + " (got=" + a + ", want=" + b + ")");
    }

    // ---- 合成线路 ----
    static LineProfile flatLine(double totalLengthM) {
        LineProfile lp = new LineProfile();
        lp.setTotalLengthM(totalLengthM);
        // 其余实体表保持 null（TrackConstraintService 对 null 返回 INF/默认值）
        return lp;
    }

    static TrainState trainAt(String id, double pos, double speedKmh, double len, Direction dir, double ts) {
        TrainState t = new TrainState();
        t.setTrainId(id);
        t.setPositionM(pos);
        t.setSpeedKmh(speedKmh);
        t.setLengthM(len);
        t.setDirection(dir);
        t.setTimestamp(ts);
        t.setAccelerationMps2(0);
        return t;
    }

    public static void main(String[] args) {
        System.out.println("===== TASK-4 自检开始 =====");
        checkMa01();
        checkMa02();
        checkMa03();
        checkSafe01();
        System.out.println("===== TASK-4 自检通过：断言数 = " + passed + " =====");
    }

    // UT-MA-01
    static void checkMa01() {
        System.out.println("UT-MA-01 空线单车：");
        MaConfig cfg = MaConfig.exampleConfig();
        MovingAuthorityService svc = new MovingAuthorityService(cfg);
        LineProfile lp = flatLine(2000);
        TrainState t = trainAt("T1", 0, 0, 140, Direction.UP, 0);
        Map<String, MovingAuthority> ma = svc.compute(lp, Arrays.asList(t));
        MovingAuthority m = ma.get("T1");
        close(m.getEndOfAuthorityM(), 2000, 1e-6, "空线单车 EoA = 线路终点 2000 m");
        check(m.getBasis() == AuthorityBasis.TURNBACK_END, "basis = TURNBACK_END");
        close(m.getMaxSpeedKmh(), 80, 1e-6, "maxSpeed = 线路默认限速 80 km/h");
    }

    // UT-MA-02 （§2.5 数值算例）
    static void checkMa02() {
        System.out.println("UT-MA-02 数值算例（两车跟车）：");
        MaConfig cfg = MaConfig.exampleConfig();
        MovingAuthorityService svc = new MovingAuthorityService(cfg);
        LineProfile lp = flatLine(2000);
        TrainState t1 = trainAt("T1", 1000, 0, 140, Direction.UP, 42);    // 已停前车
        TrainState t2 = trainAt("T2", 0, 64.8, 140, Direction.UP, 42);    // 后车 18 m/s
        Map<String, MovingAuthority> ma = svc.compute(lp, Arrays.asList(t1, t2));
        MovingAuthority m = ma.get("T2");
        close(m.getEndOfAuthorityM(), 750, 1e-6, "T2 EoA = 750 m (1000-140-110)");
        check(m.getBasis() == AuthorityBasis.PRECEDING_TRAIN, "basis = PRECEDING_TRAIN");
        close(m.getMaxSpeedKmh(), 80, 1e-6, "maxSpeed = 80 km/h（限速宽松）");
    }

    // UT-MA-03 前车匀速前移
    static void checkMa03() {
        System.out.println("UT-MA-03 前车前移 → 后车 EoA 动态前移：");
        MaConfig cfg = MaConfig.exampleConfig();
        MovingAuthorityService svc = new MovingAuthorityService(cfg);
        LineProfile lp = flatLine(5000);
        double[] t1pos = {2000, 3000, 4000};
        double prevEoa = -1;
        for (double p : t1pos) {
            TrainState t1 = trainAt("T1", p, 0, 140, Direction.UP, 42);
            TrainState t2 = trainAt("T2", 0, 64.8, 140, Direction.UP, 42);
            MovingAuthority m = svc.compute(lp, Arrays.asList(t1, t2)).get("T2");
            double expect = p - 140 - 110; // tail - requiredGap(平坡=110)
            close(m.getEndOfAuthorityM(), expect, 1e-6, "T1@" + p + " → T2 EoA=" + expect + "m");
            check(m.getBasis() == AuthorityBasis.PRECEDING_TRAIN, "  basis = PRECEDING_TRAIN");
            check(m.getEndOfAuthorityM() <= (p - 140 - cfg.safeSeparationM) + 1e-6,
                    "  EoA ≤ 前车车尾 − 安全净距(" + cfg.safeSeparationM + "m)");
            check(m.getEndOfAuthorityM() > prevEoa, "  EoA 随前车前移而增大（不卡死）");
            prevEoa = m.getEndOfAuthorityM();
        }
    }

    // UT-SAFE-01 前车状态未知 / 本车 MA 过期
    static void checkSafe01() {
        System.out.println("UT-SAFE-01 fail-safe 降级：");
        MaConfig cfg = MaConfig.exampleConfig();
        MovingAuthorityService svc = new MovingAuthorityService(cfg);
        LineProfile lp = flatLine(2000);

        // (a) 前车状态未知（position 为 NaN）
        TrainState bad = trainAt("T1", Double.NaN, 0, 140, Direction.UP, 42);
        TrainState t2 = trainAt("T2", 0, 64.8, 140, Direction.UP, 42);
        MovingAuthority m1 = svc.compute(lp, Arrays.asList(bad, t2)).get("T2");
        close(m1.getEndOfAuthorityM(), 0, 1e-6, "前车未知 → EoA = 当前位置 0 m");
        check(m1.getEvent() == SignalEvent.DEGRADED, "  event = DEGRADED");
        close(m1.getMaxSpeedKmh(), cfg.degradedSpeedKmh, 1e-6, "  maxSpeed = 降级值(" + cfg.degradedSpeedKmh + ")");

        // (b) 本车 MA 过期（timestamp 远早于 now）
        TrainState t = trainAt("T2", 0, 64.8, 140, Direction.UP, 0);
        MovingAuthority m2 = svc.compute(lp, Arrays.asList(t), new HashMap<>(), 100.0).get("T2");
        close(m2.getEndOfAuthorityM(), 0, 1e-6, "MA 过期 → EoA = 当前位置 0 m");
        check(m2.getEvent() == SignalEvent.MA_EXPIRED, "  event = MA_EXPIRED");
        close(m2.getMaxSpeedKmh(), cfg.degradedSpeedKmh, 1e-6, "  maxSpeed = 降级值");
    }
}
