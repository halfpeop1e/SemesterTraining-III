package com.bjtu.railtransit.signal.service;

import com.bjtu.railtransit.signal.domain.Direction;
import com.bjtu.railtransit.signal.model.AxleCounterSection;
import com.bjtu.railtransit.signal.model.LineProfile;
import com.bjtu.railtransit.signal.model.Platform;
import com.bjtu.railtransit.signal.model.Route;
import com.bjtu.railtransit.signal.model.Signal;
import com.bjtu.railtransit.signal.model.Station;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Resolves one physical laboratory station leg to a CBI route sequence.
 *
 * <p>
 * The supplied data has platform Seg IDs and directed CBI routes but no
 * direct station-to-route table. Interchange and terminal stations may place
 * the forward signal on the other platform Seg, and the export contains exact
 * duplicate route rows. This resolver therefore searches every station
 * platform for a same-direction signal, collapses operationally identical
 * routes, and accepts only one shortest path with the fewest reverse-switch
 * segments. A remaining tie is still rejected as ambiguous. It never derives
 * a station target from a route ID or from the synthetic Seg mileage.
 */
final class LaboratoryStationLegResolver {
    private static final int UP_PLATFORM_DIRECTION = 0x55;
    private static final int DOWN_PLATFORM_DIRECTION = 0xAA;
    private static final int MAX_ROUTE_HOPS = 16;

    private final LineProfile lineProfile;

    LaboratoryStationLegResolver(LineProfile lineProfile) {
        this.lineProfile = lineProfile;
    }

