package com.bjtu.railtransit.signal.service;

import com.bjtu.railtransit.domain.model.TrainState;
import com.bjtu.railtransit.signal.domain.RouteLifecycleState;
import com.bjtu.railtransit.signal.domain.RouteRuntimeState;
import com.bjtu.railtransit.signal.domain.SignalAspect;
import com.bjtu.railtransit.signal.model.AxleCounterSection;
import com.bjtu.railtransit.signal.model.LineProfile;
import com.bjtu.railtransit.signal.model.OverlapSection;
import com.bjtu.railtransit.signal.model.Route;
import com.bjtu.railtransit.signal.model.Signal;
import com.bjtu.railtransit.signal.model.Station;
import com.bjtu.railtransit.signal.model.Switch;
import com.bjtu.railtransit.signal.model.SwitchState;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * Single owner of simulated interlocking runtime state.
 *
 * <p>ATS/HMI may request a route, but only this service may lock resources and
 * open the protecting signal. A route remains bound to one train until the
 * train has occupied and subsequently cleared all route resources.</p>
 */
@Service
public class SignalInterlockingService {

    private final LineProfileLoader lineProfileLoader;
    private final Map<Integer, Route> builtRoutes = new LinkedHashMap<>();
    private final Map<String, Integer> routeBindings = new LinkedHashMap<>();
    private final Map<Integer, MutableRouteRuntime> runtimes = new LinkedHashMap<>();

    public SignalInterlockingService(LineProfileLoader lineProfileLoader) {
        this.lineProfileLoader = lineProfileLoader;
    }

    public synchronized Switch operateSwitch(String switchId, SwitchState target) {
        Switch sw = findSwitch(switchId);
        if (target == null) throw new IllegalArgumentException("道岔目标状态不能为空");
        if (isSwitchLocked(sw)) throw new IllegalStateException("道岔已被进路锁闭: " + switchId);
        sw.setState(target);
        return sw;
    }

    /** Legacy station operation: establish an unowned route only when every resource is clear. */
    public synchronized Route buildRoute(int routeId) {
        return establish(routeId, null, "MANUAL_STATION", Double.NaN);
    }

    /** Atomic route establishment and train ownership binding. */
    public synchronized Route buildAndAssignRoute(int routeId, String trainId,
                                                  String source, double nowSeconds) {
        requireTrainId(trainId);
        return establish(routeId, trainId, source == null ? "ATS" : source, nowSeconds);
    }

    /**
     * Establishes every CBI route needed for one verified laboratory station
     * leg. The operation is transactional: a failed later route restores every
     * route and signal changed by this request.
     */
    public synchronized List<Route> buildAndAssignLaboratoryStationLeg(
            String trainId, int fromStationId, int toStationId, double nowSeconds) {
        return buildAndAssignStationLeg(trainId, fromStationId, toStationId,
                com.bjtu.railtransit.signal.domain.Direction.UP, "LAB_STATION_LEG", nowSeconds);
    }

    /**
     * Establishes one complete CBI station leg for a backend-owned local train.
     * It has the same topology proof and transactional lock semantics as the
     * hardware workflow, but its arrival is confirmed by the simulation engine.
     */
    public synchronized List<Route> buildAndAssignLocalStationLeg(
            String trainId, int fromStationId, int toStationId,
            com.bjtu.railtransit.signal.domain.Direction direction, double nowSeconds) {
        return buildAndAssignStationLeg(trainId, fromStationId, toStationId,
                direction, "LOCAL_STATION_LEG", nowSeconds);
    }

