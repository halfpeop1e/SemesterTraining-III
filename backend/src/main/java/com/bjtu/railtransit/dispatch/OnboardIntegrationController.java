package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.common.ApiResponse;
import com.bjtu.railtransit.domain.model.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.bjtu.railtransit.signal.service.MovementAuthorityRegistry;

@RestController
@RequestMapping("/api")
public class OnboardIntegrationController {
    private final CommandBus commands;
    private final StatusFusion fusion;
    private final OnboardEventHandler events;
    private final SimulationService simulation;
    private final MovementAuthorityRegistry movementAuthorities;
    private final DispatchEngine dispatchEngine;

    public OnboardIntegrationController(CommandBus commands, StatusFusion fusion,
            OnboardEventHandler events, SimulationService simulation,
            MovementAuthorityRegistry movementAuthorities, DispatchEngine dispatchEngine) {
        this.commands = commands;
        this.fusion = fusion;
        this.events = events;
        this.simulation = simulation;
        this.movementAuthorities = movementAuthorities;
        this.dispatchEngine = dispatchEngine;
    }

    @GetMapping("/dispatch/commands/{trainId}")
    public ApiResponse<List<TrainCommand>> commands(@PathVariable String trainId) {
        return ApiResponse.ok("commands", commands.forTrain(trainId));
    }

    @PostMapping("/dispatch/commands")
    public ApiResponse<TrainCommand> issue(@RequestBody Map<String, Object> body) {
        String trainId = String.valueOf(body.get("trainId"));
        String commandType = String.valueOf(body.get("commandType"));
        if ("DEPART".equals(commandType) && simulation.isDriverDeskControlled(trainId)) {
            return ApiResponse.error("driver-desk-controlled train requires a physical ATO_START");
        }
        String reason = String.valueOf(body.getOrDefault("reason", "Dispatcher request"));
        int priority = (int) number(body.getOrDefault("priority", 50));
        String source = String.valueOf(body.getOrDefault("source", "MANUAL"));
        try {
            // Local signal trains do not have a separate physical onboard process
            // to consume CommandBus messages. Route the dispatch intent into the
            // same ATO state machine used by the signal-page start button.
            if ("DEPART".equals(commandType) && simulation.isLocalSimulationTrain(trainId)) {
                TrainCommand issued = simulation.requestDispatcherDeparture(trainId, reason, priority);
                return ApiResponse.ok("local ATO departure requested", issued);
            }
            TrainCommand issued = commands.issue(
                    trainId, commandType, number(body.get("targetValue")), reason, priority, source,
                    simulation.getSimulationTimeSeconds());
            return ApiResponse.ok("command issued", issued);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/dispatch/commands/confirm")
    public ApiResponse<TrainCommand> confirm(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok("confirmation recorded", commands.confirm(String.valueOf(body.get("commandId")),
                !Boolean.FALSE.equals(body.get("approved")), simulation.getSimulationTimeSeconds()));
    }

    @PostMapping("/dispatch/commands/ack")
    public ApiResponse<TrainCommand> ack(@RequestBody Map<String, Object> body) {
        String id = String.valueOf(body.get("commandId"));
        TrainCommand c = commands.acknowledge(id, !Boolean.FALSE.equals(body.get("accepted")),
                simulation.getSimulationTimeSeconds());
        if (body.get("executionStatus") != null)
            c = commands.updateExecution(id, String.valueOf(body.get("executionStatus")),
                    simulation.getSimulationTimeSeconds());
        return ApiResponse.ok("ack recorded", c);
    }

    @PostMapping("/dispatch/report/status")
    public ApiResponse<String> status(@RequestBody StatusReport report) {
        simulation.acceptOnboardReport(report);
        return ApiResponse.ok("status fused", "ONBOARD_REPORTED");
    }

    @GetMapping("/onboard/monitoring")
    public ApiResponse<List<Map<String, Object>>> monitoring() {
        return ApiResponse.ok("onboard monitoring", currentMonitoring());
    }

    @PostMapping("/dispatch/report/event")
    public ApiResponse<String> event(@RequestBody OnboardEvent event) {
        events.accept(event);
        return ApiResponse.ok("event accepted", event.getEventId());
    }

    @GetMapping("/onboard/{trainId}/snapshot")
    public ApiResponse<Map<String, Object>> onboard(@PathVariable String trainId) {
        TrainState train = simulation.findTrain(trainId);
        MovingAuthority ma = movementAuthorities.get(trainId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("train", train);
        data.put("commands", commands.forTrain(trainId));
        data.put("currentTimeSeconds", simulation.getSimulationTimeSeconds());
        data.put("movementAuthorityMeters", ma == null ? 0 : ma.getEndOfAuthorityM());
        data.put("speedLimitKmh", ma == null ? 0 : ma.getMaxSpeedKmh());
        data.put("movementAuthority", ma);
        data.put("communicationStatus", simulation.isLocalSimulationTrain(trainId)
                ? "LOCAL_SIMULATION" : fusion.communicationStale(trainId) ? "STALE" : "ONLINE");
        data.put("safetyStatus", train != null && train.isEmergencyBraking() ? "EMERGENCY" : "NORMAL");
        // 该车辆的时刻表
        List<Map<String, Object>> timetableList = new ArrayList<>();
        Map<Integer, DispatchEngine.TimetableEntry> trainTimetable = dispatchEngine.getTimetable() != null
                ? dispatchEngine.getTimetable().get(trainId)
                : null;
        if (trainTimetable != null) {
            for (DispatchEngine.TimetableEntry te : trainTimetable.values()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("stationId", te.stationId);
                item.put("stationName", te.stationName);
                item.put("stationIndex", te.stationIndex);
                item.put("stationKm", te.stationKm);
                item.put("plannedArrival", te.plannedArrival);
                item.put("plannedDeparture", te.plannedDeparture);
                item.put("plannedDwell", te.plannedDwell);
                timetableList.add(item);
            }
        }
        data.put("timetable", timetableList);
        return ApiResponse.ok("onboard snapshot", data);
    }

    @GetMapping("/dispatch/workstation")
    public ApiResponse<Map<String, Object>> workstation() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("automaticOperation", simulation.isRunning());
        data.put("commands", commands.all());
        data.put("pendingManualConfirmations", commands.pendingConfirmations());
        data.put("onboardReports", fusion.reports());
        data.put("onboardEvents", events.events());
        List<Map<String, Object>> monitoring = currentMonitoring();
        data.put("onboardMonitoring", monitoring);
        boolean communicationHealthy = monitoring.stream()
                .anyMatch(item -> Boolean.TRUE.equals(item.get("online")));
        data.put("communicationStatus", Map.of("mode", "HTTP_ONBOARD", "healthy", communicationHealthy));
        data.put("protocolAdapterStatus",
                Map.of("signal", "READY", "vehicle", "READY", "driverDesk", "READY", "vision", "READY"));
        return ApiResponse.ok("dispatcher workstation", data);
    }

    private List<Map<String, Object>> currentMonitoring() {
        List<Map<String, Object>> monitoring = new ArrayList<>(fusion.monitoring());
        Set<String> reportedTrainIds = new LinkedHashSet<>();
        for (Map<String, Object> item : monitoring) {
            Object trainId = item.get("trainId");
            if (trainId != null) reportedTrainIds.add(String.valueOf(trainId));
        }
        monitoring.addAll(simulation.getLocalOnboardMonitoring(reportedTrainIds));
        return monitoring;
    }

    private double number(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0;
    }
}
