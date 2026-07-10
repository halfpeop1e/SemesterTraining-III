package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.domain.model.OnboardEvent;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class OnboardEventHandler {
    private final List<OnboardEvent> events = new ArrayList<>();
    private final CommandBus commandBus;
    public OnboardEventHandler(CommandBus commandBus) { this.commandBus = commandBus; }
    public synchronized void accept(OnboardEvent e) {
        if (e.getEventId() == null) e.setEventId(UUID.randomUUID().toString());
        events.add(e);
        if ("CRITICAL".equalsIgnoreCase(e.getSeverity()) || "ATP_EB_TRIGGERED".equals(e.getEventType())) {
            commandBus.issue(e.getTrainId(), "FORCE_HOLD", 0,
                "Onboard event: " + e.getEventType(), 100, "SAFETY_GUARD", e.getTimestampSeconds());
        }
    }
    public synchronized List<OnboardEvent> events() { return new ArrayList<>(events); }
}
