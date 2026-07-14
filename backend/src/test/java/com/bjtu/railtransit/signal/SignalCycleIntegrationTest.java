package com.bjtu.railtransit.signal;

import com.bjtu.railtransit.domain.model.TrainState;
import com.bjtu.railtransit.signal.domain.AuthorityBasis;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.bjtu.railtransit.signal.service.LineProfileLoader;
import com.bjtu.railtransit.signal.service.MaConfig;
import com.bjtu.railtransit.signal.service.MovementAuthorityRegistry;
import com.bjtu.railtransit.signal.service.MovingAuthorityService;
import com.bjtu.railtransit.signal.service.SignalCycleService;
import com.bjtu.railtransit.signal.service.SignalEventLog;
import com.bjtu.railtransit.signal.service.SignalInterlockingService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalCycleIntegrationTest {
    @Test
    void unboundTrainIsHeldAtItsCurrentPosition() throws Exception {
        MaConfig config = MaConfig.exampleConfig();
        MovementAuthorityRegistry registry = new MovementAuthorityRegistry();
        LineProfileLoader loader = new LineProfileLoader();
        SignalInterlockingService interlocking = new SignalInterlockingService(loader);
        SignalCycleService cycle = new SignalCycleService(
                new MovingAuthorityService(config), registry, loader, interlocking, new SignalEventLog(), true);

        TrainState following = train("T2", 1_000, 36);
        TrainState leading = train("T1", 2_000, 18);
        Map<String, MovingAuthority> values = cycle.runCycle(List.of(following, leading), 10);

        assertEquals(2, values.size());
        MovingAuthority ma = values.get("T2");
        assertEquals(AuthorityBasis.ROUTE_END, ma.getBasis());
        assertEquals(1_000, ma.getEndOfAuthorityM(), 0.001);
        assertEquals(0, ma.getMaxSpeedKmh(), 0.001);
        assertEquals(ma.getEndOfAuthorityM(), following.getMovementAuthority(), 0.001);
        assertEquals(0, following.getMaxSpeedLimit(), 0.001);
        assertEquals("SIMULATED_INTERLOCKING", registry.getSource());
        assertEquals(1, registry.getGeneration());
    }

    @Test
    void laboratoryModeDoesNotAssumeUnknownSignalsAreClear() throws Exception {
        MaConfig config = MaConfig.exampleConfig();
        MovementAuthorityRegistry registry = new MovementAuthorityRegistry();
        LineProfileLoader loader = new LineProfileLoader();
        SignalInterlockingService interlocking = new SignalInterlockingService(loader);
        SignalCycleService cycle = new SignalCycleService(
                new MovingAuthorityService(config), registry, loader, interlocking, new SignalEventLog(), false);
        TrainState train = train("T1", 0, 0);

        MovingAuthority ma = cycle.runCycle(List.of(train), 1).get("T1");

        assertEquals(AuthorityBasis.ROUTE_END, ma.getBasis());
        assertEquals(train.getPositionMeters(), ma.getEndOfAuthorityM(), 0.001);
        assertEquals(0, ma.getMaxSpeedKmh(), 0.001);
        assertEquals("LAB_INTERLOCKING", registry.getSource());
    }

    @Test
    void localDownDirectionUsesSoftwareStationCorridor() throws Exception {
        MaConfig config = MaConfig.exampleConfig();
        MovementAuthorityRegistry registry = new MovementAuthorityRegistry();
        LineProfileLoader loader = new LineProfileLoader();
        SignalInterlockingService interlocking = new SignalInterlockingService(loader);
        SignalCycleService cycle = new SignalCycleService(
                new MovingAuthorityService(config), registry, loader, interlocking, new SignalEventLog(), true);
        double fromPosition = loader.getLineProfile().getStations().stream()
                .filter(station -> "13".equals(station.getId()))
                .findFirst().orElseThrow().getPositionM();
        double targetPosition = loader.getLineProfile().getStations().stream()
                .filter(station -> "12".equals(station.getId()))
                .findFirst().orElseThrow().getPositionM();
        TrainState train = train("LOCAL_DOWN", fromPosition, 0);
        train.setDirection("DOWN");

        cycle.ensureLocalStationLeg("LOCAL_DOWN", 13, 12, "DOWN", 0);
        MovingAuthority ma = cycle.runCycle(List.of(train), 0).get("LOCAL_DOWN");

        assertTrue(cycle.isDeparturePermitted("LOCAL_DOWN"));
        assertEquals(targetPosition, ma.getEndOfAuthorityM(), 0.001);
        assertTrue(ma.getMaxSpeedKmh() > 0);
    }

    private TrainState train(String id, double position, double speedKmh) {
        TrainState train = new TrainState();
        train.setTrainId(id);
        train.setDirection("UP");
        train.setStatus("CRUISING");
        train.setPositionMeters(position);
        train.setSpeed(speedKmh);
        train.setTrainLengthMeters(140);
        train.setMaxSpeedLimit(80);
        return train;
    }
}
