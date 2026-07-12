package com.bjtu.railtransit.vehicle.protocol704;

import com.bjtu.railtransit.vehicle.dto.ControlCommand;
import com.bjtu.railtransit.vehicle.dto.SimulationControlRequest;
import com.bjtu.railtransit.vehicle.dto.SimulationResult;
import com.bjtu.railtransit.vehicle.dto.TrainState;
import com.bjtu.railtransit.vehicle.enums.DrivingMode;
import com.bjtu.railtransit.vehicle.model.LineProfile;
import com.bjtu.railtransit.vehicle.model.ScenarioConfig;
import com.bjtu.railtransit.vehicle.service.DemoScenarioProvider;
import com.bjtu.railtransit.vehicle.service.LineProfileJsonLoader;
import com.bjtu.railtransit.vehicle.service.VehicleSimulationService;
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
    private final VehicleSimulationService vehicleSimulationService;
    private final DemoScenarioProvider demoScenarioProvider;
    private final LineProfileJsonLoader lineProfileJsonLoader;
    private final Map<String, ActiveSimulationContext> contexts = new ConcurrentHashMap<>();

    public Protocol704VehicleControlBridge(VehicleSimulationService vehicleSimulationService,
                                           DemoScenarioProvider demoScenarioProvider,
                                           LineProfileJsonLoader lineProfileJsonLoader) {
        this.vehicleSimulationService = vehicleSimulationService;
        this.demoScenarioProvider = demoScenarioProvider;
        this.lineProfileJsonLoader = lineProfileJsonLoader;
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
    }

    public void recordWebControl(String trainId, SimulationControlRequest request, SimulationResult result) {
        if (trainId == null || trainId.isBlank() || request == null || request.getCurrentState() == null) return;
        ActiveSimulationContext context = contexts.get(trainId);
        if (context == null) {
            contexts.put(trainId, new ActiveSimulationContext(trainId, request.getFromStationId(), request.getToStationId(),
                    copy(request.getCurrentState()), request.getCurrentMode()));
            return;
        }
        synchronized (context) {
            context.currentState = copy(request.getCurrentState());
            context.mode = request.getCurrentMode();
            context.controlSource = "WEB_HMI";
            context.lastUpdatedAt = System.currentTimeMillis();
        }
    }

    public boolean isPlcControlOwner(String trainId) {
        ActiveSimulationContext context = contexts.get(trainId);
        return context != null && "PLC_704_LOCAL_V1".equals(context.controlSource);
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
            if (!emergency && command.equals(context.lastCommand)
                    && Double.compare(mapped.getLevelPercent(), context.lastLevel) == 0
                    && "PLC_704_LOCAL_V1".equals(context.controlSource)) {
                return reject(lifecycle, "DUPLICATE_STEADY_STATE");
            }
            lifecycle.setStatus("VALIDATED");
            if (emergency && context.emergencyLatched) {
                lifecycle.setResultMode(DrivingMode.EMERGENCY.name());
                lifecycle.setExecutedState(copy(context.currentState));
                lifecycle.setStatus("EXECUTED");
                return lifecycle;
            }
            try {
                ControlCommand commandDto = Protocol704LocalV1ControlAdapter.toLocalV1(mapped).orElseThrow();
                SimulationControlRequest request = new SimulationControlRequest();
                request.setFromStationId(context.fromStationId);
                request.setToStationId(context.toStationId);
                request.setCurrentState(copy(context.currentState));
                request.setCurrentMode(context.mode);
                request.setControlCommand(commandDto);
                request.setDepartureConfirmed(context.departureAuthorized);
                LineProfile line = lineProfileJsonLoader.buildLineProfile(context.fromStationId, context.toStationId);
                request.setTotalTargetPosition(line.getTargetStopPosition());
                ScenarioConfig scenario = demoScenarioProvider.buildScenario(line);
                SimulationResult result = vehicleSimulationService.runContinuation(request, scenario, line.getTargetStopPosition());
                TrainState executed = selectObservableState(result.getStates(), command, context.currentState);
                context.currentState = copy(executed);
                context.mode = emergency ? DrivingMode.EMERGENCY
                        : result.getSummary().getCurrentMode() == null ? context.mode : result.getSummary().getCurrentMode();
                context.controlSource = emergency ? "EMERGENCY" : "PLC_704_LOCAL_V1";
                context.emergencyLatched = context.emergencyLatched || emergency;
                context.lastCommand = command;
                context.lastLevel = mapped.getLevelPercent();
                context.lastUpdatedAt = System.currentTimeMillis();
                lifecycle.setControlSource(context.controlSource);
                lifecycle.setExecutedState(copy(executed));
                lifecycle.setResultMode(context.mode.name());
                lifecycle.setStatus("EXECUTED");
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

    private static TrainState selectObservableState(List<TrainState> states, String command, TrainState fallback) {
        if (states == null || states.isEmpty()) return fallback;
        for (TrainState state : states) {
            if ("traction".equals(command) && state.getAcceleration() > 0.0 && state.getVelocity() > fallback.getVelocity()) return state;
            if ("brake".equals(command) && state.getAcceleration() < 0.0) return state;
            if ("emergency_brake".equals(command) && (state.getAcceleration() < 0.0 || state.getVelocity() < fallback.getVelocity())) return state;
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

        ActiveSimulationContext(String trainId, int fromStationId, int toStationId, TrainState currentState, DrivingMode mode) {
            this.trainId = trainId;
            this.fromStationId = fromStationId;
            this.toStationId = toStationId;
            this.currentState = currentState;
            this.mode = mode == null ? DrivingMode.ATO : mode;
        }
    }
}
