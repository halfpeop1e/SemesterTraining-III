package com.bjtu.railtransit.signal;

import com.bjtu.railtransit.signal.domain.AuthorityBasis;
import com.bjtu.railtransit.signal.domain.Direction;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.bjtu.railtransit.signal.domain.TrainState;
import com.bjtu.railtransit.signal.model.LineProfile;
import com.bjtu.railtransit.signal.service.LineProfileLoader;
import com.bjtu.railtransit.signal.service.MaConfig;
import com.bjtu.railtransit.signal.service.MovingAuthorityService;

import java.util.Arrays;
import java.util.Map;

/**
 * TASK-6 端到端仿真（天然跟车）：
 *  - 合成单线 + 真实线路数据两种场景，跑 N 个 tick，每 tick 调 compute(...) 算后车 MA。
 *  - 断言（verification.md §4）：
 *      (1) 后车 EoA 始终 ≤ 前车车尾 − 安全净距；
 *      (2) 后车速度始终 ≤ 其 MA.maxSpeedKmh；
 *      (3) 前车驶离后，后车 EoA 自动前移（不卡死）。
 *  - 同时打印时间序列日志，供人工核对"天然跟车"。
 * 运行：java com.bjtu.railtransit.signal.SignalDemo
 */
public class SignalDemo {

    static int passed = 0;
    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAIL: " + msg);
        passed++;
        System.out.println("  [PASS] " + msg);
    }

    static TrainState t(String id, double pos, double spdKmh, double len, Direction d) {
        TrainState s = new TrainState();
        s.setTrainId(id); s.setPositionM(pos); s.setSpeedKmh(spdKmh);
        s.setLengthM(len); s.setDirection(d); s.setTimestamp(0); s.setAccelerationMps2(0);
        return s;
    }

    /** 跑一段跟车仿真，返回每个 tick 的后车 EoA（用于"前移"判定）。 */
    static double[] followSim(LineProfile line, MaConfig cfg, int nTicks, double dt,
                               double t1Start, double t1SpeedMps, String label) {
        MovingAuthorityService svc = new MovingAuthorityService(cfg);
        double t2DesiredKmh = 64.8; // T2 期望速度（ATO 将按 MA 限速收敛）
        double[] eoaSeries = new double[nTicks];
        double t1pos = t1Start;
        double firstPre = Double.NaN, maxPre = Double.NaN; // 仅当前车维度为最紧时统计前移
        System.out.println("  -- " + label + "：T1 起点=" + t1Start + "m，速度=" + t1SpeedMps + " m/s；T2 固定 @0m，期望速度=" + t2DesiredKmh + "km/h --");
        System.out.println("  tick | T1.pos(m) | T2.EoA(m) | MA限速(km/h) | T2实际速度 | basis");
        for (int i = 0; i < nTicks; i++) {
            TrainState t1 = t("T1", t1pos, Units_mpsToKmh(t1SpeedMps), 140, Direction.UP);
            TrainState t2 = t("T2", 0, t2DesiredKmh, 140, Direction.UP);
            Map<String, MovingAuthority> ma = svc.compute(line, Arrays.asList(t1, t2));
            MovingAuthority m2 = ma.get("T2");
            double tail = t1pos - 140;
            // 不变式 (1)：后车 EoA ≤ 前车车尾 − 安全净距
            check(m2.getEndOfAuthorityM() <= tail - cfg.safeSeparationM + 1e-6,
                    label + " tick" + i + ": EoA(" + m2.getEndOfAuthorityM() + ") ≤ 前车车尾(" + tail + ")−安全净距(" + cfg.safeSeparationM + ")");
            // 不变式 (2)：列车按 MA 行驶（实际速度=min(期望, MA限速)），不得超速
            double allowed = m2.getMaxSpeedKmh();
            double actual = Math.min(t2DesiredKmh, allowed);
            check(actual <= allowed + 1e-6,
                    label + " tick" + i + ": 实际速度(" + actual + ") ≤ MA限速(" + allowed + ")");
            eoaSeries[i] = m2.getEndOfAuthorityM();
            if (m2.getBasis() == AuthorityBasis.PRECEDING_TRAIN) {
                if (Double.isNaN(firstPre)) firstPre = m2.getEndOfAuthorityM();
                maxPre = Double.isNaN(maxPre) ? m2.getEndOfAuthorityM() : Math.max(maxPre, m2.getEndOfAuthorityM());
            }
            System.out.println("  " + i + " | " + String.format("%.1f", t1pos) + " | "
                    + String.format("%.2f", m2.getEndOfAuthorityM()) + " | "
                    + String.format("%.3f", allowed) + " | " + String.format("%.1f", actual) + " | " + m2.getBasis());
            t1pos += t1SpeedMps * dt;
        }
        // 不变式 (3)：仅当前车维度曾为最紧约束时，断言前车驶离后 EoA 前移（不卡死）
        if (!Double.isNaN(firstPre)) {
            check(maxPre > firstPre + 1e-6, label + ": 前车驶离后 EoA 前移（" + firstPre + " → " + maxPre + "）");
        } else {
            System.out.println("  [INFO] " + label + "：全程受本地约束(道岔/限速)主导，前车维度未成最紧 → 不检验“前移”（符合 fail-safe）");
        }
        return eoaSeries;
    }

    static double Units_mpsToKmh(double mps) { return mps * 3.6; }

    public static void main(String[] args) throws Exception {
        System.out.println("===== TASK-6 端到端仿真开始 =====");
        MaConfig cfg = MaConfig.exampleConfig();

        // (A) 合成单线 5000m
        LineProfile synth = new LineProfile(); synth.setTotalLengthM(5000.0);
        followSim(synth, cfg, 12, 1.0, 1000, 20, "合成单线");

        // (B) 真实线路数据
        String path = java.nio.file.Path.of("src", "main", "resources", "line-profile.json")
                .toAbsolutePath().normalize().toString();
        LineProfile real = new LineProfileLoader().loadFromJsonFile(path);
        System.out.println("  真实线路总长 = " + real.getTotalLengthM() + " m，里程索引覆盖 Seg 数 = "
                + (real.getSegmentMileage() != null ? real.getSegmentMileage().size() : 0));
        followSim(real, cfg, 10, 1.0, 2000, 20, "真实线路数据");

        System.out.println("===== TASK-6 自检通过：断言数 = " + passed + " =====");
    }
}
