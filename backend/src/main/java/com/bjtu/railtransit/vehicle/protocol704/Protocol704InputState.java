package com.bjtu.railtransit.vehicle.protocol704;

/** Transport and framing state for a protocol704 input stream. */
public enum Protocol704InputState {
    TCP_NOT_CONNECTED,
    TCP_CONNECTING,
    CHANNEL_DISABLED,
    CONNECTED_NO_BYTES,
    PARTIAL_FRAME,
    HEADER_MISMATCH,
    TOTAL_LENGTH_MISMATCH,
    DATA_LENGTH_MISMATCH,
    STRUCTURE_INVALID,
    FIRST_FRAME_RECEIVED,
    FRAME_RECEIVED
}
