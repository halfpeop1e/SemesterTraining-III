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

@Service
public class Protocol704VehicleControlBridge {
    public static final String SOURCE_WEB_HMI = "WEB_HMI";
    public static final String SOURCE_PLC = "PLC_704_LOCAL_V1";
    public static final String SOURCE_ATO = "ATO";
    public static final String SOURCE_EMERGENCY = "EMERGENCY";
    public static final String SOURCE_LOCAL_TEST = "LOCAL_TEST";

    private static final long STALE_CONTEXT_MS = 5 * 60 * 1000L;
    private final VehicleSimulationService vehicleSimulationService;
    private final DemoScenarioProvider demoScenarioProvider;
    private final LineProfileJsonLoader lineProfileJsonLoader;
    private final Map<String, ActiveSimulationContext> contexts = new ConcurrentHashMap<>();
    private final Map<String, Boolean> turnbackInProgress = new ConcurrentHashMap<>();

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
            if (context.emergencyLatched) {
                return;
            }
            if (context.plcConnectionId != null && !SOURCE_WEB_HMI.equals(context.controlSource)) {
                return;
            }
            context.currentState = copy(request.getCurrentState());
            context.mode = request.getCurrentMode();
            context.controlSource = SOURCE_WEB_HMI;
            context.lastUpdatedAt = System.currentTimeMillis();
        }
    }

    public boolean isPlcControlOwner(String trainId) {
        ActiveSimulationContext context = contexts.get(trainId);
        return context != null && SOURCE_PLC.equals(context.controlSource);
    }

    public boolean isEmergencyLatched(String trainId) {
        ActiveSimulationContext context = contexts.get(trainId);
        return context != null && context.emergencyLatched;
    }

    public void triggerTurnback(String trainId) {
        if (trainId == null) return;
        turnbackInProgress.put(trainId, Boolean.TRUE);
    }

    public void completeTurnback(String trainId) {
        if (trainId == null) return;
        turnbackInProgress.remove(trainId);
    }

    public boolean isTurnbackInProgress(String trainId) {
        return Boolean.TRUE.equals(turnbackInProgress.get(trainId));
    }

    public void resetEmergencyLatch(String trainId) {
        ActiveSimulationContext context = contexts.get(trainId);
        if (context == null) return;
        synchronized (context) {
            context.emergencyLatched = false;
            if (context.mode == DrivingMode.EMERGENCY) {
                context.mode = DrivingMode.MANUAL;
            }
            context.lastUpdatedAt = System.currentTimeMillis();
        }
    }

    /**
     * External mode sync from WEB_HMI / control API into the Bridge context.
     * Keeps Protocol704 test frames and driver-cab control on the same mode.
     */
    public void syncMode(String trainId, DrivingMode mode, String source) {
        if (trainId == null || trainId.isBlank() || mode == null) return;
        ActiveSimulationContext context = contexts.get(trainId);
        if (context == null) return;
        synchronized (context) {
            context.mode = mode;
            if (source != null && !source.isBlank()) {
                context.controlSource = source;
            }
            if (mode == DrivingMode.EMERGENCY) {
                context.emergencyLatched = true;
                context.controlSource = SOURCE_EMERGENCY;
            } else if (mode == DrivingMode.ATO) {
                context.controlSource = SOURCE_ATO;
                context.plcConnectionId = null;
                context.plcPort = -1;
            } else if (mode == DrivingMode.MANUAL && SOURCE_EMERGENCY.equals(context.controlSource)) {
                context.controlSource = source != null ? source : SOURCE_WEB_HMI;
            }
            context.lastUpdatedAt = System.currentTimeMillis();
        }
    }

    /** External departure-authorization sync (dispatch DEPART → vehicle confirm). */
    public void syncDepartureAuth(String trainId, boolean authorized) {
        if (trainId == null || trainId.isBlank()) return;
        ActiveSimulationContext context = contexts.get(trainId);
        if (context == null) return;
        synchronized (context) {
            context.departureAuthorized = authorized;
            context.departureState = authorized ? "RUNNING" : "READY_TO_DEPART";
            context.lastUpdatedAt = System.currentTimeMillis();
        }
    }

    /**
     * Sync the current simulation state from the frontend ATO playback into the Bridge context.
     * <p>Only updates state/mode/station context — does NOT trigger simulation, does NOT clear
     * emergencyLatched, does NOT call reset. Used before sending 704 test frames so EB starts
     * from the actual current position rather than the initial registered state.</p>
     *
     * <p>坐标语义：currentState.position 是累计相对里程（从线路起点算），
     * currentState.absolutePosition 是绝对里程。两者语义不同，不可互换。
     * syncCurrentState 原样保存，runContinuation 会以 position 作为 currentCumulativePos。</p>
     */
    public void syncCurrentState(String trainId, TrainState currentState, DrivingMode mode,
                                 Integer fromStationId, Integer toStationId, Boolean departureAuthorized) {
        if (trainId == null || trainId.isBlank() || currentState == null) return;
        ActiveSimulationContext context = contexts.get(trainId);
        if (context == null) {
            int fromId = fromStationId != null ? fromStationId : 1;
            int toId = toStationId != null ? toStationId : 2;
            context = new ActiveSimulationContext(trainId, fromId, toId,
                    copy(currentState), mode == null ? DrivingMode.ATO : mode);
            if (departureAuthorized != null) {
                context.departureAuthorized = departureAuthorized;
                context.departureState = departureAuthorized ? "RUNNING" : "READY_TO_DEPART";
            }
            contexts.put(trainId, context);
            return;
        }
        synchronized (context) {
            // 不清除 emergencyLatched —— 只有 resetEmergencyLatch 可以清除
            context.currentState = copy(currentState);
            if (mode != null) {
                context.mode = mode;
            }
            if (departureAuthorized != null) {
                context.departureAuthorized = departureAuthorized;
                context.departureState = departureAuthorized ? "RUNNING" : "READY_TO_DEPART";
            }
            context.lastUpdatedAt = System.currentTimeMillis();
        }
    }

    public DrivingMode getMode(String trainId) {
        ActiveSimulationContext context = contexts.get(trainId);
        return context == null ? null : context.mode;
    }

    public boolean isDepartureAuthorized(String trainId) {
        ActiveSimulationContext context = contexts.get(trainId);
        return context != null && context.departureAuthorized;
    }

    public Protocol704CommandLifecycle execute(String trainId, MappedControlCommand mapped) {
        return execute(trainId, mapped, SOURCE_ATO, "legacy-2arg", -1);
    }

    public Protocol704CommandLifecycle executeFromTestFrame(String trainId, MappedControlCommand mapped) {
        Protocol704CommandLifecycle lifecycle = lifecycle(trainId, mapped, SOURCE_LOCAL_TEST, "local-test", -1);
        lifecycle.setSource(SOURCE_LOCAL_TEST);
        return execute(trainId, mapped, SOURCE_LOCAL_TEST, "local-test", -1);
    }

    public Protocol704CommandLifecycle execute(String trainId, MappedControlCommand mapped,
                                               String source, String connectionId, int port) {
        Protocol704CommandLifecycle lifecycle = lifecycle(trainId, mapped, source, connectionId, port);
        lifecycle.setStatus("RECEIVED");
        if (mapped == null || Protocol704LocalV1ControlAdapter.toLocalV1(mapped).isEmpty()) {
            return reject(lifecycle, "UNSUPPORTED_COMMAND");
        }
        lifecycle.setStatus("PARSED");
        if (source != null) {
            lifecycle.setSource(source);
        }
        ActiveSimulationContext context = contexts.get(trainId);
        if (context == null) return reject(lifecycle, "NO_ACTIVE_SIMULATION");
        synchronized (context) {
            lifecycle.setActiveTrainId(context.trainId);
            lifecycle.setSessionId(context.sessionId);
            lifecycle.setPreviousMode(context.mode.name());
            lifecycle.setPreviousControlSource(context.controlSource);
            lifecycle.setEmergencyLatchedBefore(context.emergencyLatched);
            lifecycle.setControlSource(context.controlSource);
            lifecycle.setResultMode(context.mode.name());
            lifecycle.setDepartureState(context.departureState);
            if (System.currentTimeMillis() - context.lastUpdatedAt > STALE_CONTEXT_MS) {
                return reject(lifecycle, "STALE_ACTIVE_CONTEXT");
            }
            String command = mapped.getCommand();
            boolean emergency = "emergency_brake".equals(command);
            boolean modeCommand = "SET_MANUAL".equals(command) || "RESUME_ATO".equals(command)
                    || "DEPART_CONFIRM".equals(command);
            boolean isOrdinaryMotionCommand = "traction".equals(command) || "coast".equals(command) || "brake".equals(command);

            if (context.emergencyLatched && !emergency) {
                lifecycle.setResultMode(DrivingMode.EMERGENCY.name());
                lifecycle.setControlSource(SOURCE_EMERGENCY);
                return reject(lifecycle, "EMERGENCY_BRAKE_LATCHED");
            }

            if (!emergency && !modeCommand && isOrdinaryMotionCommand) {
                if (context.plcConnectionId != null && !context.plcConnectionId.equals(connectionId)) {
                    return reject(lifecycle, "CONTROL_SOURCE_CONFLICT");
                }
            }

            if (modeCommand) {
                lifecycle.setStatus("VALIDATED");
                if ("SET_MANUAL".equals(command)) {
                    context.mode = DrivingMode.MANUAL;
                    context.controlSource = source;
                    if (connectionId != null) {
                        context.plcConnectionId = connectionId;
                        context.plcPort = port;
                    }
                } else if ("RESUME_ATO".equals(command)) {
                    context.mode = DrivingMode.ATO;
                    context.controlSource = SOURCE_ATO;
                    context.plcConnectionId = null;
                    context.plcPort = -1;
                } else {
                    context.departureAuthorized = true;
                    context.departureState = "RUNNING";
                }
                context.lastCommand = command;
                context.lastUpdatedAt = System.currentTimeMillis();
                lifecycle.setResultMode(context.mode.name());
                lifecycle.setControlSource(context.controlSource);
                lifecycle.setExecutedState(copy(context.currentState));
                lifecycle.setDepartureState(context.departureState);
                lifecycle.setEmergencyLatchedAfter(context.emergencyLatched);
                lifecycle.setStatus("EXECUTED");
                return lifecycle;
            }

            if (("traction".equals(command) && (!finiteInRange(mapped.getLevelPercent()) || mapped.getTractionLevelRaw() > 100))
                    || ("brake".equals(command) && (!finiteInRange(mapped.getLevelPercent()) || mapped.getBrakeLevelRaw() > 100))) {
                return reject(lifecycle, "INVALID_LEVEL");
            }

            if (mapped.getDirection() != null && !"FORWARD".equals(mapped.getDirection())
                    && !"ZERO".equals(mapped.getDirection()) && !"REVERSE".equals(mapped.getDirection())) {
                return reject(lifecycle, "UNKNOWN_DIRECTION");
            }

            if (!emergency && !context.departureAuthorized) return reject(lifecycle, "NOT_READY_TO_DEPART");
            if (!emergency && context.mode != DrivingMode.MANUAL) return reject(lifecycle, "MODE_NOT_MANUAL");

            if (!emergency && Boolean.TRUE.equals(turnbackInProgress.get(trainId)) && isOrdinaryMotionCommand) {
                return reject(lifecycle, "TURNBACK_IN_PROGRESS");
            }

            if (!emergency && command.equals(context.lastCommand)
                    && Double.compare(mapped.getLevelPercent(), context.lastLevel) == 0
                    && context.controlSource.equals(source)) {
                return reject(lifecycle, "DUPLICATE_STEADY_STATE");
            }

            lifecycle.setStatus("VALIDATED");

            if (emergency && context.emergencyLatched) {
                lifecycle.setResultMode(DrivingMode.EMERGENCY.name());
                lifecycle.setControlSource(SOURCE_EMERGENCY);
                lifecycle.setExecutedState(copy(context.currentState));
                lifecycle.setEmergencyLatchedAfter(true);
                lifecycle.setStatus("EXECUTED");
                context.lastUpdatedAt = System.currentTimeMillis();
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
                if (emergency) {
                    context.controlSource = SOURCE_EMERGENCY;
                    if (connectionId != null) {
                        context.plcConnectionId = connectionId;
                        context.plcPort = port;
                    }
                } else {
                    context.controlSource = source;
                    if (connectionId != null) {
                        context.plcConnectionId = connectionId;
                        context.plcPort = port;
                    }
                }
                context.emergencyLatched = context.emergencyLatched || emergency;
                context.lastCommand = command;
                context.lastLevel = mapped.getLevelPercent();
                context.lastUpdatedAt = System.currentTimeMillis();
                lifecycle.setControlSource(context.controlSource);
                lifecycle.setExecutedState(copy(executed));
                lifecycle.setResultMode(context.mode.name());
                lifecycle.setEmergencyLatchedAfter(context.emergencyLatched);
                // 存储完整 SimulationResult，前端用它把 EB 制动轨迹拼接到主 result.states 后面播放
                lifecycle.setExecutedResult(result);
                lifecycle.setStatus("EXECUTED");
                return lifecycle;
            } catch (Exception ex) {
                lifecycle.setExecutionError(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                lifecycle.setEmergencyLatchedAfter(context.emergencyLatched);
                lifecycle.setStatus("FAILED");
                return lifecycle;
            }
        }
    }

    public void notifyPlcDisconnected(String trainId, int port, String connectionId) {
        ActiveSimulationContext context = contexts.get(trainId);
        if (context == null) return;
        synchronized (context) {
            if (context.plcConnectionId != null && context.plcConnectionId.equals(connectionId)
                    && context.plcPort == port) {
                context.plcConnectionId = null;
                context.plcPort = -1;
            }
            context.lastUpdatedAt = System.currentTimeMillis();
        }
    }

    private static Protocol704CommandLifecycle lifecycle(String trainId, MappedControlCommand mapped,
                                                          String source, String connectionId, int port) {
        Protocol704CommandLifecycle lifecycle = new Protocol704CommandLifecycle();
        lifecycle.setCommandId("704-local-v1-" + UUID.randomUUID());
        lifecycle.setReceivedAt(System.currentTimeMillis());
        lifecycle.setParsedCommand(mapped == null ? null : mapped.getCommand());
        lifecycle.setLevel(mapped == null ? 0.0 : mapped.getLevelPercent());
        lifecycle.setActiveTrainId(trainId);
        lifecycle.setSource(source);
        lifecycle.setConnectionId(connectionId);
        lifecycle.setPort(port);
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
        String controlSource = SOURCE_ATO;
        String lastCommand = "";
        double lastLevel = Double.NaN;
        boolean emergencyLatched;
        boolean departureAuthorized;
        String departureState = "READY_TO_DEPART";
        String plcConnectionId;
        int plcPort = -1;
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
