package com.bjtu.railtransit.signal.domain;

public enum AuthorityBasis {
    LINE_LIMIT, TSR, PRECEDING_TRAIN, SWITCH, TURNBACK_END,
    SIGNAL, ROUTE_END, OVERLAP_END, AXLE_OCCUPIED
}
