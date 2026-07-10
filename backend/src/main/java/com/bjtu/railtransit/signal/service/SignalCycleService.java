package com.bjtu.railtransit.signal.service;

import com.bjtu.railtransit.domain.model.TrainState;
import com.bjtu.railtransit.signal.domain.Direction;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.bjtu.railtransit.signal.domain.SignalAspect;
import com.bjtu.railtransit.signal.domain.SignalEvent;
import com.bjtu.railtransit.signal.model.LineProfile;
import com.bjtu.railtransit.signal.model.Route;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Backend-owned signal cycle:
 * dispatch/onboard state -> signal domain -> full teacher-data MA -> ATP constraints.
 */
@Service
public class SignalCycleService {
    private static final double SERVICE_BRAKE_MPS2 = 1.0;
    private static final double EMERGENCY_BRAKE_MPS2 = 1.3;
    private static final double STOP_MARGIN_METERS = 5.0;

    private final MovingAuthorityService movingAuthorityService;
    private final MovementAuthorityRegistry registry;
    private final LineProfile lineProfile;
    private final boolean simulatedInterlocking;
    private final Map<String, Route> activeRoutes = new java.util.concurrent.ConcurrentHashMap<>();

    public SignalCycleService(MovingAuthorityService movingAuthorityService,
                              MovementAuthorityRegistry registry,
                              LineProfileLoader loader,
                              @Value("${rail.signal.simulated-interlocking:true}") boolean simulatedInterlocking)
            throws IOException {
        this.movingAuthorityService = movingAuthorityService;
        this.registry = registry;
        this.lineProfile = loader.loadFromClasspath("line-profile.json");
        this.simulatedInterlocking = simulatedInterlocking;
        if (simulatedInterlocking) {
            // Explicit laboratory-simulation bootstrap. In physical-lab mode this is disabled,
            // and an absent signal aspect remains fail-safe (non-proceed).
            this.lineProfile.getSignals().forEach(s -> s.setAspect(SignalAspect.GREEN));
        }
    }

    public synchronized Map<String, MovingAuthority> runCycle(
            Iterable<TrainState> runtimeTrains, double nowSeconds) {
        List<com.bjtu.railtransit.signal.domain.TrainState> signalTrains = new ArrayList<>();
        for (TrainState runtime : runtimeTrains) {
            if (!runtime.occupiesTrack()) continue;
            Direction direction;
            try {
                direction = Direction.valueOf(runtime.getDirection());
            } catch (Exception ignored) {
                direction = Direction.INVALID;
            }
            com.bjtu.railtransit.signal.domain.TrainState signalTrain =
                    TrainStateAdapter.fromAbsolute(
                            runtime.getTrainId(),
                            runtime.getPositionMeters(),
                            runtime.getSpeedKmh(),
                            direction,
                            runtime.getTrainLengthMeters(),
                            nowSeconds);
            signalTrain.setAccelerationMps2(runtime.getAccelerationMps2());
            signalTrains.add(signalTrain);
        }

        Map<String, MovingAuthority> values = movingAuthorityService.compute(
                lineProfile, signalTrains, activeRoutes, nowSeconds);
        registry.replace(values, nowSeconds,
                simulatedInterlocking ? "SIMULATED_INTERLOCKING" : "LAB_INTERLOCKING");

        for (TrainState runtime : runtimeTrains) {
            MovingAuthority ma = values.get(runtime.getTrainId());
            if (ma != null) applyAtpConstraint(runtime, ma);
        }
        return values;
    }

    private void applyAtpConstraint(TrainState train, MovingAuthority ma) {
        train.setMovementAuthority(ma.getEndOfAuthorityM());
        train.setMaxSpeedLimit(Math.max(0, ma.getMaxSpeedKmh()));

        int sign = train.getDirectionSign();
        double remaining = (ma.getEndOfAuthorityM() - train.getPositionMeters()) * sign;
        double speedMps = train.getSpeedMps();
        double emergencyDistance = speedMps * speedMps / (2 * EMERGENCY_BRAKE_MPS2);

        boolean degraded = ma.getEvent() == SignalEvent.DEGRADED
                || ma.getEvent() == SignalEvent.MA_EXPIRED;
        if (degraded || remaining <= STOP_MARGIN_METERS
                || (speedMps > 0.1 && remaining <= emergencyDistance + STOP_MARGIN_METERS)) {
            train.setEmergencyBraking(true);
            train.setMaxSpeedLimit(0);
            train.setActiveCommand("ATP_EMERGENCY_BRAKE");
            return;
        }

        // Service-braking supervision curve approaching EoA. ATO may run below this value,
        // but can never exceed it.
        double supervisedSpeedKmh = Math.sqrt(
                Math.max(0, 2 * SERVICE_BRAKE_MPS2 * (remaining - STOP_MARGIN_METERS))) * 3.6;
        train.setMaxSpeedLimit(Math.min(train.getMaxSpeedLimit(), supervisedSpeedKmh));
        if (train.isEmergencyBraking() && remaining > emergencyDistance + 20) {
            train.setEmergencyBraking(false);
        }
    }

    public LineProfile getLineProfile() { return lineProfile; }
    public boolean isSimulatedInterlocking() { return simulatedInterlocking; }
    public Map<String, Route> getActiveRoutes() { return Collections.unmodifiableMap(activeRoutes); }
    public void assignRoute(String trainId, Route route) {
        if (route == null) activeRoutes.remove(trainId);
        else activeRoutes.put(trainId, route);
    }
}
