package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.domain.model.TrainCommand;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class CommandBus {
    private static final Set<String> HIGH_RISK = Set.of(
        "SKIP_STATION", "SHORT_TURN", "CLEAR_PASSENGERS", "FORCE_HOLD",
        "DEGRADED_MODE", "EMERGENCY_RECOVERY");
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "REJECTED", "SUPERSEDED");
    private final Map<String, TrainCommand> commands = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public synchronized TrainCommand issue(String trainId, String type, double target, String reason,
                                           int priority, String source, double now) {
        // 去重：将同一列车同类指令的旧活跃指令标记为 SUPERSEDED
        for (TrainCommand existing : commands.values()) {
            if (trainId.equals(existing.getTrainId())
                    && type.equals(existing.getCommandType())
                    && !TERMINAL.contains(existing.getStatus())) {
                existing.setStatus("SUPERSEDED");
            }
        }

        TrainCommand c = new TrainCommand();
        c.setCommandId("CMD-" + sequence.incrementAndGet());
        c.setTrainId(trainId); c.setCommandType(type); c.setTargetValue(target);
        c.setReason(reason); c.setPriority(priority); c.setSource(source);
        c.setIssuedTimeSeconds(now);
        c.setStatus(HIGH_RISK.contains(type) ? "CONFIRM_REQUIRED" : "PENDING");
        commands.put(c.getCommandId(), c);
        return c;
    }

    public synchronized TrainCommand confirm(String id, boolean approved, double now) {
        TrainCommand c = require(id);
        if (!"CONFIRM_REQUIRED".equals(c.getStatus())) return c;
        c.setStatus(approved ? "CONFIRMED" : "REJECTED");
        c.setConfirmedTimeSeconds(now);
        return c;
    }

    public synchronized TrainCommand acknowledge(String id, boolean accepted, double now) {
        TrainCommand c = require(id);
        if ("CONFIRM_REQUIRED".equals(c.getStatus())) {
            throw new IllegalStateException("High-risk command requires dispatcher confirmation");
        }
        c.setStatus(accepted ? "ACKNOWLEDGED" : "REJECTED");
        c.setAcknowledgedTimeSeconds(now);
        return c;
    }

    public synchronized TrainCommand updateExecution(String id, String status, double now) {
        if (!Set.of("EXECUTING", "COMPLETED", "REJECTED").contains(status))
            throw new IllegalArgumentException("Unsupported execution status");
        TrainCommand c = require(id);
        c.setStatus(status);
        if ("COMPLETED".equals(status)) c.setCompletedTimeSeconds(now);
        return c;
    }

    public synchronized List<TrainCommand> forTrain(String trainId) {
        return commands.values().stream().filter(c -> trainId.equals(c.getTrainId()))
            .filter(c -> !TERMINAL.contains(c.getStatus())).toList();
    }
    public synchronized List<TrainCommand> all() { return new ArrayList<>(commands.values()); }
    public synchronized List<TrainCommand> pendingConfirmations() {
        return commands.values().stream().filter(c -> "CONFIRM_REQUIRED".equals(c.getStatus())).toList();
    }
    private TrainCommand require(String id) {
        TrainCommand c = commands.get(id);
        if (c == null) throw new NoSuchElementException("Unknown command: " + id);
        return c;
    }
}
