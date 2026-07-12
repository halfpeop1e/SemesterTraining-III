package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.domain.model.StatusReport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusFusionTest {

    @Test
    void keepsSeparateOnboardDevicesThatClaimTheSameTrainId() {
        StatusFusion fusion = new StatusFusion();
        fusion.accept(report("T1", "HMI-T1-A", 10));
        fusion.accept(report("T1", "HMI-T1-B", 11));

        assertEquals(2, fusion.monitoring().size());
        assertEquals("HMI-T1-B", fusion.latest("T1").getDeviceId());
        assertTrue(fusion.monitoring().stream()
            .allMatch(item -> Boolean.TRUE.equals(item.get("online"))));
    }

    @Test
    void serverRevisionAdvancesEvenWhenVehicleTimestampResets() {
        StatusFusion fusion = new StatusFusion();
        fusion.accept(report("T1", "HMI-T1-A", 800));
        long first = fusion.revision("T1");

        // A restarted HMI begins its own local timeline at zero.  It is still
        // the newest report observed by the control centre.
        fusion.accept(report("T1", "HMI-T1-A", 0));

        assertTrue(fusion.revision("T1") > first);
        assertEquals(0, fusion.latest("T1").getTimestampSeconds());
    }

    @Test
    void clearRemovesCachedReportsAndMonitoringDevices() {
        StatusFusion fusion = new StatusFusion();
        fusion.accept(report("T1", "HMI-T1-A", 10));
        fusion.accept(report("T2", "HMI-T2-A", 11));

        fusion.remove("T1");
        assertEquals(1, fusion.reports().size());
        assertEquals(1, fusion.monitoring().size());
        assertEquals("T2", fusion.latest("T2").getTrainId());

        fusion.clear();
        assertTrue(fusion.reports().isEmpty());
        assertTrue(fusion.monitoring().isEmpty());
    }

    @Test
    void departureCommandIsNotDuplicatedUntilExecutionCompletes() {
        CommandBus bus = new CommandBus();
        bus.issue("OB1", "DEPART", 0, "ATS authorization", 100, "ATS", 30);
        assertTrue(bus.hasOpenCommand("OB1", "DEPART"));

        bus.completeOpenCommands("OB1", "DEPART", 31);
        assertTrue(!bus.hasOpenCommand("OB1", "DEPART"));
    }

    private StatusReport report(String trainId, String deviceId, double timestamp) {
        StatusReport report = new StatusReport();
        report.setTrainId(trainId);
        report.setDeviceId(deviceId);
        report.setTimestampSeconds(timestamp);
        return report;
    }
}
