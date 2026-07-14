package com.bjtu.railtransit.signal.service;

import com.bjtu.railtransit.signal.domain.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LaboratoryStationLegResolverTest {

    @Test
    void resolvesTheVerifiedFirstStationLegToItsFullCbiSequence() {
        LaboratoryStationLegResolver resolver = new LaboratoryStationLegResolver(
                new LineProfileLoader().getLineProfile());

        LaboratoryStationLegResolver.LaboratoryStationLeg leg = resolver.resolve(1, 2, Direction.UP);

        assertEquals(List.of(9, 28), leg.routeIds());
        assertEquals(1_660.520, leg.targetPositionM(), 0.001);
        assertEquals(9, leg.startSignalId());
        assertEquals(62, leg.targetSignalId());
    }

    @Test
    void resolvesTheSecondStationLegWithoutGuessingFromRouteNumbers() {
        LaboratoryStationLegResolver resolver = new LaboratoryStationLegResolver(
                new LineProfileLoader().getLineProfile());

        LaboratoryStationLegResolver.LaboratoryStationLeg leg = resolver.resolve(2, 3, Direction.UP);

        assertEquals(List.of(29), leg.routeIds());
        assertEquals(2_448.610, leg.targetPositionM(), 0.001);
    }

    @Test
    void resolvesOnlyTheVerifiedContiguousRangeFromStationOneToSix() {
        LaboratoryStationLegResolver resolver = new LaboratoryStationLegResolver(
                new LineProfileLoader().getLineProfile());

        for (int fromStationId = 1; fromStationId < 6; fromStationId++) {
            LaboratoryStationLegResolver.LaboratoryStationLeg leg = resolver.resolve(
                    fromStationId, fromStationId + 1, Direction.UP);
            assertEquals(fromStationId, leg.fromStationId());
            assertEquals(fromStationId + 1, leg.toStationId());
        }
    }

    @Test
    void rejectsMissingAndAmbiguousTopologyInsteadOfInventingRouteIds() {
        LaboratoryStationLegResolver resolver = new LaboratoryStationLegResolver(
                new LineProfileLoader().getLineProfile());

        assertEquals("no directed CBI route sequence from station 6 to station 7",
                assertThrows(IllegalArgumentException.class,
                        () -> resolver.resolve(6, 7, Direction.UP)).getMessage());
        assertEquals("ambiguous directed CBI route sequence from station 7 to station 8",
                assertThrows(IllegalArgumentException.class,
                        () -> resolver.resolve(7, 8, Direction.UP)).getMessage());
        assertEquals("ambiguous directed CBI route sequence from station 11 to station 12",
                assertThrows(IllegalArgumentException.class,
                        () -> resolver.resolve(11, 12, Direction.UP)).getMessage());
        assertEquals("no directed CBI route sequence from station 12 to station 13",
                assertThrows(IllegalArgumentException.class,
                        () -> resolver.resolve(12, 13, Direction.UP)).getMessage());
    }

    @Test
    void publishesCapabilitiesAndRejectsHardwareRunsBeyondTheVerifiedRange() {
        SignalInterlockingService interlocking = new SignalInterlockingService(new LineProfileLoader());

        List<SignalInterlockingService.LaboratoryStationLegCapability> capabilities =
                interlocking.getLaboratoryStationLegCapabilities();

        assertEquals(List.of(9, 28), capabilities.get(0).routeIds());
        assertEquals(List.of(29), capabilities.get(1).routeIds());
        assertEquals(true, capabilities.get(4).supported());
        assertEquals(false, capabilities.get(5).supported());
        assertThrows(IllegalArgumentException.class,
                () -> interlocking.requireSupportedLaboratoryRun(1, 7));
        interlocking.requireSupportedLaboratoryRun(1, 6);
    }
}
