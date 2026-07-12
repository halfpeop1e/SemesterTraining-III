package com.bjtu.railtransit.signal;

import com.bjtu.railtransit.signal.model.LineProfile;
import com.bjtu.railtransit.signal.model.TemporarySpeedRestriction;
import com.bjtu.railtransit.signal.service.LineProfileLoader;
import com.bjtu.railtransit.signal.service.MovingAuthorityService;
import com.bjtu.railtransit.signal.service.MovementAuthorityRegistry;
import com.bjtu.railtransit.signal.service.MaConfig;
import com.bjtu.railtransit.signal.service.SignalCycleService;
import com.bjtu.railtransit.signal.service.SignalInterlockingService;
import com.bjtu.railtransit.signal.service.TsrService;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.bjtu.railtransit.signal.domain.AuthorityBasis;
import com.bjtu.railtransit.signal.domain.SignalEvent;

import java.util.List;
import java.util.Map;

/**
 * G3 验证：TSR REST 后端逻辑 + compute 消费 TSR。
 * 用 TsrService 直接调用（绕过 HTTP），验证：
 * 1. createTsr → TSR 有 id
 * 2. getAllTsrs → 列表含新建
 * 3. cancelTsr → 列表减少
 * 4. compute 消费 TSR → maxSpeedKmh 降为 TSR 值
 * 5. cancelTsr 不存在 → 返回 null
 * 6. createTsr 参数校验（endM <= startM 拒绝）
 */
public class G3TsrVerification {

    static int pass = 0;
    static int fail = 0;

    public static void main(String[] args) throws Exception {
        LineProfileLoader loader = new LineProfileLoader();
        LineProfile lp = loader.getLineProfile();

        // TsrService 直接测试
        TsrService tsrService = new TsrService(loader);

        // 1. createTsr — TSR 覆盖列车 EoA 可能落入的范围
        TemporarySpeedRestriction tsr = tsrService.createTsr(14000, 48000, 40, true);
        check(tsr != null, "createTsr 返回非 null");
        check(tsr.getId() != null && !tsr.getId().isEmpty(), "TSR 有 id: " + tsr.getId());
        check(tsr.getSpeedLimitKmh() == 40, "TSR 限速 = 40");
        check(tsr.isActive(), "TSR active = true");

        // 2. getAllTsrs
        List<TemporarySpeedRestriction> list1 = tsrService.getAllTsrs();
        int countBefore = list1.size();
        check(list1.stream().anyMatch(t -> tsr.getId().equals(t.getId())), "getAllTsrs 含新建 TSR");

        // 3. compute 消费 TSR：在 TSR 范围内的车应限速 40
        MaConfig config = MaConfig.exampleConfig();
        MovingAuthorityService maService = new MovingAuthorityService(config);
        MovementAuthorityRegistry registry = new MovementAuthorityRegistry(null);
        SignalInterlockingService interlocking = new SignalInterlockingService(loader);
        SignalCycleService cycle = new SignalCycleService(maService, registry, loader, interlocking, new com.bjtu.railtransit.signal.service.SignalEventLog(), true);

        com.bjtu.railtransit.domain.model.TrainState train = new com.bjtu.railtransit.domain.model.TrainState();
        train.setTrainId("G3_TEST");
        train.setPositionMeters(1500); // 在 TSR 范围内
        train.setSpeed(80);
        train.setDirection("UP");
        train.setTrainLengthMeters(140);
        train.setStatus("DWELLING");

        Map<String, MovingAuthority> maMap = cycle.runCycle(List.of(train), 10);
        MovingAuthority ma = maMap.get("G3_TEST");
        check(ma != null, "runCycle 返回 MA");
        System.out.println("  maxSpeedKmh = " + ma.getMaxSpeedKmh() + " (TSR=40, 默认=80)");
        check(ma.getMaxSpeedKmh() <= 40, "TSR 生效：maxSpeedKmh <= 40 (实际=" + ma.getMaxSpeedKmh() + ")");

        // 4. cancelTsr
        TemporarySpeedRestriction cancelled = tsrService.cancelTsr(tsr.getId());
        check(cancelled != null, "cancelTsr 返回被取消的 TSR");
        check(tsrService.getAllTsrs().size() == countBefore - 1, "cancelTsr 后列表减少");

        // 5. cancelTsr 不存在
        TemporarySpeedRestriction none = tsrService.cancelTsr("TSR-NONEXIST");
        check(none == null, "cancelTsr 不存在 → null");

        // 6. createTsr 参数校验
        try {
            tsrService.createTsr(2000, 1000, 40, true); // endM < startM
            check(false, "createTsr endM<startM 应拒绝");
        } catch (IllegalArgumentException e) {
            check(true, "createTsr endM<startM 拒绝: " + e.getMessage());
        }

        // 7. cancelTsr 后 compute 恢复
        Map<String, MovingAuthority> maMap2 = cycle.runCycle(List.of(train), 11);
        MovingAuthority ma2 = maMap2.get("G3_TEST");
        check(ma2.getMaxSpeedKmh() > 40, "取消 TSR 后 maxSpeed 恢复 > 40 (实际=" + ma2.getMaxSpeedKmh() + ")");

        System.out.println("\n===== G3TsrVerification =====");
        System.out.println("PASS: " + pass + " / " + (pass + fail));
        if (fail > 0) {
            System.out.println("FAIL: " + fail);
            System.exit(1);
        }
        System.out.println("ALL PASS");
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
