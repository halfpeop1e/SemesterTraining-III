package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.domain.model.StatusReport;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class StatusFusion {
    /** Latest report by train, retained for legacy fusion and command lookup. */
    private final Map<String, StatusReport> reports = new LinkedHashMap<>();
    /** Monitoring is keyed by physical/software device so duplicate train claims stay visible. */
    private final Map<String, StatusReport> deviceReports = new LinkedHashMap<>();
    private final Map<String, Long> deviceReceivedAtMillis = new LinkedHashMap<>();
    private final Map<String, Long> trainReceivedAtMillis = new LinkedHashMap<>();
    /**
     * Server-assigned report revision.  A vehicle's local playback clock may be
     * paused, accelerated or restarted, so it must not be used to order reports
     * received by the control centre.
     */
    private final Map<String, Long> trainRevisions = new LinkedHashMap<>();
    private long nextRevision = 0;

    public synchronized void accept(StatusReport report) {
        if (report == null || report.getTrainId() == null || report.getTrainId().isBlank()) {
            throw new IllegalArgumentException("trainId is required");
        }
        long now = System.currentTimeMillis();
        reports.put(report.getTrainId(), report);
        trainReceivedAtMillis.put(report.getTrainId(), now);
        trainRevisions.put(report.getTrainId(), ++nextRevision);
        String deviceKey = report.getDeviceId() == null || report.getDeviceId().isBlank()
            ? "LEGACY-" + report.getTrainId()
            : report.getDeviceId();
        deviceReports.put(deviceKey, report);
        deviceReceivedAtMillis.put(deviceKey, now);
    }
    public synchronized List<StatusReport> reports() { return new ArrayList<>(reports.values()); }
    public synchronized StatusReport latest(String trainId) { return reports.get(trainId); }
    /** Monotonically increases on every server-received report for this train. */
    public synchronized long revision(String trainId) { return trainRevisions.getOrDefault(trainId, 0L); }

    /** Remove one train's cached reports so a deleted simulation is not recreated by stale telemetry. */
    public synchronized void remove(String trainId) {
        if (trainId == null || trainId.isBlank()) return;
        reports.remove(trainId);
        trainReceivedAtMillis.remove(trainId);
        trainRevisions.remove(trainId);
        deviceReports.entrySet().removeIf(entry -> trainId.equals(entry.getValue().getTrainId()));
        deviceReceivedAtMillis.keySet().removeIf(deviceKey -> !deviceReports.containsKey(deviceKey));
    }

    /** Clear all cached reports as part of a full simulation reset. */
    public synchronized void clear() {
        reports.clear();
        deviceReports.clear();
        deviceReceivedAtMillis.clear();
        trainReceivedAtMillis.clear();
        trainRevisions.clear();
        nextRevision = 0;
    }

    public synchronized List<Map<String, Object>> monitoring() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> result = new ArrayList<>();
        deviceReports.forEach((deviceKey, report) -> {
            long received = deviceReceivedAtMillis.getOrDefault(deviceKey, 0L);
            double ageSeconds = received == 0 ? Double.POSITIVE_INFINITY : (now - received) / 1000.0;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("trainId", report.getTrainId());
            item.put("deviceId", deviceKey);
            item.put("sourceType", report.getSourceType() == null ? "ONBOARD_SOFTWARE" : report.getSourceType());
            item.put("online", ageSeconds <= 10);
            item.put("ageSeconds", ageSeconds);
            item.put("receivedAtEpochMillis", received);
            item.put("report", report);
            result.add(item);
        });
        return result;
    }

    public synchronized boolean communicationStale(String trainId) {
        Long received = trainReceivedAtMillis.get(trainId);
        return received == null || System.currentTimeMillis() - received > 10_000;
    }

    public synchronized boolean stale(String trainId, double now) {
        StatusReport r = reports.get(trainId);
        return r == null || now - r.getTimestampSeconds() > 10;
    }
}
