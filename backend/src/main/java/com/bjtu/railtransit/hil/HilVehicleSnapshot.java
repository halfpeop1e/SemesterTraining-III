package com.bjtu.railtransit.hil;

/** Canonical simulation state consumed by all laboratory device encoders. */
public record HilVehicleSnapshot(
        String trainId,
        double timestampSeconds,
        double positionM,
        double speedMps,
        double accelerationMps2,
        double speedLimitKmh,
        int currentStationId,
        int nextStationId,
        int terminalStationId,
        int directionCode,
        String mode,
        String handle,
        int handleLevelPercent,
        boolean emergencyBrake,
        boolean doorsClosed,
        boolean atoAvailable,
        boolean atoActive,
        boolean powerAvailable,
        double lineVoltageV,
        double lineCurrentA,
        double tractionForceKn,
        double nextStationDistanceM,
        int nextSignalState
) {
    public static HilVehicleSnapshot idle(String trainId) {
        return new HilVehicleSnapshot(trainId, 0, 0, 0, 0, 0,
                0, 0, 13, 0, "RM", "COAST", 0,
                false, true, false, false, true, 1500, 0, 0, 0, 0x01);
    }
}