    private List<Route> buildAndAssignStationLeg(
            String trainId, int fromStationId, int toStationId,
            com.bjtu.railtransit.signal.domain.Direction direction,
            String source, double nowSeconds) {
        requireTrainId(trainId);
        LaboratoryStationLegResolver resolver = new LaboratoryStationLegResolver(
                lineProfileLoader.getLineProfile());
        LaboratoryStationLegResolver.LaboratoryStationLeg leg = resolver.resolve(
                fromStationId, toStationId, direction);

        Integer old = routeBindings.get(trainId);
        if (old != null) {
            throw new IllegalStateException("train " + trainId + " already owns route " + old);
        }

        List<Route> established = new ArrayList<>();
        try {
            for (int routeId : leg.routeIds()) {
                established.add(establish(routeId, null, source, nowSeconds));
            }
            for (Route route : established) runtimes.get(route.getId()).trainId = trainId;
            routeBindings.put(trainId, leg.primaryRouteId());
            return List.copyOf(established);
        } catch (RuntimeException ex) {
            for (Route route : established) {
                finishRoute(route, RouteLifecycleState.CANCELLED, "LAB_LEG_ROLLBACK",
                        "laboratory station leg establishment rolled back", nowSeconds);
            }
            throw ex;
        }
    }

    /**
     * Lists every forward, adjacent station leg and whether the supplied CBI
     * topology proves exactly one route sequence for it.
     */
    public synchronized List<LaboratoryStationLegCapability> getLaboratoryStationLegCapabilities() {
        LaboratoryStationLegResolver resolver = new LaboratoryStationLegResolver(
                lineProfileLoader.getLineProfile());
        List<Integer> stationIds = lineProfileLoader.getLineProfile().getStations().stream()
                .map(Station::getId)
                .map(Integer::parseInt)
                .sorted()
                .toList();
        List<LaboratoryStationLegCapability> capabilities = new ArrayList<>();
        for (int index = 0; index + 1 < stationIds.size(); index++) {
            int fromStationId = stationIds.get(index);
            int toStationId = stationIds.get(index + 1);
            if (toStationId != fromStationId + 1) {
                capabilities.add(new LaboratoryStationLegCapability(fromStationId, toStationId,
                        false, List.of(), "STATIONS_NOT_ADJACENT"));
                continue;
            }
            try {
                LaboratoryStationLegResolver.LaboratoryStationLeg leg = resolver.resolve(
                        fromStationId, toStationId, com.bjtu.railtransit.signal.domain.Direction.UP);
                capabilities.add(new LaboratoryStationLegCapability(fromStationId, toStationId,
                        true, leg.routeIds(), "NONE"));
            } catch (IllegalArgumentException error) {
                capabilities.add(new LaboratoryStationLegCapability(fromStationId, toStationId,
                        false, List.of(), error.getMessage()));
            }
        }
        return List.copyOf(capabilities);
    }

    /** Rejects a hardware-controlled run when any leg is not proven by the CBI topology. */
    public synchronized void requireSupportedLaboratoryRun(int fromStationId, int toStationId) {
        if (toStationId <= fromStationId) {
            throw new IllegalArgumentException("laboratory run target must be after its source station");
        }
        LaboratoryStationLegResolver resolver = new LaboratoryStationLegResolver(
                lineProfileLoader.getLineProfile());
        for (int stationId = fromStationId; stationId < toStationId; stationId++) {
            resolver.resolve(stationId, stationId + 1,
                    com.bjtu.railtransit.signal.domain.Direction.UP);
        }
    }

    public record LaboratoryStationLegCapability(int fromStationId, int toStationId,
                                                  boolean supported, List<Integer> routeIds,
                                                  String reason) {
        public LaboratoryStationLegCapability {
            routeIds = List.copyOf(routeIds);
        }
    }

    private Route establish(int routeId, String trainId, String source, double nowSeconds) {
        Route route = findRoute(routeId);
        MutableRouteRuntime existing = runtimes.get(routeId);
        if (existing != null && !existing.state.isTerminal()) {
            if (Objects.equals(existing.trainId, trainId)) return route;
            throw new IllegalStateException("进路已建立: " + routeId);
        }
        if (trainId != null) {
            Integer old = routeBindings.get(trainId);
            if (old != null && old != routeId) {
                throw new IllegalStateException("列车 " + trainId + " 已绑定进路 " + old);
            }
        }

        checkConflictingRoutes(route);
        checkRouteResourcesAvailable(route, trainId);
        checkRouteEquipment(route);

        MutableRouteRuntime runtime = new MutableRouteRuntime(routeId, trainId,
                RouteLifecycleState.REQUESTED, source, nowSeconds);
        runtimes.put(routeId, runtime);
        builtRoutes.put(routeId, route);
        if (trainId != null) routeBindings.put(trainId, routeId);

        // This transition is deliberately performed only after every validation
        // and ownership update succeeded, so the API cannot leave an unowned green signal.
        setSignalAspectInternal(String.valueOf(route.getStartSignalId()), SignalAspect.GREEN);
        runtime.transition(RouteLifecycleState.ESTABLISHED, "ROUTE_ESTABLISHED",
                "进路已锁闭，始端信号开放", nowSeconds);
        return route;
    }

