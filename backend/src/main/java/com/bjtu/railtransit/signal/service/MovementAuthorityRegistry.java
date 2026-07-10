package com.bjtu.railtransit.signal.service;

import com.bjtu.railtransit.signal.domain.MovingAuthority;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Authoritative in-memory MA registry shared by dispatch, onboard and protocol adapters.
 * A production deployment should persist its audit stream, but the latest value must stay
 * in memory so the 100 ms signal cycle does not depend on a database round trip.
 */
@Service
public class MovementAuthorityRegistry {
    private final Map<String, MovingAuthority> latest = new LinkedHashMap<>();
    private double lastCycleTimeSeconds = -1;
    private long generation;
    private String source = "UNINITIALIZED";

    public synchronized void replace(Map<String, MovingAuthority> values,
                                     double cycleTimeSeconds,
                                     String cycleSource) {
        latest.clear();
        latest.putAll(values);
        lastCycleTimeSeconds = cycleTimeSeconds;
        source = cycleSource;
        generation++;
    }

    public synchronized MovingAuthority get(String trainId) {
        return latest.get(trainId);
    }

    public synchronized Map<String, MovingAuthority> snapshot() {
        return new LinkedHashMap<>(latest);
    }

    public synchronized double getLastCycleTimeSeconds() { return lastCycleTimeSeconds; }
    public synchronized long getGeneration() { return generation; }
    public synchronized String getSource() { return source; }

    public synchronized void clear() {
        latest.clear();
        lastCycleTimeSeconds = -1;
        source = "UNINITIALIZED";
        generation = 0;
    }
}
