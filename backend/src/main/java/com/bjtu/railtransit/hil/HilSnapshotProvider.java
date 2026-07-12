package com.bjtu.railtransit.hil;

import com.bjtu.railtransit.energy.TractionPowerSupplyService;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.bjtu.railtransit.signal.domain.SignalAspect;
import com.bjtu.railtransit.signal.model.Station;
import com.bjtu.railtransit.signal.service.LineProfileLoader;
import com.bjtu.railtransit.signal.service.MovementAuthorityRegistry;
import com.bjtu.railtransit.vehicle.dto.TrainState;
import com.bjtu.railtransit.vehicle.protocol704.Protocol704VehicleControlBridge;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class HilSnapshotProvider {
    private final Protocol704VehicleControlBridge bridge;
    private final MovementAuthorityRegistry maRegistry;
    private final LineProfileLoader lineLoader;
    private final TractionPowerSupplyService powerSupply;

    public HilSnapshotProvider(Protocol704VehicleControlBridge bridge,
                               MovementAuthorityRegistry maRegistry,
                               LineProfileLoader lineLoader,
                               TractionPowerSupplyService powerSupply) {
        this.bridge = bridge;
        this.maRegistry = maRegistry;
        this.lineLoader = lineLoader;
        this.powerSupply = powerSupply;
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
        return new HilVehicleSnapshot(trainId, state.getTime(), position, state.getVelocity(),
                state.getAcceleration(), speedLimit,
                current == null ? 0 : Integer.parseInt(current.getId()),
                next == null ? 0 : Integer.parseInt(next.getId()),
                stations.isEmpty() ? 13 : Integer.parseInt(stations.get(stations.size() - 1).getId()),
                0, control.mode(), command, (int) Math.round(control.lastLevelPercent()),
                control.emergencyLatched(), true, true, "ATO".equalsIgnoreCase(control.mode()),
                voltage > 1000, voltage, currentA, forceKn,
                next == null ? 0 : Math.max(0, next.getPositionM() - position), signalState);
    }
}

