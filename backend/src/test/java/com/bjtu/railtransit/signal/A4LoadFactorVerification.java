package com.bjtu.railtransit.signal;

import com.bjtu.railtransit.signal.domain.Direction;
import com.bjtu.railtransit.signal.domain.TrainState;
import com.bjtu.railtransit.signal.model.LineProfile;
import com.bjtu.railtransit.signal.service.MaConfig;
import com.bjtu.railtransit.signal.service.TrackConstraintService;

/**
 * A4 载重折减制动验证。
 *
 * 验证点：
 * 1. 同速同坡，loadFactor=0 vs 1 时 gap 单调不减（重载 gap >= 轻载 gap）
 * 2. 未知 load（NaN）与旧行为一致（=额定 aBrake，§2.5 数值算例 110m）
 * 3. load=0 与未知 load gap 完全相等
 * 4. load 从 0→1 gap 单调递增
 * 5. effectiveBrakeDecel：未知=额定，load=1 < 额定
 * 6. 负值 load 按额定处理（不崩溃）
 * 7. load>1 封顶 1.0（不爆炸）
 */
public class A4LoadFactorVerification {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        checkMonotonicGap();
        checkUnknownEqualsLegacy();
        checkLoad0EqualsUnknown();
        checkMonotonicIncreasing();
        checkEffectiveBrakeDecel();
        checkNegativeLoad();
        checkOverflowLoad();
        System.out.println("A4 done: pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    /** 同速同坡：load=1 gap >= load=0 gap */
    static void checkMonotonicGap() {
        System.out.println("A4-1 同速同坡 load=1 gap >= load=0 gap:");
        MaConfig cfg = MaConfig.exampleConfig();
        TrackConstraintService svc = new TrackConstraintService(cfg);
        LineProfile lp = flatLine(5000);
        TrainState t0 = trainAt(100, 64.8);
        t0.setLoadFactor(0.0);
        TrainState t1 = trainAt(100, 64.8);
        t1.setLoadFactor(1.0);
        double g0 = svc.requiredGap(t0, null, lp);
        double g1 = svc.requiredGap(t1, null, lp);
        System.out.println("  g(load=0)=" + g0 + " g(load=1)=" + g1);
        check(g1 >= g0 - 1e-9, "重载 gap >= 轻载 gap");
        check(g1 > g0, "重载 gap 严格大于轻载 gap");
    }

    /** 未知 load（NaN）= 旧行为（§2.5 110m） */
    static void checkUnknownEqualsLegacy() {
        System.out.println("A4-2 未知 load(NaN) = §2.5 旧算例 110m:");
        MaConfig cfg = MaConfig.exampleConfig();
        TrackConstraintService svc = new TrackConstraintService(cfg);
        LineProfile lp = flatLine(5000);
        TrainState t = trainAt(0, 64.8); // 18 m/s
        // loadFactor 默认 NaN（未知）
        double g = svc.requiredGap(t, null, lp);
        System.out.println("  gap(NaN)=" + g);
        close(g, 110.0, 1e-6, "未知 load = 110m（额定 aBrake）");
    }

    /** load=0 与未知 load gap 完全相等 */
    static void checkLoad0EqualsUnknown() {
        System.out.println("A4-3 load=0 与未知 load gap 完全相等:");
        MaConfig cfg = MaConfig.exampleConfig();
        TrackConstraintService svc = new TrackConstraintService(cfg);
        LineProfile lp = flatLine(5000);
        TrainState t0 = trainAt(100, 64.8);
        t0.setLoadFactor(0.0);
        TrainState tNaN = trainAt(100, 64.8);
        // tNaN.loadFactor = NaN（默认）
        double g0 = svc.requiredGap(t0, null, lp);
        double gNaN = svc.requiredGap(tNaN, null, lp);
        System.out.println("  g(0)=" + g0 + " g(NaN)=" + gNaN);
        close(g0, gNaN, 1e-9, "load=0 == load=NaN");
    }

    /** load 从 0→1 gap 单调递增 */
    static void checkMonotonicIncreasing() {
        System.out.println("A4-4 load 0→1 gap 单调递增:");
        MaConfig cfg = MaConfig.exampleConfig();
        TrackConstraintService svc = new TrackConstraintService(cfg);
        LineProfile lp = flatLine(5000);
        double prev = -1;
        for (int i = 0; i <= 10; i++) {
            double load = i / 10.0;
            TrainState t = trainAt(100, 64.8);
            t.setLoadFactor(load);
            double g = svc.requiredGap(t, null, lp);
            System.out.println("  load=" + load + " gap=" + g);
            if (i > 0) {
                check(g >= prev - 1e-9, "load=" + load + " gap >= prev (" + g + " >= " + prev + ")");
            }
            prev = g;
        }
    }

    /** effectiveBrakeDecel：未知=额定，load=1<额定 */
    static void checkEffectiveBrakeDecel() {
        System.out.println("A4-5 effectiveBrakeDecel:");
        MaConfig cfg = MaConfig.exampleConfig();
        TrackConstraintService svc = new TrackConstraintService(cfg);
        double aNaN = svc.effectiveBrakeDecel(Double.NaN);
        double a0 = svc.effectiveBrakeDecel(0.0);
        double a1 = svc.effectiveBrakeDecel(1.0);
        System.out.println("  a(NaN)=" + aNaN + " a(0)=" + a0 + " a(1)=" + a1);
        close(aNaN, cfg.aBrakeMps2, 1e-9, "未知=额定 aBrake");
        close(a0, cfg.aBrakeMps2, 1e-9, "load=0=额定 aBrake");
        check(a1 < cfg.aBrakeMps2, "load=1 < 额定（折减生效）");
        double expected1 = cfg.aBrakeMps2 * (1 - cfg.loadFactorK * 1.0);
        close(a1, expected1, 1e-9, "load=1 = aBrake*(1-k)");
    }

    /** 负值 load 按额定处理（不崩溃） */
    static void checkNegativeLoad() {
        System.out.println("A4-6 负值 load 按额定处理:");
        MaConfig cfg = MaConfig.exampleConfig();
        TrackConstraintService svc = new TrackConstraintService(cfg);
        double aNeg = svc.effectiveBrakeDecel(-0.5);
        close(aNeg, cfg.aBrakeMps2, 1e-9, "load<0 → 额定 aBrake");
    }

    /** load>1 封顶 1.0（不爆炸） */
    static void checkOverflowLoad() {
        System.out.println("A4-7 load>1 封顶 1.0:");
        MaConfig cfg = MaConfig.exampleConfig();
        TrackConstraintService svc = new TrackConstraintService(cfg);
        double aOver = svc.effectiveBrakeDecel(1.5);
        double a1 = svc.effectiveBrakeDecel(1.0);
        close(aOver, a1, 1e-9, "load=1.5 == load=1.0（封顶）");
        check(aOver > 0, "折减后仍 > 0");
    }

    // ---- helpers ----

    static LineProfile flatLine(double len) {
        LineProfile lp = new LineProfile();
        lp.setLineId("A4");
        lp.setTotalLengthM(len);
        return lp;
    }

    static TrainState trainAt(double pos, double speedKmh) {
        TrainState t = new TrainState();
        t.setTrainId("T");
        t.setPositionM(pos);
        t.setSpeedKmh(speedKmh);
        t.setAccelerationMps2(0);
        t.setLengthM(140);
        t.setDirection(Direction.UP);
        t.setTimestamp(0);
        return t;
    }

    static void check(boolean c, String msg) {
        if (c) { pass++; System.out.println("  OK " + msg); }
        else { fail++; System.out.println("  FAIL " + msg); }
    }

    static void close(double a, double b, double eps, String msg) {
        check(Math.abs(a - b) <= eps, msg + " (" + a + " vs " + b + ")");
    }
}
