package com.bjtu.railtransit.vehicle.protocol704;

import com.bjtu.railtransit.dispatch.SimulationService;
import com.bjtu.railtransit.domain.model.StatusReport;
import com.bjtu.railtransit.vehicle.dto.ControlCommand;
import com.bjtu.railtransit.vehicle.dto.SimulationControlRequest;
import com.bjtu.railtransit.vehicle.dto.SimulationResult;
import com.bjtu.railtransit.vehicle.dto.TrainState;
import com.bjtu.railtransit.vehicle.enums.DrivingMode;
import com.bjtu.railtransit.vehicle.enums.SimulationPhase;
import com.bjtu.railtransit.vehicle.model.LineProfile;
import com.bjtu.railtransit.vehicle.model.ScenarioConfig;
import com.bjtu.railtransit.vehicle.service.DemoScenarioProvider;
import com.bjtu.railtransit.vehicle.service.LineProfileJsonLoader;
import com.bjtu.railtransit.vehicle.service.LineProfileJsonLoader.StationEntry;
import com.bjtu.railtransit.vehicle.service.VehicleSimulationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The sole local-v1 business bridge: parsed PLC intent -> existing vehicle continuation service.
 * It contains no kinematics and never calls a web controller.
 */
@Service
public class Protocol704VehicleControlBridge {
    private static final long STALE_CONTEXT_MS = 5 * 60 * 1000L;
    /** PLC emits at about 100 ms; the vehicle integrator samples at 0.5 s. */
    private static final long MIN_CONTINUATION_INTERVAL_MS = 450L;
    private static final double STOPPED_SPEED_MPS = 0.05;
    private static final double STATION_STOP_MARGIN_M = 12.0;
    /** Conservative local protection when a physical desk keeps its traction handle applied. */
    private static final double STATION_BRAKE_MARGIN_M = 12.0;
    private final VehicleSimulationService vehicleSimulationService;
    private final DemoScenarioProvider demoScenarioProvider;
    private final LineProfileJsonLoader lineProfileJsonLoader;
    private final SimulationService dispatchSimulationService;
    private final Map<String, ActiveSimulationContext> contexts = new ConcurrentHashMap<>();

    public Protocol704VehicleControlBridge(VehicleSimulationService vehicleSimulationService,
                                           DemoScenarioProvider demoScenarioProvider,
                                           LineProfileJsonLoader lineProfileJsonLoader) {
        this(vehicleSimulationService, demoScenarioProvider, lineProfileJsonLoader, null);
    }

    @Autowired
    public Protocol704VehicleControlBridge(VehicleSimulationService vehicleSimulationService,
                                           DemoScenarioProvider demoScenarioProvider,
                                           LineProfileJsonLoader lineProfileJsonLoader,
                                           SimulationService dispatchSimulationService) {
        this.vehicleSimulationService = vehicleSimulationService;
        this.demoScenarioProvider = demoScenarioProvider;
        this.lineProfileJsonLoader = lineProfileJsonLoader;
        this.dispatchSimulationService = dispatchSimulationService;
    }

    public void registerSimulation(String trainId, int fromStationId, int toStationId,
                                   SimulationResult result, DrivingMode mode) {
        registerSimulation(trainId, fromStationId, toStationId, result, mode, true);
    }

    /** Production run registration starts READY_TO_DEPART; tests/legacy callers may opt in explicitly. */
    public void registerSimulation(String trainId, int fromStationId, int toStationId,
                                   SimulationResult result, DrivingMode mode, boolean departureAuthorized) {
        if (trainId == null || trainId.isBlank() || result == null || result.getStates() == null
                || result.getStates().isEmpty()) return;
        ActiveSimulationContext context = new ActiveSimulationContext(trainId, fromStationId, toStationId,
                copy(result.getStates().get(0)), mode == null ? DrivingMode.ATO : mode);
        context.departureAuthorized = departureAuthorized;
        context.departureState = departureAuthorized ? "RUNNING" : "READY_TO_DEPART";
        contexts.put(trainId, context);
        publishToDispatch(context);
    }

    public void recordWebControl(String trainId, SimulationControlRequest request, SimulationResult result) {
        if (trainId == null || trainId.isBlank() || request == null || request.getCurrentState() == null) return;
        ActiveSimulationContext context = contexts.get(trainId);
        // Normal web driving must not implicitly create a PLC-bound context.
        // A context is created only by a simulation run that opts in explicitly.
        if (context == null) return;
        synchronized (context) {
            context.currentState = copy(request.getCurrentState());
            context.mode = request.getCurrentMode();
            context.controlSource = "WEB_HMI";
            context.lastUpdatedAt = System.currentTimeMillis();
        }
        publishToDispatch(context);
    }