    public synchronized Route cancelRoute(int routeId) {
        Route route = builtRoutes.get(routeId);
        if (route == null) throw new NoSuchElementException("进路未建立: " + routeId);
        MutableRouteRuntime runtime = runtimes.get(routeId);
        if (runtime != null && "LAB_STATION_LEG".equals(runtime.source)) {
            if (runtime.trainId == null) throw new IllegalStateException("laboratory station leg has no owner");
            cancelLaboratoryStationLeg(runtime.trainId, runtime.updatedAtSeconds);
            return route;
        }
        if (runtime != null && "LOCAL_STATION_LEG".equals(runtime.source)) {
            if (runtime.trainId == null) throw new IllegalStateException("local station leg has no owner");
            cancelLocalStationLeg(runtime.trainId, runtime.updatedAtSeconds);
            return route;
        }
        if (runtime != null && (runtime.state == RouteLifecycleState.OCCUPIED
                || runtime.state == RouteLifecycleState.RELEASING)) {
            throw new IllegalStateException("列车已进入进路，禁止直接取消: " + routeId);
        }
        finishRoute(route, RouteLifecycleState.CANCELLED, "ROUTE_CANCELLED", "进路已取消",
                runtime == null ? Double.NaN : runtime.updatedAtSeconds);
        return route;
    }

    /**
     * Advances occupation and release from the authoritative train projection.
     * Call once per signal cycle, after axle occupation has been refreshed.
     */
    public synchronized void advanceRouteLifecycles(Collection<TrainState> trains, double nowSeconds) {
        Map<String, TrainState> byId = new HashMap<>();
        if (trains != null) {
            for (TrainState train : trains) byId.put(train.getTrainId(), train);
        }
        for (Integer routeId : new ArrayList<>(builtRoutes.keySet())) {
            MutableRouteRuntime runtime = runtimes.get(routeId);
            if (runtime == null || runtime.state.isTerminal() || runtime.trainId == null) continue;
            // A physical ATO station leg spans several CBI routes whose Seg
            // coordinates are not the vehicle platform chainage. Its release
            // is therefore driven only by the bridge's confirmed platform stop.
            if (isStationLegSource(runtime.source)) continue;
            Route route = builtRoutes.get(routeId);
            TrainState owner = byId.get(runtime.trainId);
            boolean ownerInside = owner != null && hasEnteredRoute(owner)
                    && routeResourceSections(route).stream()
                    .map(this::findAxleSection)
                    .filter(Objects::nonNull)
                    .anyMatch(section -> section.isOccupiedBy(runtime.trainId));

            if (runtime.state == RouteLifecycleState.ESTABLISHED && ownerInside) {
                runtime.occupiedOnce = true;
                setSignalAspectInternal(String.valueOf(route.getStartSignalId()), SignalAspect.RED);
                runtime.transition(RouteLifecycleState.OCCUPIED, "TRAIN_ENTERED_ROUTE",
                        "列车已进入进路", nowSeconds);
            } else if (runtime.state == RouteLifecycleState.OCCUPIED && !ownerInside) {
                runtime.transition(RouteLifecycleState.RELEASING, "TRAIN_CLEARED_ROUTE",
                        "列车已出清，等待释放", nowSeconds);
            } else if (runtime.state == RouteLifecycleState.RELEASING
                    && nowSeconds > runtime.updatedAtSeconds) {
                finishRoute(route, RouteLifecycleState.COMPLETED, "ROUTE_RELEASED",
                        "进路资源已释放", nowSeconds);
            }
        }
    }

