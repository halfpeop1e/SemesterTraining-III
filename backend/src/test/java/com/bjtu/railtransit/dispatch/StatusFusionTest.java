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

    private StatusReport report(String trainId, String deviceId, double timestamp) {
        StatusReport report = new StatusReport();
        report.setTrainId(trainId);
        report.setDeviceId(deviceId);
        report.setTimestampSeconds(timestamp);
        return report;
    }
}
