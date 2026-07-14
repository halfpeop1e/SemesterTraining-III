package com.bjtu.railtransit.signal.service;

import com.bjtu.railtransit.signal.domain.Direction;
import com.bjtu.railtransit.signal.model.LineProfile;
import com.bjtu.railtransit.signal.model.Platform;
import com.bjtu.railtransit.signal.model.Route;
import com.bjtu.railtransit.signal.model.Signal;
import com.bjtu.railtransit.signal.model.Station;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves one physical laboratory station leg to a CBI route sequence.
 *
 * <p>The supplied data has platform Seg IDs and directed CBI routes but no
 * direct station-to-route table. This resolver accepts a leg only when those
 * two sources produce one unambiguous shortest directed sequence. It never
 * derives a station target from a route ID or from the synthetic Seg mileage.
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
        if (direction != Direction.UP) {
            throw new IllegalArgumentException("laboratory station-leg adapter currently supports UP only");
        }
        if (toStationId != fromStationId + 1) {
            throw new IllegalArgumentException("laboratory station leg must use adjacent stations");
        }

        Station fromStation = station(fromStationId);
        Station toStation = station(toStationId);
        int platformDirection = direction == Direction.UP ? UP_PLATFORM_DIRECTION : DOWN_PLATFORM_DIRECTION;
        Platform fromPlatform = uniquePlatform(fromStation, platformDirection, "source");
        Platform toPlatform = uniquePlatform(toStation, platformDirection, "target");
        int startSignalId = uniqueSignalOnPlatform(fromPlatform, platformDirection, "source");
        int targetSignalId = uniqueSignalOnPlatform(toPlatform, platformDirection, "target");

        List<List<Integer>> candidates = shortestRouteSequences(startSignalId, targetSignalId);
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("no directed CBI route sequence from station "
                    + fromStationId + " to station " + toStationId);
        }
        if (candidates.size() != 1) {
            throw new IllegalArgumentException("ambiguous directed CBI route sequence from station "
                    + fromStationId + " to station " + toStationId);
        }
        return new LaboratoryStationLeg(fromStationId, toStationId, direction,
                candidates.get(0), toStation.getPositionM(), startSignalId, targetSignalId);
    }

    private Station station(int stationId) {
        return lineProfile.getStations().stream()
                .filter(station -> String.valueOf(stationId).equals(station.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown station " + stationId));
    }

    private Platform uniquePlatform(Station station, int direction, String role) {
        Set<Integer> platformIds = new HashSet<>(station.getPlatformIds());
        List<Platform> matches = lineProfile.getPlatforms().stream()
                .filter(platform -> platformIds.contains(platform.getId()))
                .filter(platform -> platform.getDir() == direction)
                .toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException("station " + station.getId() + " has no unique "
                    + role + " platform for this direction");
        }
        return matches.get(0);
    }

    private int uniqueSignalOnPlatform(Platform platform, int direction, String role) {
        List<Signal> matches = lineProfile.getSignals().stream()
                .filter(signal -> signal.getSegId() == platform.getSegId())
                .filter(signal -> signal.getProtectDir() == direction)
                .toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException("platform " + platform.getId() + " has no unique "
                    + role + " signal for this direction");
        }
        return matches.get(0).getId();
    }

    private List<List<Integer>> shortestRouteSequences(int startSignalId, int targetSignalId) {
        Map<Integer, List<Route>> outgoing = new HashMap<>();
        for (Route route : lineProfile.getRoutes()) {
            outgoing.computeIfAbsent(route.getStartSignalId(), ignored -> new ArrayList<>()).add(route);
        }
        for (List<Route> routes : outgoing.values()) {
            routes.sort(Comparator.comparingInt(Route::getId));
        }

        ArrayDeque<RoutePath> pending = new ArrayDeque<>();
        pending.add(new RoutePath(startSignalId, List.of(), Set.of(startSignalId)));
        List<List<Integer>> matches = new ArrayList<>();
        int shortestLength = Integer.MAX_VALUE;
        while (!pending.isEmpty()) {
            RoutePath current = pending.removeFirst();
            if (current.routeIds().size() >= shortestLength || current.routeIds().size() >= MAX_ROUTE_HOPS) {
                continue;
            }
            for (Route route : outgoing.getOrDefault(current.signalId(), List.of())) {
                List<Integer> nextPath = new ArrayList<>(current.routeIds());
                nextPath.add(route.getId());
                if (route.getEndSignalId() == targetSignalId) {
                    shortestLength = nextPath.size();
                    matches.add(nextPath);
                    continue;
                }
                if (current.visitedSignals().contains(route.getEndSignalId())) continue;
                Set<Integer> visited = new HashSet<>(current.visitedSignals());
                visited.add(route.getEndSignalId());
                pending.addLast(new RoutePath(route.getEndSignalId(), nextPath, visited));
            }
        }
        int finalShortestLength = shortestLength;
        return matches.stream().filter(path -> path.size() == finalShortestLength).toList();
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

    private record RoutePath(int signalId, List<Integer> routeIds, Set<Integer> visitedSignals) {}
}
