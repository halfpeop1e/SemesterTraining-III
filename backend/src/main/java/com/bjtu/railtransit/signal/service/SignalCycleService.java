package com.bjtu.railtransit.signal.service;

import com.bjtu.railtransit.domain.model.TrainState;
import com.bjtu.railtransit.signal.domain.Direction;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.bjtu.railtransit.signal.domain.SignalEvent;
import com.bjtu.railtransit.signal.model.AxleCounterSection;
import com.bjtu.railtransit.signal.model.LineProfile;
import com.bjtu.railtransit.signal.model.Route;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

/**
 * Backend-owned signal cycle:
 * dispatch/onboard state -> signal domain -> full teacher-data MA -> ATP constraints.
 * 每周期集成联锁进路生命周期、信号保持和列车占用通知。
 */
@Service
public class SignalCycleService {
    private static final double SERVICE_BRAKE_MPS2 = 1.0;
    private static final double EMERGENCY_BRAKE_MPS2 = 1.3;
    private static final double STOP_MARGIN_METERS = 5.0;

    private final MovingAuthorityService movingAuthorityService;
    private final MovementAuthorityRegistry registry;
    private final LineProfile lineProfile;
    private final boolean simulatedInterlocking;
    private final SignalInterlockingService interlockingService;
    private final SignalEventLog eventLog;
    private final Map<String, Route> activeRoutes = new java.util.concurrent.ConcurrentHashMap<>();

    public SignalCycleService(MovingAuthorityService movingAuthorityService,
                              MovementAuthorityRegistry registry,
                              LineProfileLoader loader,
                              SignalInterlockingService interlockingService,
                              SignalEventLog eventLog,
                              @Value("${rail.signal.simulated-interlocking:true}") boolean simulatedInterlocking)
            throws IOException {
        this.movingAuthorityService = movingAuthorityService;
        this.registry = registry;
        this.interlockingService = interlockingService;
        this.eventLog = eventLog;
        this.lineProfile = loader.getLineProfile();
        this.simulatedInterlocking = simulatedInterlocking;
    }

    public synchronized Map<String, MovingAuthority> runCycle(
            Iterable<TrainState> runtimeTrains, double nowSeconds) {

        // Step 0: 推进联锁进路生命周期（道岔转换计时 → 锁闭 → 开放信号）
        interlockingService.processRouteTransitions(nowSeconds);

        // G1: 从联锁同步 trainId→Route 绑定到 activeRoutes（MA 只读）
        Map<String, Integer> bindings = interlockingService.getRouteBindings();
        activeRoutes.keySet().removeIf(tid -> !bindings.containsKey(tid));
        for (Map.Entry<String, Integer> entry : bindings.entrySet()) {
            String trainId = entry.getKey();
            int routeId = entry.getValue();
            Route route = interlockingService.getBuiltRoutes().stream()
                    .filter(r -> r.getId() == routeId)
                    .findFirst().orElse(null);
            if (route != null) {
                activeRoutes.put(trainId, route);
            } else {
                activeRoutes.remove(trainId);
            }
        }

        // G2: 按车 positionM 刷新 axleSections.occupied（记录新旧占用状态）
        Map<Integer, Boolean> prevOccupancy = snapshotOccupancy();
        refreshAxleOccupancy(runtimeTrains);
        Map<Integer, Boolean> newOccupancy = snapshotOccupancy();

        // G2.5: 通知联锁区段占用/出清变化
        notifyOccupancyChanges(prevOccupancy, newOccupancy);
        // 接近锁闭检查
        interlockingService.checkApproachLocking();
        // 信号保持检查
        interlockingService.signalHoldingCheck();

        // 转换到 signal 域
        List<com.bjtu.railtransit.signal.domain.TrainState> signalTrains = new ArrayList<>();
        for (TrainState runtime : runtimeTrains) {
            if (!runtime.occupiesTrack()) continue;
            Direction direction;
            try {
                direction = Direction.valueOf(runtime.getDirection());
            } catch (Exception ignored) {
                direction = Direction.INVALID;
            }
            com.bjtu.railtransit.signal.domain.TrainState signalTrain =
                    TrainStateAdapter.fromAbsolute(
                            runtime.getTrainId(),
                            runtime.getPositionMeters(),
                            runtime.getSpeedKmh(),
                            direction,
                            runtime.getTrainLengthMeters(),
                            nowSeconds,
                            runtime.getFaultSpeedLimitKmh(),
                            runtime.isPositionLost(),
                            runtime.isIntegrityLost(),
                            runtime.getLoadFactor());
            signalTrain.setAccelerationMps2(runtime.getAccelerationMps2());
            signalTrains.add(signalTrain);
        }

        Map<String, MovingAuthority> values = movingAuthorityService.compute(
                lineProfile, signalTrains, activeRoutes, nowSeconds);
        registry.replace(values, nowSeconds,
                simulatedInterlocking ? "SIMULATED_INTERLOCKING" : "LAB_INTERLOCKING");

        for (TrainState runtime : runtimeTrains) {
            MovingAuthority ma = values.get(runtime.getTrainId());
            if (ma != null) {
                applyAtpConstraint(runtime, ma);
                if (eventLog != null && ma.getEvent() != null) {
                    SignalEvent ev = ma.getEvent();
                    if (ev == SignalEvent.DEGRADED || ev == SignalEvent.MA_EXPIRED
                            || ev == SignalEvent.POSITION_LOSS) {
                        String level = (ev == SignalEvent.MA_EXPIRED) ? "ERROR" : "WARN";
                        eventLog.addThrottled(level, "MA",
                                "列车 " + runtime.getTrainId() + " MA 降级: " + ev,
                                runtime.getTrainId(),
                                runtime.getTrainId() + "|" + ev.name());
                    }
                }
            }
        }
        return values;
    }