    public synchronized boolean isDeparturePermitted(String trainId) {
        MutableRouteRuntime runtime = runtimeForTrain(trainId);
        if (runtime == null || runtime.state != RouteLifecycleState.ESTABLISHED) return false;
        Route route = builtRoutes.get(runtime.routeId);
        if (route == null) return false;
        Signal signal = findSignal(String.valueOf(route.getStartSignalId()));
        return signal.getAspect() != null && signal.getAspect().isProceed();
    }

    public synchronized String departureBlockingReason(String trainId) {
        MutableRouteRuntime runtime = runtimeForTrain(trainId);
        if (runtime == null) return "NO_ROUTE_BOUND";
        if (runtime.state != RouteLifecycleState.ESTABLISHED) return "ROUTE_" + runtime.state.name();
        Route route = builtRoutes.get(runtime.routeId);
        if (route == null) return "ROUTE_NOT_BUILT";
        Signal signal = findSignal(String.valueOf(route.getStartSignalId()));
        return signal.getAspect() != null && signal.getAspect().isProceed()
                ? "NONE" : "START_SIGNAL_NOT_PROCEED";
    }

    public synchronized Route assignRoute(String trainId, int routeId) {
        requireTrainId(trainId);
        Route route = builtRoutes.get(routeId);
        if (route == null) throw new NoSuchElementException("进路未建立，无法绑定: routeId=" + routeId);
        MutableRouteRuntime runtime = runtimes.get(routeId);
        if (runtime != null && runtime.trainId != null && !runtime.trainId.equals(trainId)) {
            throw new IllegalStateException("进路 " + routeId + " 已被列车 " + runtime.trainId + " 占用");
        }
        Integer old = routeBindings.get(trainId);
        if (old != null && old != routeId) {
            throw new IllegalStateException("列车 " + trainId + " 已绑定进路 " + old);
        }
        checkRouteResourcesAvailable(route, trainId);
        routeBindings.put(trainId, routeId);
        if (runtime == null) {
            runtime = new MutableRouteRuntime(routeId, trainId, RouteLifecycleState.ESTABLISHED,
                    "MANUAL_ASSIGN", Double.NaN);
            runtimes.put(routeId, runtime);
        } else {
            runtime.trainId = trainId;
        }
        return route;
    }

    public synchronized void unassignRoute(String trainId) {
        Integer routeId = routeBindings.get(trainId);
        if (routeId == null) return;
        MutableRouteRuntime runtime = runtimes.get(routeId);
        if (runtime != null && isStationLegSource(runtime.source)) {
            throw new IllegalStateException("cancel the complete station leg instead");
        }
        if (runtime != null && (runtime.state == RouteLifecycleState.OCCUPIED
                || runtime.state == RouteLifecycleState.RELEASING)) {
            throw new IllegalStateException("列车已进入进路，禁止解绑: " + trainId);
        }
        routeBindings.remove(trainId);
        if (runtime != null) runtime.trainId = null;
    }

    public synchronized void removeTrain(String trainId) {
        List<Route> routes = getBuiltRoutesForTrain(trainId);
        routeBindings.remove(trainId);
        for (Route route : routes) {
            finishRoute(route, RouteLifecycleState.CANCELLED, "TRAIN_REMOVED",
                    "列车删除，进路清理", Double.NaN);
        }
    }

    /**
     * Completes a route only after the laboratory bridge has authoritatively
     * reported a station arrival for its bound train. This is a controlled
     * station-leg adapter while LK/Seg-to-line-mileage mapping is unavailable.
     */
    public synchronized boolean completeRouteAfterLaboratoryArrival(String trainId, double nowSeconds) {
        return completeStationLegAfterArrival(trainId, nowSeconds, "LAB_STATION_LEG");
    }

    /** Completes a local station leg after the backend-owned train reaches its target platform. */
    public synchronized boolean completeLocalStationLegAfterArrival(String trainId, double nowSeconds) {
        return completeStationLegAfterArrival(trainId, nowSeconds, "LOCAL_STATION_LEG");
    }

