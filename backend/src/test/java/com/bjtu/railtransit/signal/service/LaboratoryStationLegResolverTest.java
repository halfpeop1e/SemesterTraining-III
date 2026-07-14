package com.bjtu.railtransit.signal.service;

import com.bjtu.railtransit.signal.domain.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LaboratoryStationLegResolverTest {

    @Test
    void resolvesTheVerifiedFirstStationLegToItsFullCbiSequence() {
        LaboratoryStationLegResolver resolver = new LaboratoryStationLegResolver(
                new LineProfileLoader().getLineProfile());

        LaboratoryStationLegResolver.LaboratoryStationLeg leg = resolver.resolve(1, 2, Direction.UP);

        assertEquals(List.of(9, 28), leg.routeIds());
        assertEquals(1_719.520, leg.targetPositionM(), 0.001);
        assertEquals(9, leg.startSignalId());
        assertEquals(62, leg.targetSignalId());
    }

    @Test
    void resolvesTheSecondStationLegWithoutGuessingFromRouteNumbers() {
        LaboratoryStationLegResolver resolver = new LaboratoryStationLegResolver(
                new LineProfileLoader().getLineProfile());

        LaboratoryStationLegResolver.LaboratoryStationLeg leg = resolver.resolve(2, 3, Direction.UP);

        assertEquals(List.of(29), leg.routeIds());
        assertEquals(2_507.610, leg.targetPositionM(), 0.001);
    }

    @Test
    void resolvesTheCompleteVerifiedMainlineFromStationOneToThirteen() {
        LaboratoryStationLegResolver resolver = new LaboratoryStationLegResolver(
                new LineProfileLoader().getLineProfile());
        Map<Integer, List<Integer>> expectedRoutes = Map.ofEntries(
                Map.entry(1, List.of(9, 28)),
                Map.entry(2, List.of(29)),
                Map.entry(3, List.of(36)),
                Map.entry(4, List.of(37, 38)),
                Map.entry(5, List.of(39, 48)),
                Map.entry(6, List.of(49, 50)),
                Map.entry(7, List.of(51, 53, 55)),
                Map.entry(8, List.of(65)),
                Map.entry(9, List.of(66, 67, 72)),
                Map.entry(10, List.of(73, 74)),
                Map.entry(11, List.of(75, 77)),
                Map.entry(12, List.of(89)));

        for (int fromStationId = 1; fromStationId < 13; fromStationId++) {
            LaboratoryStationLegResolver.LaboratoryStationLeg leg = resolver.resolve(
                    fromStationId, fromStationId + 1, Direction.UP);
            assertEquals(fromStationId, leg.fromStationId());
            assertEquals(fromStationId + 1, leg.toStationId());
            assertEquals(expectedRoutes.get(fromStationId), leg.routeIds());
        }
    }

    @Test
    void resolvesEveryDownDirectionLegForTheLocalSimulation() {
        LaboratoryStationLegResolver resolver = new LaboratoryStationLegResolver(
                new LineProfileLoader().getLineProfile());

        for (int fromStationId = 13; fromStationId > 1; fromStationId--) {
            LaboratoryStationLegResolver.LaboratoryStationLeg leg = resolver.resolve(
                    fromStationId, fromStationId - 1, Direction.DOWN);
            assertEquals(fromStationId, leg.fromStationId());
            assertEquals(fromStationId - 1, leg.toStationId());
            assertEquals(Direction.DOWN, leg.direction());
        }
    }

    @Test
    void followsNormalMainlineAndCanonicalizesDuplicateCbiRows() {
        LaboratoryStationLegResolver resolver = new LaboratoryStationLegResolver(
                new LineProfileLoader().getLineProfile());

        LaboratoryStationLegResolver.LaboratoryStationLeg sixToSeven =
                resolver.resolve(6, 7, Direction.UP);
        assertEquals(List.of(49, 50), sixToSeven.routeIds());
        assertEquals(69, sixToSeven.startSignalId());
        assertEquals(72, sixToSeven.targetSignalId());

        LaboratoryStationLegResolver.LaboratoryStationLeg sevenToEight =
                resolver.resolve(7, 8, Direction.UP);
        assertEquals(List.of(51, 53, 55), sevenToEight.routeIds());
        assertEquals(72, sevenToEight.startSignalId());
        assertEquals(78, sevenToEight.targetSignalId());

        assertEquals(List.of(75, 77), resolver.resolve(11, 12, Direction.UP).routeIds());
        LaboratoryStationLegResolver.LaboratoryStationLeg terminal =
                resolver.resolve(12, 13, Direction.UP);
        assertEquals(List.of(89), terminal.routeIds());
        assertEquals(95, terminal.targetSignalId());
    }

    @Test
    void publishesCapabilitiesForTheCompleteLaboratoryMainline() {
        SignalInterlockingService interlocking = new SignalInterlockingService(new LineProfileLoader());

        List<SignalInterlockingService.LaboratoryStationLegCapability> capabilities =
                interlocking.getLaboratoryStationLegCapabilities();

        assertEquals(List.of(9, 28), capabilities.get(0).routeIds());
        assertEquals(List.of(29), capabilities.get(1).routeIds());
        assertEquals(12, capabilities.size());
        assertEquals(true, capabilities.stream().allMatch(
                SignalInterlockingService.LaboratoryStationLegCapability::supported));
        assertDoesNotThrow(() -> interlocking.requireSupportedLaboratoryRun(1, 13));
    }

    @Test
    void establishesAndCancelsEveryMainlineLegTransactionally() {
        SignalInterlockingService interlocking = new SignalInterlockingService(new LineProfileLoader());

        for (int fromStationId = 1; fromStationId < 13; fromStationId++) {
            String trainId = "LEG-" + fromStationId;
            List<Integer> established = interlocking.buildAndAssignLaboratoryStationLeg(
                            trainId, fromStationId, fromStationId + 1, fromStationId)
                    .stream().map(com.bjtu.railtransit.signal.model.Route::getId).toList();
            List<Integer> cancelled = interlocking.cancelLaboratoryStationLeg(trainId, fromStationId + 0.5)
                    .stream().map(com.bjtu.railtransit.signal.model.Route::getId).toList();

            assertEquals(established, cancelled);
        }
    }
}