    private void applyAtpConstraint(TrainState train, MovingAuthority ma) {
        train.setMovementAuthority(ma.getEndOfAuthorityM());
        train.setMaxSpeedLimit(Math.max(0, ma.getMaxSpeedKmh()));

        int sign = train.getDirectionSign();
        double remaining = (ma.getEndOfAuthorityM() - train.getPositionMeters()) * sign;
        double speedMps = train.getSpeedMps();
        double emergencyDistance = speedMps * speedMps / (2 * EMERGENCY_BRAKE_MPS2);

        boolean degraded = ma.getEvent() == SignalEvent.DEGRADED
                || ma.getEvent() == SignalEvent.MA_EXPIRED;
        if (degraded || remaining <= STOP_MARGIN_METERS
                || (speedMps > 0.1 && remaining <= emergencyDistance + STOP_MARGIN_METERS)) {
            train.setEmergencyBraking(true);
            train.setMaxSpeedLimit(0);
            train.setActiveCommand("ATP_EMERGENCY_BRAKE");
            return;
        }

        double supervisedSpeedKmh = Math.sqrt(
                Math.max(0, 2 * SERVICE_BRAKE_MPS2 * (remaining - STOP_MARGIN_METERS))) * 3.6;
        train.setMaxSpeedLimit(Math.min(train.getMaxSpeedLimit(), supervisedSpeedKmh));
        if (train.isEmergencyBraking() && remaining > emergencyDistance + 20) {
            train.setEmergencyBraking(false);
        }
    }

    private Map<Integer, Boolean> snapshotOccupancy() {
        Map<Integer, Boolean> map = new LinkedHashMap<>();
        if (lineProfile.getAxleSections() != null) {
            for (AxleCounterSection a : lineProfile.getAxleSections()) {
                map.put(a.getId(), a.isOccupied());
            }
        }
        return map;
    }

    private void notifyOccupancyChanges(Map<Integer, Boolean> prev, Map<Integer, Boolean> next) {
        for (Map.Entry<Integer, Boolean> e : next.entrySet()) {
            int id = e.getKey();
            boolean was = prev.getOrDefault(id, false);
            boolean now = e.getValue();
            if (!was && now) {
                interlockingService.notifySectionOccupied(id);
            } else if (was && !now) {
                interlockingService.notifySectionCleared(id);
            }
        }
    }

    public LineProfile getLineProfile() { return lineProfile; }
    public boolean isSimulatedInterlocking() { return simulatedInterlocking; }
    public Map<String, Route> getActiveRoutes() { return Collections.unmodifiableMap(activeRoutes); }

    private void refreshAxleOccupancy(Iterable<TrainState> runtimeTrains) {
        if (lineProfile.getAxleSections() == null || lineProfile.getAxleSections().isEmpty())
            return;
        for (AxleCounterSection a : lineProfile.getAxleSections()) {
            a.setOccupied(false);
        }
        for (TrainState t : runtimeTrains) {
            if (!t.occupiesTrack()) continue;
            double head = t.getPositionMeters();
            double tail = head - t.getTrainLengthMeters();
            double lo = Math.min(head, tail);
            double hi = Math.max(head, tail);
            for (AxleCounterSection a : lineProfile.getAxleSections()) {
                double aStart = axleSectionStartM(a);
                double aEnd = axleSectionEndM(a);
                if (Double.isNaN(aStart) || Double.isNaN(aEnd)) continue;
                if (hi >= aStart && lo <= aEnd) {
                    a.setOccupied(true);
                }
            }
        }
    }

    private double axleSectionStartM(AxleCounterSection a) {
        if (a.getSegIds() == null) return Double.NaN;
        double min = Double.NaN;
        for (Integer segId : a.getSegIds()) {
            double sm = lineProfile.segStartM(String.valueOf(segId));
            if (!Double.isNaN(sm) && (Double.isNaN(min) || sm < min)) min = sm;
        }
        return min;
    }

    private double axleSectionEndM(AxleCounterSection a) {
        if (a.getSegIds() == null) return Double.NaN;
        double max = Double.NaN;
        for (Integer segId : a.getSegIds()) {
            double em = lineProfile.segEndM(String.valueOf(segId));
            if (!Double.isNaN(em) && (Double.isNaN(max) || em > max)) max = em;
        }
        return max;
    }

    public void assignRoute(String trainId, Route route) {
        if (route == null) activeRoutes.remove(trainId);
        else activeRoutes.put(trainId, route);
    }
}
