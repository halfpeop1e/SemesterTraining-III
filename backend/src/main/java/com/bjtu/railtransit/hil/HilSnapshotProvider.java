package com.bjtu.railtransit.hil;

import com.bjtu.railtransit.energy.TractionPowerSupplyService;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.bjtu.railtransit.signal.domain.SignalAspect;
import com.bjtu.railtransit.signal.model.Station;
import com.bjtu.railtransit.signal.service.LineProfileLoader;
import com.bjtu.railtransit.signal.service.MovementAuthorityRegistry;
import com.bjtu.railtransit.signal.service.SignalPlcDepartureService;
import com.bjtu.railtransit.vehicle.dto.TrainState;
import com.bjtu.railtransit.vehicle.protocol704.Protocol704VehicleControlBridge;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class HilSnapshotProvider {
    /**
     * Vision 1.3 table 3 main-line edge order. These are Vision edge IDs,
     * not the 319 internal CBTC Seg IDs used by the signal model.
     */
    private static final VisionEdge[] POSITIVE_VISION_EDGES = {
            new VisionEdge(2, 0, 215.49), new VisionEdge(10, 215.49, 232.81),
            new VisionEdge(13, 232.81, 300.19), new VisionEdge(16, 300.19, 443.81),
            new VisionEdge(18, 443.81, 511.6664), new VisionEdge(17, 511.19, 5274.8396),
            new VisionEdge(21, 5274.8396, 8346.083), new VisionEdge(24, 8346.083, 8687.02908),
            new VisionEdge(28, 8687.02908, 14039.49649), new VisionEdge(35, 14039.49649, 14176.15854),
            new VisionEdge(39, 14176.15854, 14327.05088), new VisionEdge(40, 14327.05088, 14396.04781),
            new VisionEdge(42, 14400.14, 16207.82966), new VisionEdge(45, 16207.82966, 16277.75286),
            new VisionEdge(47, 16270.76149, 16484.57149)
    };
    private static final VisionEdge[] NEGATIVE_VISION_EDGES = {
            new VisionEdge(3, 0, 215.49), new VisionEdge(4, 215.49, 232.81),
            new VisionEdge(7, 232.81, 300.19), new VisionEdge(11, 300.19, 443.81),
            new VisionEdge(14, 443.81, 511.19), new VisionEdge(20, 511.19, 5146.644),
            new VisionEdge(23, 5146.644, 8352.184), new VisionEdge(27, 8352.184, 8632.564),
            new VisionEdge(34, 8632.564, 14119.63), new VisionEdge(37, 14119.63, 14180.69396),
            new VisionEdge(39, 14176.15854, 14327.05088), new VisionEdge(40, 14327.05088, 14396.04781),
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
        double position = state.getAbsolutePosition() != null ? state.getAbsolutePosition() : state.getPosition();
        List<Station> stations = lineLoader.getLineProfile().getStations().stream()
                .sorted(Comparator.comparingDouble(Station::getPositionM)).toList();
        Station current = stations.isEmpty() ? null : stations.get(0);
        Station next = stations.isEmpty() ? null : stations.get(stations.size() - 1);
        for (Station station : stations) {
            if (station.getPositionM() <= position) current = station;
            if (station.getPositionM() > position) { next = station; break; }
        }
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
        boolean atoActive = "ATO".equalsIgnoreCase(control.mode());
        boolean atoAvailable = signalPlcDeparture.isAtoReady(trainId);
        return new HilVehicleSnapshot(trainId, state.getTime(), position, state.getVelocity(),
                state.getAcceleration(), speedLimit,
                current == null ? 0 : Integer.parseInt(current.getId()),
                next == null ? 0 : Integer.parseInt(next.getId()),
                stations.isEmpty() ? 13 : Integer.parseInt(stations.get(stations.size() - 1).getId()),
                0, control.mode(), command, (int) Math.round(control.lastLevelPercent()),
                control.emergencyLatched(), doorsClosed, atoAvailable, atoActive,
                voltage > 1000, voltage, currentA, forceKn,
                next == null ? 0 : Math.max(0, next.getPositionM() - position), signalState,
                visionEdgeFor(position, visionDirection), visionDirection);
    }

    /** Other trains use the variable-length Vision 1.3 tail, 9 bytes per train. */
    public List<TeacherDeviceFrameCodec.VisionOtherTrain> otherTrains(String ownTrainId) {
        return bridge.activeLaboratoryTrainIds().stream()
                .filter(trainId -> !trainId.equals(ownTrainId))
                .map(this::snapshot)
                .map(state -> new TeacherDeviceFrameCodec.VisionOtherTrain(
                        state.positionM(), state.visionEdgeId(), state.visionDirection(), state.speedMps()))
                .toList();
    }

    /** The Vision computer accepts table-3 edge IDs (1-48), never internal Seg IDs. */
    private static int visionEdgeFor(double positionM, int direction) {
        VisionEdge[] candidates = direction < 0 ? NEGATIVE_VISION_EDGES : POSITIVE_VISION_EDGES;
        for (VisionEdge edge : candidates) {
            if (positionM >= edge.startM && positionM <= edge.endM) return edge.id;
        }
        return positionM < candidates[0].startM ? candidates[0].id : candidates[candidates.length - 1].id;
    }

    private record VisionEdge(int id, double startM, double endM) {}
}