    private boolean completeStationLegAfterArrival(String trainId, double nowSeconds, String source) {
        List<Route> routes = builtRoutes.values().stream()
                .filter(route -> {
                    MutableRouteRuntime runtime = runtimes.get(route.getId());
                    return runtime != null && trainId.equals(runtime.trainId)
                            && source.equals(runtime.source) && !runtime.state.isTerminal();
                }).toList();
        if (routes.isEmpty()) return false;
        for (Route route : routes) {
            finishRoute(route, RouteLifecycleState.COMPLETED, "STATION_LEG_ARRIVAL",
                    "列车已确认停靠目标站，进路释放", nowSeconds);
        }
        return true;
    }

    /** Every established route held by the laboratory station-leg owner. */
    public synchronized List<Route> getBuiltRoutesForTrain(String trainId) {
        return builtRoutes.values().stream()
                .filter(route -> {
                    MutableRouteRuntime runtime = runtimes.get(route.getId());
                    return runtime != null && trainId.equals(runtime.trainId) && !runtime.state.isTerminal();
                }).toList();
    }

    /** Cancels an entire unoccupied laboratory station-leg route sequence. */
    public synchronized List<Route> cancelLaboratoryStationLeg(String trainId, double nowSeconds) {
        return cancelStationLeg(trainId, nowSeconds, "LAB_STATION_LEG", "laboratory");
    }

    /** Cancels every unoccupied CBI route in the selected local station leg. */
    public synchronized List<Route> cancelLocalStationLeg(String trainId, double nowSeconds) {
        return cancelStationLeg(trainId, nowSeconds, "LOCAL_STATION_LEG", "local");
    }

    private List<Route> cancelStationLeg(String trainId, double nowSeconds, String source, String label) {
        List<Route> routes = getBuiltRoutesForTrain(trainId);
        if (routes.isEmpty()) throw new NoSuchElementException("no " + label + " station leg for " + trainId);
        for (Route route : routes) {
            MutableRouteRuntime runtime = runtimes.get(route.getId());
            if (runtime == null || !source.equals(runtime.source)) {
                throw new IllegalStateException("train " + trainId + " does not own a " + label + " station leg");
            }
            if (runtime != null && (runtime.state == RouteLifecycleState.OCCUPIED
                    || runtime.state == RouteLifecycleState.RELEASING)) {
                throw new IllegalStateException("laboratory station leg is occupied by " + trainId);
            }
        }
        for (Route route : routes) {
            finishRoute(route, RouteLifecycleState.CANCELLED, "STATION_LEG_CANCELLED",
                    label + " station leg cancelled", nowSeconds);
        }
        return routes;
    }

    public synchronized boolean hasEstablishedRouteSequence(String trainId, List<Integer> routeIds) {
        if (routeIds == null || routeIds.isEmpty()) return false;
        for (Integer routeId : routeIds) {
            Route route = builtRoutes.get(routeId);
            MutableRouteRuntime runtime = runtimes.get(routeId);
            if (route == null || runtime == null || !trainId.equals(runtime.trainId)
                    || runtime.state != RouteLifecycleState.ESTABLISHED) {
                return false;
            }
        }
        return true;
    }

