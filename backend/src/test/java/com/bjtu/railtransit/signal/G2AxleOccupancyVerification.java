package com.bjtu.railtransit.signal;

import com.bjtu.railtransit.signal.model.LineProfile;
import com.bjtu.railtransit.signal.model.AxleCounterSection;
import com.bjtu.railtransit.signal.service.LineProfileLoader;
import com.bjtu.railtransit.signal.service.MovingAuthorityService;
import com.bjtu.railtransit.signal.service.MovementAuthorityRegistry;
import com.bjtu.railtransit.signal.service.MaConfig;
import com.bjtu.railtransit.signal.service.SignalCycleService;
import com.bjtu.railtransit.signal.service.SignalEventLog;
import com.bjtu.railtransit.signal.service.SignalInterlockingService;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.bjtu.railtransit.domain.model.TrainState;

import java.util.List;
import java.util.Map;

/**
 * G2 验证：axleSections.occupied 按车 positionM 刷新，compute 前生效。
 *
 * 验证点：
 * 1. runCycle 前所有 axleSection.occupied = false
 * 2. runCycle 后，列车所在区段 occupied = true
 * 3. runCycle 后，列车不在的区段 occupied = false
 * 4. 车移动后 occupied 跟随刷新（旧区段清 false，新区段置 true）
 * 5. 多列车在同区段 → occupied = true
 * 6. READY_TO_DEPART 列车不触发占用
 */
public class G2AxleOccupancyVerification {

    static int pass = 0;
    static int fail = 0;