    LaboratoryStationLeg resolve(int fromStationId, int toStationId, Direction direction) {
        if (direction != Direction.UP && direction != Direction.DOWN) {
            throw new IllegalArgumentException("station leg direction must be UP or DOWN");
        }
        int stationStep = direction == Direction.UP ? 1 : -1;
        if (toStationId != fromStationId + stationStep) {
            throw new IllegalArgumentException("station leg must use adjacent stations in its travel direction");
        }

        int platformDirection = direction == Direction.UP ? UP_PLATFORM_DIRECTION : DOWN_PLATFORM_DIRECTION;
        Station fromStation = station(fromStationId);
        Station toStation = station(toStationId);

        List<Integer> startSignalIds = stationSignals(fromStation, platformDirection, "source");
        List<Integer> targetSignalIds = stationSignals(toStation, platformDirection, "target");

        List<RoutePath> candidates = bestRouteSequences(startSignalIds, targetSignalIds);
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("no directed CBI route sequence from station "
                    + fromStationId + " to station " + toStationId);
        }
        if (candidates.size() != 1) {
            throw new IllegalArgumentException("ambiguous directed CBI route sequence from station "
                    + fromStationId + " to station " + toStationId);
        }
        RoutePath selected = candidates.get(0);
        return new LaboratoryStationLeg(fromStationId, toStationId, direction,
                selected.routes().stream().map(Route::getId).toList(),
                toStation.getPositionM(), selected.startSignalId(), selected.signalId());
    }

    private Station station(int stationId) {
        return lineProfile.getStations().stream()
                .filter(station -> String.valueOf(stationId).equals(station.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown station " + stationId));
    }

    private List<Integer> stationSignals(Station station, int direction, String role) {
        Set<Integer> platformIds = new HashSet<>(station.getPlatformIds());
        Set<Integer> platformSegIds = lineProfile.getPlatforms().stream()
                .filter(platform -> platformIds.contains(platform.getId()))
                .map(Platform::getSegId)
                .collect(java.util.stream.Collectors.toSet());
        List<Integer> matches = lineProfile.getSignals().stream()
                .filter(signal -> platformSegIds.contains(signal.getSegId()))
                .filter(signal -> signal.getProtectDir() == direction)
                .map(Signal::getId)
                .distinct()
                .sorted()
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("station " + station.getId() + " has no "
                    + role + " signal for this direction");
        }
        return matches;
    }

    private List<RoutePath> bestRouteSequences(List<Integer> startSignalIds, List<Integer> targetSignalIds) {
        Map<Integer, List<Route>> outgoing = new HashMap<>();
        for (Route route : canonicalRoutes()) {
            outgoing.computeIfAbsent(route.getStartSignalId(), ignored -> new ArrayList<>()).add(route);
        }
        for (List<Route> routes : outgoing.values()) {
            routes.sort(Comparator.comparingInt(Route::getId));
        }

        Map<Integer, AxleCounterSection> axleSections = new HashMap<>();
        for (AxleCounterSection section : lineProfile.getAxleSections()) {
            axleSections.put(section.getId(), section);
        }
        Set<Integer> reverseSwitchSegIds = new HashSet<>();
        lineProfile.getSwitches().forEach(item -> reverseSwitchSegIds.add(item.getReverseSegId()));

        Comparator<RoutePath> byCost = Comparator
                .comparingInt((RoutePath path) -> path.routes().size())
                .thenComparingInt(RoutePath::reverseSwitchTraversals);
        PriorityQueue<RoutePath> pending = new PriorityQueue<>(byCost);
        Map<Integer, PathCost> bestAtSignal = new HashMap<>();
        for (int startSignalId : startSignalIds) {
            pending.add(new RoutePath(startSignalId, startSignalId, List.of(), Set.of(startSignalId), 0));
            bestAtSignal.put(startSignalId, new PathCost(0, 0));
        }

        Set<Integer> targetSet = new HashSet<>(targetSignalIds);
        List<RoutePath> matches = new ArrayList<>();
        PathCost bestTargetCost = null;
        while (!pending.isEmpty()) {
            RoutePath current = pending.remove();
            PathCost currentCost = current.cost();
            if (bestTargetCost != null && currentCost.compareTo(bestTargetCost) > 0)
                break;
            PathCost knownCost = bestAtSignal.get(current.signalId());
            if (knownCost != null && currentCost.compareTo(knownCost) > 0)
                continue;
            if (!current.routes().isEmpty() && targetSet.contains(current.signalId())) {
                bestTargetCost = currentCost;
                matches.add(current);
                continue;
            }
            if (current.routes().size() >= MAX_ROUTE_HOPS)
                continue;

            for (Route route : outgoing.getOrDefault(current.signalId(), List.of())) {
                if (current.visitedSignals().contains(route.getEndSignalId()))
                    continue;
                List<Route> nextPath = new ArrayList<>(current.routes());
                nextPath.add(route);
                Set<Integer> visited = new HashSet<>(current.visitedSignals());
                visited.add(route.getEndSignalId());
                int reverseTraversals = current.reverseSwitchTraversals()
                        + reverseSwitchTraversals(route, axleSections, reverseSwitchSegIds);
                RoutePath next = new RoutePath(route.getEndSignalId(), current.startSignalId(),
                        nextPath, visited, reverseTraversals);
                PathCost nextCost = next.cost();
                PathCost previous = bestAtSignal.get(route.getEndSignalId());
                if (previous == null || nextCost.compareTo(previous) <= 0) {
                    if (previous == null || nextCost.compareTo(previous) < 0) {
                        bestAtSignal.put(route.getEndSignalId(), nextCost);
                    }
                    pending.add(next);
                }
            }
        }
        return matches;
    }

    private List<Route> canonicalRoutes() {
        List<Route> sorted = new ArrayList<>(lineProfile.getRoutes());
        sorted.sort(Comparator.comparingInt(Route::getId));
        Map<RouteSignature, Route> canonical = new LinkedHashMap<>();
        for (Route route : sorted) {
            RouteSignature signature = new RouteSignature(route.getStartSignalId(), route.getEndSignalId(),
                    safeList(route.getAxleSectionIds()), safeList(route.getOverlapIds()), route.getCiZoneId());
            canonical.putIfAbsent(signature, route);
        }
        return List.copyOf(canonical.values());
    }

    private int reverseSwitchTraversals(Route route, Map<Integer, AxleCounterSection> axleSections,
            Set<Integer> reverseSwitchSegIds) {
        Set<Integer> routeReverseSegIds = new HashSet<>();
        for (int sectionId : safeList(route.getAxleSectionIds())) {
            AxleCounterSection section = axleSections.get(sectionId);
            if (section == null || section.getSegIds() == null)
                continue;
            for (int segId : section.getSegIds()) {
                if (reverseSwitchSegIds.contains(segId))
                    routeReverseSegIds.add(segId);
            }
        }
        return routeReverseSegIds.size();
    }

    private List<Integer> safeList(List<Integer> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    record LaboratoryStationLeg(int fromStationId, int toStationId, Direction direction,
            List<Integer> routeIds, double targetPositionM,
            int startSignalId, int targetSignalId) {
        LaboratoryStationLeg {
            routeIds = List.copyOf(routeIds);
        }

        int primaryRouteId() {
            return routeIds.get(0);
        }
    }

    private record RouteSignature(int startSignalId, int endSignalId, List<Integer> axleSectionIds,
            List<Integer> overlapIds, int ciZoneId) {
    }

    private record PathCost(int routeHops, int reverseSwitchTraversals) implements Comparable<PathCost> {
        @Override
        public int compareTo(PathCost other) {
            int byHops = Integer.compare(routeHops, other.routeHops);
            return byHops != 0 ? byHops
                    : Integer.compare(reverseSwitchTraversals,
                            other.reverseSwitchTraversals);
        }
    }

    private record RoutePath(int signalId, int startSignalId, List<Route> routes,
            Set<Integer> visitedSignals, int reverseSwitchTraversals) {
        PathCost cost() {
            return new PathCost(routes.size(), reverseSwitchTraversals);
        }
    }
}
