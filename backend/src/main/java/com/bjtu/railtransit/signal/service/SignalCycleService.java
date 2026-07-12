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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Backend-owned signal cycle:
 * dispatch/onboard state -> signal domain -> full teacher-data MA -> ATP constraints.
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
        // 必须与联锁/GET /line 共用同一 LineProfile，否则扳道/设灯不影响 MA 周期
        this.lineProfile = loader.getLineProfile();
        this.simulatedInterlocking = simulatedInterlocking;
        // A missing aspect is intentionally left unset. Both the frontend and the
        // interlocking API treat it as RED, which is the fail-safe protective state.
        // GREEN is set only by a built route or an explicit signal command.
    }

    public synchronized Map<String, MovingAuthority> runCycle(
            Iterable<TrainState> runtimeTrains, double nowSeconds) {
        // G1: 从联锁同步 trainId→Route 绑定到 activeRoutes（MA 只读）
        Map<String, Integer> bindings = interlockingService.getRouteBindings();
        // 清除不再绑定的车
        activeRoutes.keySet().removeIf(tid -> !bindings.containsKey(tid));
        // 写入/更新绑定的 Route
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

        // G2: 按车 positionM 刷新 axleSections.occupied（compute 前生效）
        refreshAxleOccupancy(runtimeTrains);

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
                // G4: MA 降级事件写入（5s 节流）
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

        // Service-braking supervision curve approaching EoA. ATO may run below this value,
        // but can never exceed it.
        double supervisedSpeedKmh = Math.sqrt(
                Math.max(0, 2 * SERVICE_BRAKE_MPS2 * (remaining - STOP_MARGIN_METERS))) * 3.6;
        train.setMaxSpeedLimit(Math.min(train.getMaxSpeedLimit(), supervisedSpeedKmh));
        if (train.isEmergencyBraking() && remaining > emergencyDistance + 20) {
            train.setEmergencyBraking(false);
        }
    }

    public LineProfile getLineProfile() { return lineProfile; }
    public boolean isSimulatedInterlocking() { return simulatedInterlocking; }
    public Map<String, Route> getActiveRoutes() { return Collections.unmodifiableMap(activeRoutes); }

    /**
     * G2: 按车 positionM 刷新 axleSections.occupied。
     * 先全清 false，再遍历 occupiesTrack 的列车，车头/车尾落在区段里程范围内 → occupied=true。
     * 在 compute 前调用，确保 eoaFromAxleOccupancy 读到的是当前 tick 的真实占用态。
     */
    private void refreshAxleOccupancy(Iterable<TrainState> runtimeTrains) {
        if (lineProfile.getAxleSections() == null || lineProfile.getAxleSections().isEmpty())
            return;
        // 先全清
        for (AxleCounterSection a : lineProfile.getAxleSections()) {
            a.setOccupied(false);
        }
        // 按车 positionM 标记占用
        for (TrainState t : runtimeTrains) {
            if (!t.occupiesTrack())
                continue;
            double head = t.getPositionMeters();
            double tail = head - t.getTrainLengthMeters();
            double lo = Math.min(head, tail);
            double hi = Math.max(head, tail);
            for (AxleCounterSection a : lineProfile.getAxleSections()) {
                double aStart = axleSectionStartM(a);
                double aEnd = axleSectionEndM(a);
                if (Double.isNaN(aStart) || Double.isNaN(aEnd))
                    continue;
                // 车体 [lo, hi] 与区段 [aStart, aEnd] 有交集 → occupied
                if (hi >= aStart && lo <= aEnd) {
                    a.setOccupied(true);
                }
            }
        }
    }

    /** 计轴区段起点里程 m（委托 TrackConstraintService 的同款算法） */
    private double axleSectionStartM(AxleCounterSection a) {
        if (a.getSegIds() == null) return Double.NaN;
        double min = Double.NaN;
        for (Integer segId : a.getSegIds()) {
            double sm = lineProfile.segStartM(String.valueOf(segId));
            if (!Double.isNaN(sm) && (Double.isNaN(min) || sm < min))
                min = sm;
        }
        return min;
    }

    /** 计轴区段终点里程 m */
    private double axleSectionEndM(AxleCounterSection a) {
        if (a.getSegIds() == null) return Double.NaN;
        double max = Double.NaN;
        for (Integer segId : a.getSegIds()) {
            double em = lineProfile.segEndM(String.valueOf(segId));
            if (!Double.isNaN(em) && (Double.isNaN(max) || em > max))
                max = em;
        }
        return max;
    }
    public void assignRoute(String trainId, Route route) {
        if (route == null) activeRoutes.remove(trainId);
        else activeRoutes.put(trainId, route);
    }
}
