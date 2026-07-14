package com.bjtu.railtransit.hil;

import com.bjtu.railtransit.energy.TractionPowerSupplyService;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.bjtu.railtransit.signal.domain.SignalAspect;
import com.bjtu.railtransit.signal.service.LineProfileLoader;
import com.bjtu.railtransit.signal.service.MovementAuthorityRegistry;
import com.bjtu.railtransit.signal.service.SignalPlcDepartureService;
import com.bjtu.railtransit.vehicle.dto.TrainState;
import com.bjtu.railtransit.vehicle.protocol704.Protocol704VehicleControlBridge;
import org.springframework.stereotype.Service;

@Service
public class HilSnapshotProvider {
    /**
     * Vision 1.3 main-line edge order from the 2026-07-03 workbook. Positive
     * mileage is the laboratory station 1 -> 13 (physical DOWN / Track 0)
     * workflow. These are Vision edge IDs, not internal CBTC Seg IDs.
     */
    private static final VisionEdge[] POSITIVE_VISION_EDGES = {
            new VisionEdge(3, 0, 215.49), new VisionEdge(4, 215.49, 232.81),
            new VisionEdge(7, 232.81, 300.19), new VisionEdge(11, 300.19, 443.81),
            new VisionEdge(14, 443.81, 511.19), new VisionEdge(17, 511.19, 5274.8396),
            new VisionEdge(21, 5274.8396, 8346.083), new VisionEdge(24, 8346.083, 8687.02908),
            new VisionEdge(28, 8687.02908, 14039.49649), new VisionEdge(36, 14039.49649, 16203.38149),
            new VisionEdge(43, 16203.38149, 16270.76149), new VisionEdge(47, 16270.76149, 16484.57149)
    };
    private static final VisionEdge[] NEGATIVE_VISION_EDGES = {
            new VisionEdge(2, 0, 215.49), new VisionEdge(10, 215.49, 232.81),
            new VisionEdge(13, 232.81, 300.19), new VisionEdge(16, 300.19, 443.81),
            new VisionEdge(19, 443.81, 511.19), new VisionEdge(20, 511.19, 5146.644),
            new VisionEdge(23, 5146.644, 8352.184), new VisionEdge(27, 8352.184, 8632.564),
            new VisionEdge(34, 8632.564, 14119.63), new VisionEdge(38, 14119.63, 14400.14),
            new VisionEdge(42, 14400.14, 16207.82966), new VisionEdge(46, 16207.82966, 16275.20966),
            new VisionEdge(48, 16275.20966, 16489.02)
    };
    private final Protocol704VehicleControlBridge bridge;
    private final MovementAuthorityRegistry maRegistry;
    private final LineProfileLoader lineLoader;
    private final TractionPowerSupplyService powerSupply;
    private final SignalPlcDepartureService signalPlcDeparture;

    public HilSnapshotProvider(Protocol704VehicleControlBridge bridge,
                               MovementAuthorityRegistry maRegistry,
                               LineProfileLoader lineLoader,
                               TractionPowerSupplyService powerSupply,
                               SignalPlcDepartureService signalPlcDeparture) {
        this.bridge = bridge;
        this.maRegistry = maRegistry;
        this.lineLoader = lineLoader;
        this.powerSupply = powerSupply;
        this.signalPlcDeparture = signalPlcDeparture;
    }

    public HilVehicleSnapshot snapshot(String trainId) {
        var control = bridge.snapshot(trainId);
        if (control == null || control.state() == null) return HilVehicleSnapshot.idle(trainId);
        TrainState state = control.state();
        double position = control.absolutePositionM();
        MovingAuthority ma = maRegistry.get(trainId);
        double speedLimit = ma == null ? 0 : ma.getMaxSpeedKmh();
        int signalState = SignalAspect.RED.toDriverConsoleScreen();
        if (ma != null && ma.getCapSignalId() != null) {
            signalState = lineLoader.getLineProfile().getSignals().stream()
                    .filter(signal -> signal.getId() == ma.getCapSignalId())
                    .map(signal -> signal.getAspect() == null ? SignalAspect.RED.toDriverConsoleScreen()
                            : signal.getAspect().toDriverConsoleScreen())
                    .findFirst().orElse(signalState);
        }
        TractionPowerSupplyService.PowerState power = powerSupply.getPowerState(trainId);
        double voltage = power == null ? 1500 : power.voltage();
        double currentA = power == null ? 0 : power.current();
        double forceKn = Math.abs(power == null ? 0 : power.powerKw()) /
                Math.max(0.1, state.getVelocity()) / 1000.0;
        String command = control.lastCommand() == null ? "COAST" : control.lastCommand().toUpperCase();
        int visionDirection = control.toStationId() >= control.fromStationId() ? 1 : -1;
        boolean doorsClosed = control.doorsClosed();
        // Mode selection alone is not an ATO departure. The physical active
        // indication must follow the accepted ATO_START workflow state.
        boolean atoActive = control.atoRunning();
        boolean atoAvailable = signalPlcDeparture.isAtoReady(trainId);
        return new HilVehicleSnapshot(trainId, state.getTime(), position, state.getVelocity(),
                state.getAcceleration(), speedLimit,
                control.currentStationId(), control.nextTargetStationId(), control.toStationId(),
                0, control.mode(), command, (int) Math.round(control.lastLevelPercent()),
                control.emergencyLatched(), doorsClosed, atoAvailable, atoActive,
                voltage > 1000, voltage, currentA, forceKn,
                Math.max(0, control.nextTargetAbsolutePositionM() - position), signalState,
                visionEdgeFor(position, visionDirection), visionDirection);
    }

    /** The Vision computer accepts table-3 edge IDs (1-48), never internal Seg IDs. */
    static int visionEdgeFor(double positionM, int direction) {
        VisionEdge[] candidates = direction < 0 ? NEGATIVE_VISION_EDGES : POSITIVE_VISION_EDGES;
        for (VisionEdge edge : candidates) {
            if (positionM >= edge.startM && positionM <= edge.endM) return edge.id;
        }
        return positionM < candidates[0].startM ? candidates[0].id : candidates[candidates.length - 1].id;
    }

    private record VisionEdge(int id, double startM, double endM) {}
}
