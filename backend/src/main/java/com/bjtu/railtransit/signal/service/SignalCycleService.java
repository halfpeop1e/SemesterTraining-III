package com.bjtu.railtransit.signal.service;

import com.bjtu.railtransit.domain.model.TrainState;
import com.bjtu.railtransit.signal.domain.Direction;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.bjtu.railtransit.signal.domain.RouteRuntimeState;
import com.bjtu.railtransit.signal.domain.RouteLifecycleState;
import com.bjtu.railtransit.signal.domain.SignalEvent;
import com.bjtu.railtransit.signal.model.AxleCounterSection;
import com.bjtu.railtransit.signal.model.LineProfile;
import com.bjtu.railtransit.signal.model.Route;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final SignalPlcDepartureService signalPlcDepartureService;
    private final boolean autoLaboratoryStationLeg;
    private final Map<String, Route> activeRoutes = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * The vehicle model uses verified station chainage, while the supplied CBI
     * Seg graph has no confirmed mapping to that coordinate system. A lease
     * therefore binds one lab route to one ATO station leg and is released only
     * after the bridge reports that exact station stop.
     */
    private final Map<String, LaboratoryRouteLease> laboratoryRouteLeases =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Local ATO station legs have no PLC cab context and may use a software corridor. */
    private final Map<String, LocalStationLegLease> localStationLegLeases =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    public SignalCycleService(MovingAuthorityService movingAuthorityService,
                              MovementAuthorityRegistry registry,
                              LineProfileLoader loader,
                              SignalInterlockingService interlockingService,
                              SignalEventLog eventLog,
                              SignalPlcDepartureService signalPlcDepartureService,
                              @Value("${rail.signal.simulated-interlocking:true}") boolean simulatedInterlocking,
                              @Value("${rail.signal.auto-laboratory-station-leg:true}") boolean autoLaboratoryStationLeg)
            throws IOException {
        this.movingAuthorityService = movingAuthorityService;
        this.registry = registry;
        this.interlockingService = interlockingService;
        this.eventLog = eventLog;
        this.signalPlcDepartureService = signalPlcDepartureService;
        // 必须与联锁/GET /line 共用同一 LineProfile，否则扳道/设灯不影响 MA 周期
        this.lineProfile = loader.getLineProfile();
        this.simulatedInterlocking = simulatedInterlocking;
        this.autoLaboratoryStationLeg = autoLaboratoryStationLeg;
        // A missing aspect is intentionally left unset. Both the frontend and the
        // interlocking API treat it as RED, which is the fail-safe protective state.
        // GREEN is set only by a built route or an explicit signal command.
    }

    /** Compatibility constructor for isolated signal-domain tests. */
    public SignalCycleService(MovingAuthorityService movingAuthorityService,
                              MovementAuthorityRegistry registry,
                              LineProfileLoader loader,
                              SignalInterlockingService interlockingService,
                              SignalEventLog eventLog,
                              boolean simulatedInterlocking) throws IOException {
        this(movingAuthorityService, registry, loader, interlockingService, eventLog,
                null, simulatedInterlocking, false);
    }

    /** Compatibility constructor for tests and integrations that explicitly wire the PLC bridge. */
    public SignalCycleService(MovingAuthorityService movingAuthorityService,
                              MovementAuthorityRegistry registry,
                              LineProfileLoader loader,
                              SignalInterlockingService interlockingService,
                              SignalEventLog eventLog,
                              SignalPlcDepartureService signalPlcDepartureService,
                              boolean simulatedInterlocking) throws IOException {
        this(movingAuthorityService, registry, loader, interlockingService, eventLog,
                signalPlcDepartureService, simulatedInterlocking, false);
    }

    public synchronized Map<String, MovingAuthority> runCycle(
            Iterable<TrainState> runtimeTrains, double nowSeconds) {
        List<TrainState> runtimeList = new ArrayList<>();
        for (TrainState train : runtimeTrains) runtimeList.add(train);

        refreshAxleOccupancy(runtimeList);
        interlockingService.advanceRouteLifecycles(runtimeList, nowSeconds);

        // G1: 从联锁同步 trainId→Route 绑定到 activeRoutes（MA 只读）
        Map<String, Integer> bindingsBeforeLeaseSync = interlockingService.getRouteBindings();
        // 清除不再绑定的车
        activeRoutes.keySet().removeIf(tid -> !bindingsBeforeLeaseSync.containsKey(tid));
        // 写入/更新绑定的 Route
        for (Map.Entry<String, Integer> entry : bindingsBeforeLeaseSync.entrySet()) {
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
        synchronizeLaboratoryRouteLeases(nowSeconds);
        autoBuildLaboratoryStationLegs(nowSeconds);
        synchronizeLocalStationLegLeases(runtimeList);
        Map<String, Integer> bindingsAfterLeaseSync = interlockingService.getRouteBindings();
        activeRoutes.keySet().removeIf(tid -> !bindingsAfterLeaseSync.containsKey(tid));
        for (Map.Entry<String, Integer> entry : bindingsAfterLeaseSync.entrySet()) {
            interlockingService.getBuiltRoutes().stream()
                    .filter(route -> route.getId() == entry.getValue())
                    .findFirst()
                    .ifPresent(route -> activeRoutes.put(entry.getKey(), route));
        }

        List<com.bjtu.railtransit.signal.domain.TrainState> signalTrains = new ArrayList<>();
        for (TrainState runtime : runtimeList) {
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
        for (com.bjtu.railtransit.signal.domain.TrainState signalTrain : signalTrains) {
            if (!activeRoutes.containsKey(signalTrain.getTrainId())
                    && !hasSoftwareLocalStationLeg(signalTrain.getTrainId())) {
                MovingAuthority blocked = new MovingAuthority();
                blocked.setTrainId(signalTrain.getTrainId());
                blocked.setEndOfAuthorityM(signalTrain.getPositionM());
                blocked.setMaxSpeedKmh(0);
                blocked.setBasis(com.bjtu.railtransit.signal.domain.AuthorityBasis.ROUTE_END);
                blocked.setEvent(SignalEvent.ROUTE_BLOCKED);
                blocked.setTimestamp(signalTrain.getTimestamp());
                values.put(signalTrain.getTrainId(), blocked);
            }
        }
        applyLaboratoryStationLegAuthorities(runtimeList, values);
        registry.replace(values, nowSeconds,
                simulatedInterlocking ? "SIMULATED_INTERLOCKING" : "LAB_INTERLOCKING");

        for (TrainState runtime : runtimeList) {
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

        // Run only after ATP has marked this cycle's unsafe authority. A
        // route-build REST call and a generic ATS command are intentions only;
        // neither may make the train movable by themselves.
        syncLaboratoryDepartureAuthorizations(runtimeList, values);
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
                || ma.getEvent() == SignalEvent.MA_EXPIRED
                || ma.getEvent() == SignalEvent.POSITION_LOSS;
        boolean movingIntoUnsafeAuthority = speedMps > 0.1
                && (degraded || remaining <= emergencyDistance + STOP_MARGIN_METERS);
        if (movingIntoUnsafeAuthority) {
            train.setEmergencyBraking(true);
            train.setMaxSpeedLimit(0);
            train.setActiveCommand("ATP_EMERGENCY_BRAKE");
            return;
        }
        if (remaining <= STOP_MARGIN_METERS || degraded || ma.getEvent() == SignalEvent.ROUTE_BLOCKED) {
            train.setMaxSpeedLimit(0);
            if (!train.isEmergencyBraking()) train.setActiveCommand("WAITING_ROUTE");
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
    public boolean isDeparturePermitted(String trainId) {
        return hasSoftwareLocalStationLeg(trainId)
                ? localAuthorityPermitsDeparture(trainId)
                : interlockingService.isDeparturePermitted(trainId);
    }

    /** True while a CBI route or software local station corridor is assigned. */
    public boolean hasRouteRuntime(String trainId) {
        return localStationLegLeases.containsKey(trainId)
                || interlockingService.getRouteRuntimeForTrain(trainId) != null;
    }

    public String departureBlockingReason(String trainId) {
        if (hasSoftwareLocalStationLeg(trainId)) {
            MovingAuthority ma = registry.get(trainId);
            if (ma == null) return "LOCAL_MA_UNAVAILABLE";
            if (ma.getEvent() != SignalEvent.NONE) return "LOCAL_" + ma.getEvent().name();
            return ma.getMaxSpeedKmh() > 0 ? "NONE" : "LOCAL_SPEED_ZERO";
        }
        return interlockingService.departureBlockingReason(trainId);
    }
    public void removeTrain(String trainId) {
        laboratoryRouteLeases.remove(trainId);
        localStationLegLeases.remove(trainId);
        interlockingService.removeTrain(trainId);
    }

    /** Establishes the next local ATO station leg without exposing route selection in the UI. */
    public synchronized List<Integer> ensureLocalStationLeg(
            String trainId, int fromStationId, int toStationId,
            String direction, double nowSeconds) {
        LocalStationLegLease localLease = localStationLegLeases.get(trainId);
        if (localLease != null && localLease.targetStationId == toStationId) {
            return localLease.routeIds;
        }
        RouteRuntimeState existing = interlockingService.getRouteRuntimeForTrain(trainId);
        if (existing != null && existing.state() == RouteLifecycleState.ESTABLISHED) {
            return interlockingService.getBuiltRoutesForTrain(trainId).stream().map(Route::getId).toList();
        }
        com.bjtu.railtransit.signal.domain.Direction routeDirection =
                "DOWN".equalsIgnoreCase(direction)
                        ? com.bjtu.railtransit.signal.domain.Direction.DOWN
                        : com.bjtu.railtransit.signal.domain.Direction.UP;
        try {
            List<Route> routes = interlockingService.buildAndAssignLocalStationLeg(
                    trainId, fromStationId, toStationId, routeDirection, nowSeconds);
            return routes.stream().map(Route::getId).toList();
        } catch (IllegalArgumentException error) {
            if (routeDirection != Direction.DOWN) throw error;
            localStationLegLeases.put(trainId, new LocalStationLegLease(
                    List.of(), toStationId, stationPosition(toStationId), false));
            return List.of();
        }
    }

    /** Releases a local ATO leg exactly when the backend-owned train reaches its platform. */
    public synchronized boolean completeLocalStationLegAfterArrival(String trainId, double nowSeconds) {
        localStationLegLeases.remove(trainId);
        return interlockingService.completeLocalStationLegAfterArrival(trainId, nowSeconds);
    }

    private void synchronizeLaboratoryRouteLeases(double nowSeconds) {
        if (signalPlcDepartureService == null) return;
        Map<String, Integer> bindings = interlockingService.getRouteBindings();
        laboratoryRouteLeases.keySet().removeIf(trainId -> !bindings.containsKey(trainId));

        for (Map.Entry<String, Route> entry : new ArrayList<>(activeRoutes.entrySet())) {
            String trainId = entry.getKey();
            Route route = entry.getValue();
            var control = signalPlcDepartureService.controlSnapshot(trainId);
            if (control == null || control.state() == null) continue;

            LaboratoryRouteLease lease = laboratoryRouteLeases.get(trainId);
            if (lease != null && hasReachedLaboratoryLease(control, lease)) {
                if (interlockingService.completeRouteAfterLaboratoryArrival(trainId, nowSeconds)) {
                    activeRoutes.remove(trainId);
                    laboratoryRouteLeases.remove(trainId);
                    if (eventLog != null) {
                        eventLog.add("INFO", "ROUTE", "列车 " + trainId
                                + " 已停靠站 " + lease.targetStationId + "，实验进路 "
                                + route.getId() + " 已释放", trainId);
                    }
                }
                continue;
            }

            RouteRuntimeState runtime = interlockingService.getRouteRuntimeForTrain(trainId);
            List<Route> routes = interlockingService.getBuiltRoutesForTrain(trainId);
            if (runtime != null && "LAB_STATION_LEG".equals(runtime.source())
                    && lease == null && !routes.isEmpty()) {
                List<Integer> routeIds = routes.stream().map(Route::getId).toList();
                if (interlockingService.hasEstablishedRouteSequence(trainId, routeIds)) {
                    laboratoryRouteLeases.put(trainId, new LaboratoryRouteLease(
                            routeIds, control.nextTargetStationId(),
                            control.nextTargetAbsolutePositionM()));
                }
            }
        }
    }

    private void synchronizeLocalStationLegLeases(List<TrainState> trains) {
        Map<String, Integer> bindings = interlockingService.getRouteBindings();
        localStationLegLeases.entrySet().removeIf(entry -> entry.getValue().cbiBacked
                && !bindings.containsKey(entry.getKey()));
        for (TrainState train : trains) {
            String trainId = train.getTrainId();
            RouteRuntimeState runtime = interlockingService.getRouteRuntimeForTrain(trainId);
            if (runtime == null || !"LOCAL_STATION_LEG".equals(runtime.source())
                    || runtime.state() != RouteLifecycleState.ESTABLISHED) {
                continue;
            }
            List<Route> routes = interlockingService.getBuiltRoutesForTrain(trainId);
            int nextIndex = train.getNextStationIndex();
            if (routes.isEmpty() || nextIndex < 0 || nextIndex >= lineProfile.getStations().size()) continue;
            double target = lineProfile.getStations().get(nextIndex).getPositionM();
            int targetStationId = Integer.parseInt(lineProfile.getStations().get(nextIndex).getId());
            List<Integer> routeIds = routes.stream().map(Route::getId).toList();
            if (interlockingService.hasEstablishedRouteSequence(trainId, routeIds)) {
                localStationLegLeases.put(trainId,
                        new LocalStationLegLease(routeIds, targetStationId, target, true));
            }
        }
    }

    /**
     * Single-train laboratory convenience: once the cab has completed the
     * station door cycle, establish the verified route chain to its next ATO
     * target. Interlocking remains authoritative; any conflict leaves the train
     * stopped without departure authorization.
     */
    private void autoBuildLaboratoryStationLegs(double nowSeconds) {
        if (!autoLaboratoryStationLeg || signalPlcDepartureService == null) return;
        for (String trainId : signalPlcDepartureService.activeLaboratoryTrainIds()) {
            if (interlockingService.getRouteRuntimeForTrain(trainId) != null) continue;
            var control = signalPlcDepartureService.controlSnapshot(trainId);
            if (control == null || control.state() == null
                    || control.atoRunning()
                    || control.state().getVelocity() > 0.1
                    || !control.keySwitchOn()
                    || !control.keyPreparationComplete()
                    || control.directionHandle() != 1
                    || control.masterHandle() != 0
                    || !control.doorsClosed()
                    || control.doorCycleRequired()
                    || control.nextTargetStationId() <= control.currentStationId()) {
                continue;
            }
            try {
                List<Route> routes = interlockingService.buildAndAssignLaboratoryStationLeg(
                        trainId, control.currentStationId(), control.nextTargetStationId(), nowSeconds);
                List<Integer> routeIds = routes.stream().map(Route::getId).toList();
                if (interlockingService.hasEstablishedRouteSequence(trainId, routeIds)) {
                    laboratoryRouteLeases.put(trainId, new LaboratoryRouteLease(
                            routeIds, control.nextTargetStationId(), control.nextTargetAbsolutePositionM()));
                }
                if (eventLog != null) {
                    eventLog.add("INFO", "ROUTE", "列车 " + trainId + " 已自动办理站 "
                            + control.currentStationId() + " 至站 " + control.nextTargetStationId()
                            + " 实验进路 " + routeIds, trainId);
                }
            } catch (RuntimeException ex) {
                if (eventLog != null) {
                    eventLog.addThrottled("WARN", "ROUTE", "列车 " + trainId
                                    + " 自动办理下一站进路失败: " + ex.getMessage(),
                            trainId, trainId + "|AUTO_LAB_ROUTE_FAILED");
                }
            }
        }
    }

    private static boolean hasReachedLaboratoryLease(
            com.bjtu.railtransit.vehicle.protocol704.Protocol704VehicleControlBridge.ControlStateSnapshot control,
            LaboratoryRouteLease lease) {
        return !control.atoRunning()
                && control.currentStationId() == lease.targetStationId
                && control.state().getVelocity() <= 0.1
                && control.absolutePositionM() >= lease.targetAbsolutePositionM - STOP_MARGIN_METERS;
    }

    /**
     * A lab station leg can never receive MA beyond the next selected platform.
     * This remains conservative when the static route endpoint is nearer: the
     * existing MA is retained in that case and ATO_READY remains false.
     */
    private void applyLaboratoryStationLegAuthorities(List<TrainState> trains,
                                                       Map<String, MovingAuthority> values) {
        for (TrainState train : trains) {
            LaboratoryRouteLease lease = laboratoryRouteLeases.get(train.getTrainId());
            LocalStationLegLease localLease = localStationLegLeases.get(train.getTrainId());
            List<Integer> routeIds = lease != null ? lease.routeIds : localLease != null ? localLease.routeIds : null;
            double targetPosition = lease != null ? lease.targetAbsolutePositionM : localLease != null ? localLease.targetAbsolutePositionM : 0;
            MovingAuthority ma = values.get(train.getTrainId());
            if (routeIds == null || ma == null) continue;
            if (targetPosition <= train.getPositionMeters() + STOP_MARGIN_METERS && train.getDirectionSign() > 0) continue;
            if (train.getDirectionSign() < 0 && targetPosition >= train.getPositionMeters() - STOP_MARGIN_METERS) continue;
            boolean softwareLocalLeg = localLease != null && !localLease.cbiBacked;
            if (!softwareLocalLeg
                    && !interlockingService.hasEstablishedRouteSequence(train.getTrainId(), routeIds)) continue;
            if (softwareLocalLeg && ma.getEvent() != SignalEvent.NONE
                    && ma.getEvent() != SignalEvent.SIGNAL_BOUNDARY) continue;
            if (ma.getEvent() != SignalEvent.NONE && ma.getEvent() != SignalEvent.SIGNAL_BOUNDARY) continue;
            ma.setEndOfAuthorityM(targetPosition);
            ma.setBasis(com.bjtu.railtransit.signal.domain.AuthorityBasis.ROUTE_END);
            ma.setEvent(SignalEvent.NONE);
            ma.setRouteId(routeIds.isEmpty() ? null : routeIds.get(0));
        }
    }

    private boolean hasSoftwareLocalStationLeg(String trainId) {
        LocalStationLegLease lease = localStationLegLeases.get(trainId);
        return lease != null && !lease.cbiBacked;
    }

    private boolean localAuthorityPermitsDeparture(String trainId) {
        MovingAuthority ma = registry.get(trainId);
        return ma != null && ma.getEvent() == SignalEvent.NONE && ma.getMaxSpeedKmh() > 0;
    }

    private double stationPosition(int stationId) {
        return lineProfile.getStations().stream()
                .filter(station -> String.valueOf(stationId).equals(station.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown station " + stationId))
                .getPositionM();
    }

    private void syncLaboratoryDepartureAuthorizations(List<TrainState> trains,
                                                         Map<String, MovingAuthority> values) {
        if (signalPlcDepartureService == null) return;
        for (TrainState train : trains) {
            String trainId = train.getTrainId();
            MovingAuthority ma = values.get(trainId);
            RouteRuntimeState route = interlockingService.getRouteRuntimeForTrain(trainId);
            LaboratoryRouteLease lease = laboratoryRouteLeases.get(trainId);
            boolean routeEstablished = lease != null
                    ? interlockingService.hasEstablishedRouteSequence(trainId, lease.routeIds)
                    : route != null && route.state() == RouteLifecycleState.ESTABLISHED;
            var control = signalPlcDepartureService.controlSnapshot(trainId);
            boolean validAuthority = ma != null
                    && ma.getMaxSpeedKmh() > 0
                    && ma.getEvent() != SignalEvent.ROUTE_BLOCKED
                    && ma.getEvent() != SignalEvent.DEGRADED
                    && ma.getEvent() != SignalEvent.MA_EXPIRED
                    && ma.getEvent() != SignalEvent.POSITION_LOSS
                    && (ma.getEndOfAuthorityM() - train.getPositionMeters())
                    * train.getDirectionSign() > STOP_MARGIN_METERS
                    && (control == null
                    || signalPlcDepartureService.authorityCoversNextAtoStation(control, ma));
            boolean authorized = routeEstablished
                    && interlockingService.isDeparturePermitted(trainId)
                    && validAuthority
                    && !train.isEmergencyBraking();
            if (authorized) signalPlcDepartureService.onDepartureAuthorized(trainId);
            else signalPlcDepartureService.onDepartureRevoked(trainId);
        }
    }

    private record LaboratoryRouteLease(List<Integer> routeIds, int targetStationId,
                                        double targetAbsolutePositionM) {}

    private record LocalStationLegLease(List<Integer> routeIds, int targetStationId,
                                        double targetAbsolutePositionM, boolean cbiBacked) {}

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
                // 车体 [lo, hi] 与区段 [aStart, aEnd] 有交集 → occupied（含方向）
                if (hi >= aStart && lo <= aEnd) {
                    int dir = t.isUpDirection() ? 0x55 : 0xAA;
                    a.addOccupyingTrain(t.getTrainId(), dir);
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