    public boolean isPlcControlOwner(String trainId) {
        ActiveSimulationContext context = contexts.get(trainId);
        return context != null && "PLC_704_LOCAL_V1".equals(context.controlSource);
    }

    /** True while this train was deliberately configured for laboratory desk control. */
    public boolean isLaboratoryControlEnabled(String trainId) {
        return trainId != null && !trainId.isBlank() && contexts.containsKey(trainId);
    }

    /** Remove laboratory control ownership without changing any web simulation instance. */
    public void unregisterSimulation(String trainId) {
        if (trainId == null || trainId.isBlank()) return;
        contexts.remove(trainId);
    }

    /** Thread-safe read model for laboratory output adapters; never exposes mutable context. */
    public ControlStateSnapshot snapshot(String trainId) {
        ActiveSimulationContext context = contexts.get(trainId);
        if (context == null) return null;
        synchronized (context) {
            TrainState state = copy(context.currentState);
            return new ControlStateSnapshot(context.trainId, context.fromStationId, context.toStationId,
                    state, context.mode.name(), context.controlSource, context.lastCommand,
                    Double.isFinite(context.lastLevel) ? context.lastLevel : 0,
                    context.emergencyLatched, context.departureAuthorized, context.lastUpdatedAt);
        }
    }

    public record ControlStateSnapshot(String trainId, int fromStationId, int toStationId,
                                       TrainState state, String mode, String controlSource,
                                       String lastCommand, double lastLevelPercent,
                                       boolean emergencyLatched, boolean departureAuthorized,
                                       long lastUpdatedAt) {}

    /** Read-only preflight state for binding a physical PLC desk to a simulated train. */
    public ControlReadiness readiness(String trainId) {
        ActiveSimulationContext context = contexts.get(trainId);
        if (context == null) {
            return new ControlReadiness(trainId, false, "NO_ACTIVE_SIMULATION", null,
                    null, null, 0);
        }
        synchronized (context) {
            if (System.currentTimeMillis() - context.lastUpdatedAt > STALE_CONTEXT_MS) {
                return new ControlReadiness(trainId, false, "STALE_ACTIVE_CONTEXT", context.mode.name(),
                        context.departureState, context.controlSource, context.lastUpdatedAt);
            }
            return new ControlReadiness(trainId, true, "READY", context.mode.name(),
                    context.departureState, context.controlSource, context.lastUpdatedAt);
        }
    }

    public record ControlReadiness(String trainId, boolean ready, String reason, String mode,
                                   String departureState, String controlSource, long lastUpdatedAt) {}