    public synchronized Map<String, Integer> getRouteBindings() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(routeBindings));
    }

    public synchronized List<RouteRuntimeState> getRouteRuntimeStates() {
        return runtimes.values().stream().map(MutableRouteRuntime::snapshot).toList();
    }

    public synchronized RouteRuntimeState getRouteRuntimeForTrain(String trainId) {
        MutableRouteRuntime runtime = runtimeForTrain(trainId);
        return runtime == null ? null : runtime.snapshot();
    }

    public synchronized List<Switch> getAllSwitches() {
        LineProfile lp = lineProfileLoader.getLineProfile();
        return lp.getSwitches() != null ? lp.getSwitches() : Collections.emptyList();
    }

    public synchronized List<Route> getBuiltRoutes() {
        return new ArrayList<>(builtRoutes.values());
    }

    public synchronized Map<String, SignalAspect> getAllSignalAspects() {
        Map<String, SignalAspect> result = new LinkedHashMap<>();
        LineProfile lp = lineProfileLoader.getLineProfile();
        if (lp.getSignals() != null) {
            for (Signal signal : lp.getSignals()) result.put(String.valueOf(signal.getId()), signal.getAspect());
        }
        return result;
    }

    /** Manual restrictive aspects are allowed; a proceed aspect requires an established route. */
    public synchronized Signal setSignalAspect(String signalId, SignalAspect aspect) {
        if (aspect != null && aspect.isProceed()) {
            boolean protectedByRoute = builtRoutes.values().stream()
                    .anyMatch(route -> String.valueOf(route.getStartSignalId()).equals(signalId));
            if (!protectedByRoute) throw new IllegalStateException("无已建立进路，禁止直接开放信号机 " + signalId);
        }
        return setSignalAspectInternal(signalId, aspect);
    }

    private Signal setSignalAspectInternal(String signalId, SignalAspect aspect) {
        Signal signal = findSignal(signalId);
        signal.setAspect(aspect);
        return signal;
    }

    private void checkConflictingRoutes(Route newRoute) {
        Set<Integer> newResources = routeResourceSections(newRoute);
        for (Route existing : builtRoutes.values()) {
            MutableRouteRuntime runtime = runtimes.get(existing.getId());
            if (runtime != null && runtime.state.isTerminal()) continue;
            if (existing.getStartSignalId() == newRoute.getStartSignalId()
                    || existing.getEndSignalId() == newRoute.getEndSignalId()) {
                throw new IllegalStateException("敌对进路冲突: routeId=" + existing.getId());
            }
            Set<Integer> overlap = routeResourceSections(existing);
            overlap.retainAll(newResources);
            if (!overlap.isEmpty()) {
                throw new IllegalStateException("进路资源冲突: routeId=" + existing.getId()
                        + ", sections=" + overlap);
            }
        }
    }

    private void checkRouteResourcesAvailable(Route route, String ownerTrainId) {
        for (Integer sectionId : routeResourceSections(route)) {
            AxleCounterSection section = findAxleSection(sectionId);
            if (section != null && section.isOccupiedByOtherThan(ownerTrainId)) {
                throw new IllegalStateException("进路区段占用: axleSectionId=" + sectionId);
            }
        }
    }

    private void checkRouteEquipment(Route route) {
        Set<Integer> routeSegments = new HashSet<>();
        for (Integer sectionId : routeResourceSections(route)) {
            AxleCounterSection section = findAxleSection(sectionId);
            if (section != null && section.getSegIds() != null) routeSegments.addAll(section.getSegIds());
        }
        for (Switch sw : getAllSwitches()) {
            boolean belongs = routeSegments.contains(sw.getMergeSegId())
                    || routeSegments.contains(sw.getNormalSegId())
                    || routeSegments.contains(sw.getReverseSegId());
            if (belongs && (sw.getState() == null || sw.getState() == SwitchState.FAIL)) {
                throw new IllegalStateException("进路道岔状态未知或失表: switchId=" + sw.getId());
            }
        }
        findSignal(String.valueOf(route.getStartSignalId()));
        findSignal(String.valueOf(route.getEndSignalId()));
    }

    private boolean isSwitchLocked(Switch sw) {
        for (Route route : builtRoutes.values()) {
            for (Integer sectionId : routeResourceSections(route)) {
                AxleCounterSection section = findAxleSection(sectionId);
                if (section == null || section.getSegIds() == null) continue;
                if (section.getSegIds().contains(sw.getMergeSegId())
                        || section.getSegIds().contains(sw.getNormalSegId())
                        || section.getSegIds().contains(sw.getReverseSegId())) return true;
            }
        }
        return false;
    }

    private Set<Integer> routeResourceSections(Route route) {
        Set<Integer> ids = new HashSet<>();
        if (route.getAxleSectionIds() != null) ids.addAll(route.getAxleSectionIds());
        if (route.getOverlapIds() != null && lineProfileLoader.getLineProfile().getOverlaps() != null) {
            for (Integer overlapId : route.getOverlapIds()) {
                OverlapSection overlap = lineProfileLoader.getLineProfile().getOverlaps().stream()
                        .filter(item -> item.getId() == overlapId).findFirst().orElse(null);
                if (overlap != null && overlap.getAxleSectionIds() != null) ids.addAll(overlap.getAxleSectionIds());
            }
        }
        return ids;
    }

    private boolean hasEnteredRoute(TrainState train) {
        String status = train.getStatus();
        return status != null && !Set.of("DEPOT_WAITING", "READY_TO_DEPART", "DWELLING",
                "TERMINAL_DWELL", "TURNING_BACK", "FINISHED").contains(status);
    }

    private void finishRoute(Route route, RouteLifecycleState state, String reasonCode,
                             String reason, double nowSeconds) {
        MutableRouteRuntime runtime = runtimes.get(route.getId());
        if (runtime != null) runtime.transition(state, reasonCode, reason, nowSeconds);
        setSignalAspectInternal(String.valueOf(route.getStartSignalId()), SignalAspect.RED);
        builtRoutes.remove(route.getId());
        routeBindings.values().removeIf(id -> id == route.getId());
    }

    private MutableRouteRuntime runtimeForTrain(String trainId) {
        Integer routeId = routeBindings.get(trainId);
        return routeId == null ? null : runtimes.get(routeId);
    }

    private static boolean isStationLegSource(String source) {
        return "LAB_STATION_LEG".equals(source) || "LOCAL_STATION_LEG".equals(source);
    }

    private void requireTrainId(String trainId) {
        if (trainId == null || trainId.isBlank()) throw new IllegalArgumentException("trainId 不能为空");
    }

    private Switch findSwitch(String id) {
        LineProfile lp = lineProfileLoader.getLineProfile();
        if (lp.getSwitches() != null) {
            for (Switch sw : lp.getSwitches()) if (id.equals(String.valueOf(sw.getId()))) return sw;
        }
        throw new NoSuchElementException("道岔不存在: " + id);
    }

    private Route findRoute(int id) {
        LineProfile lp = lineProfileLoader.getLineProfile();
        if (lp.getRoutes() != null) {
            for (Route route : lp.getRoutes()) if (route.getId() == id) return route;
        }
        throw new NoSuchElementException("进路不存在: " + id);
    }

    private Signal findSignal(String id) {
        LineProfile lp = lineProfileLoader.getLineProfile();
        if (lp.getSignals() != null) {
            for (Signal signal : lp.getSignals()) if (id.equals(String.valueOf(signal.getId()))) return signal;
        }
        throw new NoSuchElementException("信号机不存在: " + id);
    }

    private AxleCounterSection findAxleSection(int id) {
        LineProfile lp = lineProfileLoader.getLineProfile();
        if (lp.getAxleSections() != null) {
            for (AxleCounterSection section : lp.getAxleSections()) if (section.getId() == id) return section;
        }
        return null;
    }

    private static final class MutableRouteRuntime {
        final int routeId;
        String trainId;
        RouteLifecycleState state;
        final String source;
        String reasonCode = "ROUTE_REQUESTED";
        String reason = "进路已请求";
        final double requestedAtSeconds;
        double updatedAtSeconds;
        boolean occupiedOnce;

        MutableRouteRuntime(int routeId, String trainId, RouteLifecycleState state,
                            String source, double nowSeconds) {
            this.routeId = routeId;
            this.trainId = trainId;
            this.state = state;
            this.source = source;
            this.requestedAtSeconds = nowSeconds;
            this.updatedAtSeconds = nowSeconds;
        }

        void transition(RouteLifecycleState next, String code, String description, double nowSeconds) {
            state = next;
            reasonCode = code;
            reason = description;
            updatedAtSeconds = nowSeconds;
        }

        RouteRuntimeState snapshot() {
            return new RouteRuntimeState(routeId, trainId, state, source, reasonCode, reason,
                    requestedAtSeconds, updatedAtSeconds, occupiedOnce);
        }
    }
}
