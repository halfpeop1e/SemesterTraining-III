package com.bjtu.railtransit.signal.domain;

/** Runtime lifecycle of one interlocking route. */
public enum RouteLifecycleState {
    REQUESTED,
    ESTABLISHED,
    OCCUPIED,
    RELEASING,
    COMPLETED,
    CANCELLED,
    REJECTED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == REJECTED || this == FAILED;
    }

    public boolean grantsMovement() {
        return this == ESTABLISHED || this == OCCUPIED || this == RELEASING;
    }
}
