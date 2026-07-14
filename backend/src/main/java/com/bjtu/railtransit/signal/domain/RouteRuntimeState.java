package com.bjtu.railtransit.signal.domain;

/** Immutable snapshot of the mutable interlocking route runtime. */
public record RouteRuntimeState(
        int routeId,
        String trainId,
        RouteLifecycleState state,
        String source,
        String reasonCode,
        String reason,
        double requestedAtSeconds,
        double updatedAtSeconds,
        boolean occupiedOnce) {
}
