package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.common.ApiResponse;
import com.bjtu.railtransit.domain.model.*;
import com.bjtu.railtransit.signal.service.SignalPlcDepartureService;
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
    private final SignalPlcDepartureService signalPlcDepartureService;

    public OnboardIntegrationController(CommandBus commands, StatusFusion fusion,
            OnboardEventHandler events, SimulationService simulation,
            MovementAuthorityRegistry movementAuthorities, DispatchEngine dispatchEngine,
            SignalPlcDepartureService signalPlcDepartureService) {
        this.commands = commands;
        this.fusion = fusion;
        this.events = events;
        this.simulation = simulation;
        this.movementAuthorities = movementAuthorities;
        this.dispatchEngine = dispatchEngine;
        this.signalPlcDepartureService = signalPlcDepartureService;
    }

    @GetMapping("/dispatch/commands/{trainId}")
    public ApiResponse<List<TrainCommand>> commands(@PathVariable String trainId) {
        return ApiResponse.ok("commands", commands.forTrain(trainId));
    }

    @PostMapping("/dispatch/commands")
    public ApiResponse<TrainCommand> issue(@RequestBody Map<String, Object> body) {
        TrainCommand issued = commands.issue(
                String.valueOf(body.get("trainId")), String.valueOf(body.get("commandType")),
                number(body.get("targetValue")), String.valueOf(body.getOrDefault("reason", "Dispatcher request")),
                (int) number(body.getOrDefault("priority", 50)), String.valueOf(body.getOrDefault("source", "MANUAL")),
                simulation.getSimulationTimeSeconds());
        if ("DEPART".equalsIgnoreCase(issued.getCommandType())) {
            signalPlcDepartureService.onDepartureAuthorized(issued.getTrainId());
        }
        return ApiResponse.ok("command issued", issued);
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
        return ApiResponse.ok("onboard monitoring", fusion.monitoring());
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
        data.put("communicationStatus", fusion.communicationStale(trainId) ? "STALE" : "ONLINE");
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
        data.put("onboardMonitoring", fusion.monitoring());
        boolean communicationHealthy = fusion.monitoring().stream()
                .anyMatch(item -> Boolean.TRUE.equals(item.get("online")));
        data.put("communicationStatus", Map.of("mode", "HTTP_ONBOARD", "healthy", communicationHealthy));
        data.put("protocolAdapterStatus",
                Map.of("signal", "READY", "vehicle", "READY", "driverDesk", "READY", "vision", "READY"));
        return ApiResponse.ok("dispatcher workstation", data);
    }

    private double number(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0;
    }
}
