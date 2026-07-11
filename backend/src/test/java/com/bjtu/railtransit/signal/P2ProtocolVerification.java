package com.bjtu.railtransit.signal;

import com.bjtu.railtransit.signal.domain.AuthorityBasis;
import com.bjtu.railtransit.signal.domain.Direction;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.bjtu.railtransit.signal.domain.SignalEvent;
import com.bjtu.railtransit.signal.domain.TrainState;
import com.bjtu.railtransit.signal.model.LineProfile;
import com.bjtu.railtransit.signal.model.Switch;
import com.bjtu.railtransit.signal.model.SwitchState;
import com.bjtu.railtransit.signal.service.MaConfig;
import com.bjtu.railtransit.signal.service.MovingAuthorityService;
import com.bjtu.railtransit.signal.service.TrackConstraintService;

import java.util.Collections;
import java.util.HashMap;

/** 阶段 E / P2：故障限速、定位/完整性丢失、道岔 FAIL、maValiditySec=1.6 */
public class P2ProtocolVerification {
    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        checkFaultSpeed();
        checkPositionLoss();
        checkIntegrityLoss();
        checkSwitchFail();
        checkMaValidity();
        checkSwitchCodec();
        System.out.println("P2 done: pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static void checkFaultSpeed() {
        System.out.println("A6 faultSpeedLimitKmh:");
        MaConfig cfg = MaConfig.exampleConfig();
        MovingAuthorityService svc = new MovingAuthorityService(cfg);
        LineProfile lp = flatLine(5000);
        TrainState t = train("T1", 100, 60, Direction.UP, 0);
        t.setFaultSpeedLimitKmh(30.0);
        MovingAuthority m = svc.compute(lp, Collections.singletonList(t)).get("T1");
        check(m.getMaxSpeedKmh() <= 30.0 + 1e-6, "maxSpeed <= 30 (fault)");
        check(m.getMaxSpeedKmh() < cfg.defaultLineSpeedKmh - 1e-6, "tighter than default 80");
        check(m.getEvent() == SignalEvent.SPEED_RESTRICTION, "event = SPEED_RESTRICTION");
    }

    static void checkPositionLoss() {
        System.out.println("A9 positionLost:");
        MaConfig cfg = MaConfig.exampleConfig();
        MovingAuthorityService svc = new MovingAuthorityService(cfg);
        LineProfile lp = flatLine(5000);
        TrainState t = train("T1", 500, 40, Direction.UP, 10);
        t.setPositionLost(true);
        MovingAuthority m = svc.compute(lp, Collections.singletonList(t)).get("T1");
        close(m.getEndOfAuthorityM(), 500, 1e-6, "EoA = current pos");
        check(m.getEvent() == SignalEvent.POSITION_LOSS, "event = POSITION_LOSS");
        close(m.getMaxSpeedKmh(), cfg.degradedSpeedKmh, 1e-6, "maxSpeed degraded");
    }

    static void checkIntegrityLoss() {
        System.out.println("A10 integrityLost:");
        MaConfig cfg = MaConfig.exampleConfig();
        MovingAuthorityService svc = new MovingAuthorityService(cfg);
        LineProfile lp = flatLine(5000);
        TrainState t = train("T1", 800, 40, Direction.UP, 10);
        t.setIntegrityLost(true);
        MovingAuthority m = svc.compute(lp, Collections.singletonList(t)).get("T1");
        close(m.getEndOfAuthorityM(), 800, 1e-6, "EoA = current pos");
        check(m.getEvent() == SignalEvent.POSITION_LOSS, "event = POSITION_LOSS");
    }

    static void checkSwitchFail() {
        System.out.println("B2 SwitchState.FAIL:");
        MaConfig cfg = MaConfig.exampleConfig();
        TrackConstraintService tc = new TrackConstraintService(cfg);
        LineProfile lp = flatLine(5000);
        Switch sw = new Switch();
        sw.setId("9");
        sw.setPositionM(1200);
        sw.setState(SwitchState.FAIL);
        sw.setMergeSegId(1);
        sw.setDivergingSpeedLimitKmh(35);
        lp.setSwitches(Collections.singletonList(sw));
        TrainState t = train("T1", 100, 50, Direction.UP, 0);
        double eoa = tc.eoaFromSwitch(lp, t);
        close(eoa, 1200, 1e-6, "FAIL switch caps EoA at switch");
        MovingAuthorityService svc = new MovingAuthorityService(cfg);
        MovingAuthority m = svc.compute(lp, Collections.singletonList(t)).get("T1");
        check(m.getBasis() == AuthorityBasis.SWITCH, "basis = SWITCH");
        check(m.getEvent() == SignalEvent.SWITCH_ABNORMAL, "event = SWITCH_ABNORMAL");
    }

    static void checkMaValidity() {
        System.out.println("D3 maValiditySec=1.6:");
        MaConfig cfg = MaConfig.exampleConfig();
        check(Math.abs(cfg.maValiditySec - 1.6) < 1e-9, "default maValiditySec == 1.6");
        MovingAuthorityService svc = new MovingAuthorityService(cfg);
        LineProfile lp = flatLine(2000);
        TrainState ok = train("T2", 0, 64.8, Direction.UP, 0);
        MovingAuthority mOk = svc.compute(lp, Collections.singletonList(ok), new HashMap<>(), 1.5).get("T2");
        check(mOk.getEvent() != SignalEvent.MA_EXPIRED, "age 1.5s not expired");
        MovingAuthority mExp = svc.compute(lp, Collections.singletonList(ok), new HashMap<>(), 2.0).get("T2");
        check(mExp.getEvent() == SignalEvent.MA_EXPIRED, "age 2.0s -> MA_EXPIRED");
        close(mExp.getMaxSpeedKmh(), cfg.degradedSpeedKmh, 1e-6, "expired maxSpeed degraded");
    }

    static void checkSwitchCodec() {
        System.out.println("B2 protocol codec:");
        check(SwitchState.fromProtocol(0x01) == SwitchState.NORMAL, "0x01 -> NORMAL");
        check(SwitchState.fromProtocol(0x02) == SwitchState.REVERSE, "0x02 -> REVERSE");
        check(SwitchState.fromProtocol(0x04) == SwitchState.FAIL, "0x04 -> FAIL");
        check(SwitchState.fromProtocol(0x00) == null, "0x00 -> null");
        check(SwitchState.FAIL.toProtocol() == 0x04, "FAIL -> 0x04");
    }

    static LineProfile flatLine(double len) {
        LineProfile lp = new LineProfile();
        lp.setLineId("P2");
        lp.setTotalLengthM(len);
        return lp;
    }

    static TrainState train(String id, double pos, double v, Direction d, double ts) {
        TrainState t = new TrainState();
        t.setTrainId(id);
        t.setPositionM(pos);
        t.setSpeedKmh(v);
        t.setLengthM(140);
        t.setDirection(d);
        t.setTimestamp(ts);
        t.setAccelerationMps2(0);
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