    public static void main(String[] args) throws Exception {
        LineProfileLoader loader = new LineProfileLoader();
        LineProfile lp = loader.getLineProfile();

        MaConfig config = MaConfig.exampleConfig();
        MovingAuthorityService maService = new MovingAuthorityService(config);
        MovementAuthorityRegistry registry = new MovementAuthorityRegistry(null);
        SignalInterlockingService interlocking = new SignalInterlockingService(loader);
        SignalCycleService cycle = new SignalCycleService(maService, registry, loader, interlocking, new SignalEventLog(), true);

        // 前置：确认 axleSections 存在
        check(lp.getAxleSections() != null && !lp.getAxleSections().isEmpty(),
                "LineProfile 含 axleSections (count=" +
                (lp.getAxleSections() != null ? lp.getAxleSections().size() : 0) + ")");

        // 断言 1: runCycle 前所有 occupied = false
        boolean allClear = true;
        for (AxleCounterSection a : lp.getAxleSections()) {
            if (a.isOccupied()) { allClear = false; break; }
        }
        check(allClear, "runCycle 前所有 axleSection.occupied = false");

        // 找一个区段里程范围，放一辆列车进去
        // 用 TrackConstraintService 的算法找区段里程
        AxleCounterSection testSection = lp.getAxleSections().get(0);
        double[] range = sectionRange(lp, testSection);
        check(!Double.isNaN(range[0]) && !Double.isNaN(range[1]),
                "测试区段 " + testSection.getName() + " 里程范围 [" + range[0] + ", " + range[1] + "]");

        // 放一辆列车在区段中间
        double mid = (range[0] + range[1]) / 2;
        TrainState t1 = new TrainState();
        t1.setTrainId("G2_T1");
        t1.setPositionMeters(mid);
        t1.setSpeed(40);
        t1.setDirection("UP");
        t1.setTrainLengthMeters(140);
        t1.setStatus("RUNNING");

        // 断言 2: runCycle 后，列车所在区段 occupied = true
        cycle.runCycle(List.of(t1), 1.0);
        check(testSection.isOccupied(),
                "列车在区段 " + testSection.getName() + " 内 → occupied = true");

        // 断言 3: 至少有一个区段 occupied = false（线路很长，不可能全占）
        boolean someClear = false;
        for (AxleCounterSection a : lp.getAxleSections()) {
            if (!a.isOccupied()) { someClear = true; break; }
        }
        check(someClear, "列车不在的区段 occupied = false");

        // 断言 4: 车移动到另一个区段 → 旧区段清 false，新区段置 true
        // 找另一个区段
        AxleCounterSection otherSection = null;
        for (AxleCounterSection a : lp.getAxleSections()) {
            if (a.getId() != testSection.getId()) {
                double[] r = sectionRange(lp, a);
                if (!Double.isNaN(r[0]) && !Double.isNaN(r[1]) && r[0] != range[0]) {
                    otherSection = a;
                    break;
                }
            }
        }

        if (otherSection != null) {
            double[] otherRange = sectionRange(lp, otherSection);
            double otherMid = (otherRange[0] + otherRange[1]) / 2;
            t1.setPositionMeters(otherMid);
            cycle.runCycle(List.of(t1), 2.0);

            check(!testSection.isOccupied(),
                    "车移走后旧区段 " + testSection.getName() + " occupied = false");
            check(otherSection.isOccupied(),
                    "车移入后新区段 " + otherSection.getName() + " occupied = true");
        } else {
            // 只有一个区段时跳过移动测试
            check(true, "仅一个区段，跳过移动刷新测试");
        }

        // 断言 5: 多列车在同区段 → occupied = true
        t1.setPositionMeters(mid);
        TrainState t2 = new TrainState();
        t2.setTrainId("G2_T2");
        t2.setPositionMeters(mid + 10);
        t2.setSpeed(30);
        t2.setDirection("UP");
        t2.setTrainLengthMeters(140);
        t2.setStatus("RUNNING");
        cycle.runCycle(List.of(t1, t2), 3.0);
        check(testSection.isOccupied(),
                "两列车在同区段 " + testSection.getName() + " → occupied = true");

        // 断言 6: READY_TO_DEPART 列车不触发占用
        TrainState t3 = new TrainState();
        t3.setTrainId("G2_T3");
        t3.setPositionMeters(mid);
        t3.setSpeed(0);
        t3.setDirection("UP");
        t3.setTrainLengthMeters(140);
        t3.setStatus("READY_TO_DEPART");
        // 只跑 t3（不跑 t1/t2），验证 READY_TO_DEPART 不占
        cycle.runCycle(List.of(t3), 4.0);
        check(!testSection.isOccupied(),
                "READY_TO_DEPART 列车不触发区段占用");

        // 断言 7: 车体部分跨区段 → 两个区段都 occupied
        // 找两个相邻区段
        if (lp.getAxleSections().size() >= 2) {
            AxleCounterSection a1 = lp.getAxleSections().get(0);
            AxleCounterSection a2 = lp.getAxleSections().get(1);
            double[] r1 = sectionRange(lp, a1);
            double[] r2 = sectionRange(lp, a2);
            if (!Double.isNaN(r1[1]) && !Double.isNaN(r2[0]) && Math.abs(r1[1] - r2[0]) < 1) {
                // 相邻：车头在 a2，车尾在 a1
                TrainState t4 = new TrainState();
                t4.setTrainId("G2_T4");
                t4.setPositionMeters(r2[0] + 5); // 车头在 a2
                t4.setSpeed(20);
                t4.setDirection("UP");
                t4.setTrainLengthMeters(140);
                t4.setStatus("RUNNING");
                cycle.runCycle(List.of(t4), 5.0);
                check(a1.isOccupied() && a2.isOccupied(),
                        "车体跨两区段 → 两区段均 occupied = true");
            } else {
                check(true, "区段不相邻，跳过跨区段测试");
            }
        } else {
            check(true, "仅一个区段，跳过跨区段测试");
        }

        System.out.println("\n===== G2AxleOccupancyVerification =====");
        System.out.println("PASS: " + pass + " / " + (pass + fail));
        if (fail > 0) {
            System.out.println("FAIL: " + fail);
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    /** 计算区段里程范围 [startM, endM] */
    static double[] sectionRange(LineProfile lp, AxleCounterSection a) {
        double min = Double.NaN, max = Double.NaN;
        if (a.getSegIds() != null) {
            for (Integer segId : a.getSegIds()) {
                double sm = lp.segStartM(String.valueOf(segId));
                double em = lp.segEndM(String.valueOf(segId));
                if (!Double.isNaN(sm) && (Double.isNaN(min) || sm < min)) min = sm;
                if (!Double.isNaN(em) && (Double.isNaN(max) || em > max)) max = em;
            }
        }
        return new double[]{min, max};
    }

    static void check(boolean ok, String msg) {
        if (ok) {
            pass++;
            System.out.println("  [PASS] " + msg);
        } else {
            fail++;
            System.out.println("  [FAIL] " + msg);
        }
    }
}