    public Protocol704CommandLifecycle execute(String trainId, MappedControlCommand mapped) {
        Protocol704CommandLifecycle lifecycle = lifecycle(trainId, mapped);
        lifecycle.setStatus("RECEIVED");
        if (mapped == null || Protocol704LocalV1ControlAdapter.toLocalV1(mapped).isEmpty()) {
            return reject(lifecycle, "UNSUPPORTED_COMMAND");
        }
        lifecycle.setStatus("PARSED");
        ActiveSimulationContext context = contexts.get(trainId);
        if (context == null) return reject(lifecycle, "NO_ACTIVE_SIMULATION");
        synchronized (context) {
            lifecycle.setActiveTrainId(context.trainId);
            lifecycle.setSessionId(context.sessionId);
            lifecycle.setPreviousMode(context.mode.name());
            lifecycle.setControlSource(context.controlSource);
            lifecycle.setDepartureState(context.departureState);
            if (System.currentTimeMillis() - context.lastUpdatedAt > STALE_CONTEXT_MS) {
                return reject(lifecycle, "STALE_ACTIVE_CONTEXT");
            }
            String command = mapped.getCommand();
            if ("SET_MANUAL".equals(command) || "RESUME_ATO".equals(command)
                    || "DEPART_CONFIRM".equals(command)) {
                if ("SET_MANUAL".equals(command)) {
                    context.mode = DrivingMode.MANUAL;
                    context.controlSource = "PLC_704_LOCAL_V1";
                } else if ("RESUME_ATO".equals(command)) {
                    context.mode = DrivingMode.ATO;
                    context.controlSource = "PLC_704_LOCAL_V1";
                } else {
                    context.departureAuthorized = true;
                    context.departureState = "RUNNING";
                }
                context.lastCommand = command;
                context.lastUpdatedAt = System.currentTimeMillis();
                lifecycle.setResultMode(context.mode.name());
                lifecycle.setExecutedState(copy(context.currentState));
                lifecycle.setDepartureState(context.departureState);
                lifecycle.setStatus("EXECUTED");
                publishToDispatch(context);
                return lifecycle;
            }
            if (("traction".equals(command) && (!finiteInRange(mapped.getLevelPercent()) || mapped.getTractionLevelRaw() > 100))
                    || ("brake".equals(command) && (!finiteInRange(mapped.getLevelPercent()) || mapped.getBrakeLevelRaw() > 100))) {
                return reject(lifecycle, "INVALID_LEVEL");
            }
            boolean emergency = "emergency_brake".equals(command);
            if (mapped.getDirection() != null && !"FORWARD".equals(mapped.getDirection())
                    && !"ZERO".equals(mapped.getDirection()) && !"REVERSE".equals(mapped.getDirection())) {
                return reject(lifecycle, "UNKNOWN_DIRECTION");
            }
            if (!emergency && context.emergencyLatched) return reject(lifecycle, "EMERGENCY_BRAKE_LATCHED");
            if (!emergency && !context.departureAuthorized) return reject(lifecycle, "NOT_READY_TO_DEPART");
            if (!emergency && context.mode != DrivingMode.MANUAL) return reject(lifecycle, "MODE_NOT_MANUAL");
            long now = System.currentTimeMillis();
            boolean steadyInput = command.equals(context.lastCommand)
                    && Double.compare(mapped.getLevelPercent(), context.lastLevel) == 0
                    && "PLC_704_LOCAL_V1".equals(context.controlSource);
            // Repeated PLC frames mean the driver is holding the handle. Keep the
            // command alive, but advance at the vehicle model's 0.5 s sample rate.
            if (steadyInput && now - context.lastPhysicalStepAt < MIN_CONTINUATION_INTERVAL_MS) {
                context.lastUpdatedAt = now;
                lifecycle.setExecutedState(copy(context.currentState));
                lifecycle.setResultMode(context.mode.name());
                lifecycle.setDepartureState(context.departureState);
                lifecycle.setStatus("EXECUTED");
                return lifecycle;
            }
            lifecycle.setStatus("VALIDATED");
            try {
                ControlCommand commandDto = Protocol704LocalV1ControlAdapter.toLocalV1(mapped).orElseThrow();
                boolean stationProtection = shouldApplyStationProtection(context, command);
                if (stationProtection) {
                    // The PLC still owns the manual mode. At this short approach
                    // distance, clamp traction and apply service brake locally.
                    commandDto = new ControlCommand("brake", 1.2, 100.0);
                    commandDto.setDirection(mapped.getDirection());
                }
                SimulationControlRequest request = new SimulationControlRequest();
                request.setFromStationId(context.currentStationId);
                request.setToStationId(context.currentTargetStationId);
                request.setCurrentState(copy(context.currentState));
                request.setCurrentMode(context.mode);
                request.setControlCommand(commandDto);
                request.setDepartureConfirmed(context.departureAuthorized);
                double targetPosition = stationDistanceFromOrigin(context, context.currentTargetStationId);
                LineProfile line = lineProfileJsonLoader.buildLineProfile(
                        context.currentStationId, context.currentTargetStationId);
                request.setTotalTargetPosition(targetPosition);
                request.setNextStationId(context.currentTargetStationId);
                request.setNextStationName(stationName(context.currentTargetStationId));
                ScenarioConfig scenario = demoScenarioProvider.buildScenario(line);
                SimulationResult result = vehicleSimulationService.runContinuation(request, scenario, targetPosition);
                TrainState executed = selectObservableState(result.getStates(),
                        stationProtection ? "brake" : command, context.currentState);
                context.currentState = copy(executed);
                context.mode = emergency ? DrivingMode.EMERGENCY
                        : result.getSummary().getCurrentMode() == null ? context.mode : result.getSummary().getCurrentMode();
                context.controlSource = emergency ? "EMERGENCY" : "PLC_704_LOCAL_V1";
                context.emergencyLatched = context.emergencyLatched || emergency;
                context.lastCommand = command;
                context.lastLevel = mapped.getLevelPercent();
                context.lastUpdatedAt = now;
                context.lastPhysicalStepAt = now;
                updateStationStop(context);
                lifecycle.setControlSource(context.controlSource);
                lifecycle.setExecutedState(copy(context.currentState));
                lifecycle.setResultMode(context.mode.name());
                lifecycle.setDepartureState(context.departureState);
                lifecycle.setStatus("EXECUTED");
                publishToDispatch(context);
                return lifecycle;
            } catch (Exception ex) {
                lifecycle.setExecutionError(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                lifecycle.setStatus("FAILED");
                return lifecycle;
            }
        }
    }

    private static Protocol704CommandLifecycle lifecycle(String trainId, MappedControlCommand mapped) {
        Protocol704CommandLifecycle lifecycle = new Protocol704CommandLifecycle();
        lifecycle.setCommandId("704-local-v1-" + UUID.randomUUID());
        lifecycle.setReceivedAt(System.currentTimeMillis());
        lifecycle.setParsedCommand(mapped == null ? null : mapped.getCommand());
        lifecycle.setLevel(mapped == null ? 0.0 : mapped.getLevelPercent());
        lifecycle.setActiveTrainId(trainId);
        return lifecycle;
    }

    private static Protocol704CommandLifecycle reject(Protocol704CommandLifecycle lifecycle, String reason) {
        lifecycle.setStatus("REJECTED");
        lifecycle.setRejectionReason(reason);
        return lifecycle;
    }

    private static boolean finiteInRange(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 100.0;
    }

    /**
     * The driver remains responsible for normal manual braking. This guard only
     * prevents a continuously-held traction input from carrying the software train
     * past its configured next stop. It is local simulation supervision, not a
     * command to any laboratory wayside device.
     */
    private boolean shouldApplyStationProtection(ActiveSimulationContext context, String command) {
        if (!"traction".equals(command) || context.currentTargetStationId < context.fromStationId) return false;
        double remaining = stationDistanceFromOrigin(context, context.currentTargetStationId)
                - context.currentState.getPosition();
        if (remaining <= STATION_STOP_MARGIN_M) return true;
        double speed = Math.max(0.0, context.currentState.getVelocity());
        double requiredDistance = speed * speed / (2.0 * 1.0)
                + STATION_BRAKE_MARGIN_M;
        return remaining <= requiredDistance;
    }

    private void updateStationStop(ActiveSimulationContext context) {
        TrainState state = context.currentState;
        double targetPosition = stationDistanceFromOrigin(context, context.currentTargetStationId);
        // A driver may brake to zero anywhere in the section. Only a stop within
        // the configured approach tolerance is an arrival at the next station.
        if (state.getPosition() < targetPosition - STATION_STOP_MARGIN_M) return;
        if (state.getVelocity() > STOPPED_SPEED_MPS) return;

        // Continuation physics can stop a few metres short on a conservative
        // approach. Treat the configured platform tolerance as the authority,
        // snap the software train to the target and require a new departure.
        state.setPosition(targetPosition);
        state.setVelocity(0.0);
        state.setAcceleration(0.0);
        state.setPhase(SimulationPhase.STOPPED);

        context.currentStationId = context.currentTargetStationId;
        if (context.currentTargetStationId < context.toStationId) {
            context.currentTargetStationId++;
            context.departureAuthorized = false;
            context.departureState = "READY_TO_DEPART";
            context.lastCommand = "STATION_DWELL";
        } else {
            context.departureAuthorized = false;
            context.departureState = "TERMINAL_DWELL";
            context.lastCommand = "TERMINAL_DWELL";
        }
    }

    private void publishToDispatch(ActiveSimulationContext context) {
        if (dispatchSimulationService == null) return;
        TrainState state = copy(context.currentState);
        StatusReport report = new StatusReport();
        report.setTrainId(context.trainId);
        report.setDeviceId("PROTOCOL704-" + context.trainId);
        report.setSourceType("PLC_704_LOCAL_V1");
        report.setAuthoritative(true);
        report.setLineId("BJ-L9");
        report.setTimestampSeconds(state.getTime());
        report.setPositionMeters(absolutePosition(context, state));
        report.setSpeedKmh(Math.max(0.0, state.getVelocity() * 3.6));
        report.setAccelerationMps2(state.getAcceleration());
        report.setDirection("UP");
        report.setCurrentStationId(String.valueOf(currentStationFor(context, state)));
        report.setNextStationId(String.valueOf(nextStationFor(context)));
        report.setFromStationId(String.valueOf(context.fromStationId));
        report.setToStationId(String.valueOf(context.toStationId));
        report.setOperatingMode(context.mode.name());
        report.setPhase(dispatchPhase(context, state));
        dispatchSimulationService.acceptOnboardReport(report);
    }

    private String dispatchPhase(ActiveSimulationContext context, TrainState state) {
        if (!context.departureAuthorized && state.getVelocity() <= STOPPED_SPEED_MPS) {
            return "READY_TO_DEPART";
        }
        return switch (state.getPhase()) {
            case TRACTION -> "ACCELERATING";
            case BRAKING -> "BRAKING";
            case COAST -> "CRUISING";
            case DWELL, STOPPED -> "DWELLING";
        };
    }

    private double absolutePosition(ActiveSimulationContext context, TrainState state) {
        if (state.getAbsolutePosition() != null) return state.getAbsolutePosition();
        return stationAbsolutePosition(context.fromStationId) + state.getPosition();
    }

    private int currentStationFor(ActiveSimulationContext context, TrainState state) {
        if (state.getVelocity() <= STOPPED_SPEED_MPS) return context.currentStationId;
        double position = absolutePosition(context, state);
        int current = context.fromStationId;
        for (StationEntry station : lineProfileJsonLoader.listStations()) {
            if (station.id > context.toStationId || station.km * 1000.0 > position + STATION_STOP_MARGIN_M) break;
            current = station.id;
        }
        return current;
    }

    private int nextStationFor(ActiveSimulationContext context) {
        return Math.min(context.currentTargetStationId, context.toStationId);
    }

    private double stationDistanceFromOrigin(ActiveSimulationContext context, int stationId) {
        return stationAbsolutePosition(stationId) - stationAbsolutePosition(context.fromStationId);
    }

    private double stationAbsolutePosition(int stationId) {
        return lineProfileJsonLoader.listStations().stream()
                .filter(station -> station.id == stationId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown station id " + stationId))
                .km * 1000.0;
    }

    private String stationName(int stationId) {
        return lineProfileJsonLoader.listStations().stream()
                .filter(station -> station.id == stationId)
                .findFirst()
                .map(station -> station.name)
                .orElse(String.valueOf(stationId));
    }

    private static TrainState selectObservableState(List<TrainState> states, String command, TrainState fallback) {
        if (states == null || states.isEmpty()) return fallback;
        for (TrainState state : states) {
            if ("traction".equals(command) && state.getAcceleration() > 0.0 && state.getVelocity() > fallback.getVelocity()) return state;
            if ("brake".equals(command) && state.getAcceleration() < 0.0) {
                if (state.getVelocity() < fallback.getVelocity() - 0.01) return state;
            }
            if ("emergency_brake".equals(command)
                    && (state.getAcceleration() < 0.0 || state.getVelocity() < fallback.getVelocity())) {
                if (state.getVelocity() < fallback.getVelocity() - 0.01
                        || state.getVelocity() <= STOPPED_SPEED_MPS) {
                    return state;
                }
            }
            if ("coast".equals(command) && "COAST".equals(state.getPhase().name())) return state;
        }
        return states.get(Math.min(1, states.size() - 1));
    }

    private static TrainState copy(TrainState source) {
        TrainState copy = new TrainState(source.getTime(), source.getPosition(), source.getVelocity(),
                source.getAcceleration(), source.getPhase(), source.getTrainId());
        copy.setAbsolutePosition(source.getAbsolutePosition());
        copy.setCars(source.getCars());
        return copy;
    }

    private static final class ActiveSimulationContext {
        final String trainId;
        final int fromStationId;
        final int toStationId;
        final String sessionId = "protocol704-local-v1";
        TrainState currentState;
        DrivingMode mode;
        String controlSource = "ATO";
        String lastCommand = "";
        double lastLevel = Double.NaN;
        boolean emergencyLatched;
        boolean departureAuthorized;
        String departureState = "READY_TO_DEPART";
        long lastUpdatedAt = System.currentTimeMillis();
        long lastPhysicalStepAt;
        int currentStationId;
        int currentTargetStationId;

        ActiveSimulationContext(String trainId, int fromStationId, int toStationId, TrainState currentState, DrivingMode mode) {
            this.trainId = trainId;
            this.fromStationId = fromStationId;
            this.toStationId = toStationId;
            this.currentState = currentState;
            this.mode = mode == null ? DrivingMode.ATO : mode;
            this.currentStationId = fromStationId;
            this.currentTargetStationId = Math.min(toStationId, fromStationId + 1);
        }
    }
}
