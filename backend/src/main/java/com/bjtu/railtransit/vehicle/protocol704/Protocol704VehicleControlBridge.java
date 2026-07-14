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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;
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
    /** PLC emits at about 100 ms; the vehicle integrator samples at 0.5 s. */
    private static final long MIN_CONTINUATION_INTERVAL_MS = 450L;
    private static final double STOPPED_SPEED_MPS = 0.05;
    private static final double STATION_STOP_MARGIN_M = 12.0;
    /** Conservative local protection when a physical desk keeps its traction handle applied. */
    private static final double STATION_BRAKE_MARGIN_M = 12.0;
    /** Five seconds at the scheduler's 500 ms period. */
    private static final int AUTOMATIC_DOOR_DWELL_TICKS = 10;
    private final VehicleSimulationService vehicleSimulationService;
    private final DemoScenarioProvider demoScenarioProvider;
    private final LineProfileJsonLoader lineProfileJsonLoader;
    private final SimulationService dispatchSimulationService;
    private final Map<String, ActiveSimulationContext> contexts = new ConcurrentHashMap<>();
    private final Map<String, Boolean> turnbackInProgress = new ConcurrentHashMap<>();
    /**
     * Vehicle callbacks frequently run while holding an individual context
     * monitor. Signal-cycle code takes the dispatch monitor first and then reads
     * that same context. Calling dispatch synchronously here would therefore
     * create an AB/BA lock inversion and freeze ATO, ATS and every HIL snapshot.
     * Keep only the newest authoritative report per train and flush it after the
     * context monitor has been released.
     */
    private final Map<String, StatusReport> pendingDispatchReports = new ConcurrentHashMap<>();

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
        registerSimulation(trainId, fromStationId, toStationId, result, mode, true, false);
    }

    public void registerSimulation(String trainId, int fromStationId, int toStationId,
                                   SimulationResult result, DrivingMode mode, boolean departureAuthorized) {
        registerSimulation(trainId, fromStationId, toStationId, result, mode, departureAuthorized, false);
    }

    /**
     * Registers a prepared train for a physical desk. Direct-handle control is
     * deliberately opt-in: after an intermediate station stop, the driver must
     * return the master handle to coast once before the next traction command is
     * accepted. This prevents a continuously held traction input from departing
     * a platform automatically.
     */
    public void registerSimulation(String trainId, int fromStationId, int toStationId,
                                   SimulationResult result, DrivingMode mode, boolean departureAuthorized,
                                   boolean directHandleControl) {
        registerSimulation(trainId, fromStationId, toStationId, result, mode, departureAuthorized,
                directHandleControl, false);
    }

    /**
     * Automatic departure applies only after an intermediate station door cycle.
     * It never bypasses signal/MA authorization and never starts from the origin
     * or terminal station.
     */
    public void registerSimulation(String trainId, int fromStationId, int toStationId,
                                   SimulationResult result, DrivingMode mode, boolean departureAuthorized,
                                   boolean directHandleControl, boolean laboratoryAutoDeparture) {
        if (trainId == null || trainId.isBlank() || result == null || result.getStates() == null
                || result.getStates().isEmpty()) return;
        ActiveSimulationContext context = new ActiveSimulationContext(trainId, fromStationId, toStationId,
                copy(result.getStates().get(0)), mode == null ? DrivingMode.ATO : mode);
        context.departureAuthorized = departureAuthorized;
        context.departureState = departureAuthorized ? "RUNNING" : "READY_TO_DEPART";
        context.directHandleControl = directHandleControl;
        context.laboratoryAutoDeparture = laboratoryAutoDeparture;
        contexts.put(trainId, context);
        if (dispatchSimulationService != null) {
            dispatchSimulationService.claimDriverDeskControl(trainId);
        }
        publishToDispatch(context);
    }

    public void recordWebControl(String trainId, SimulationControlRequest request, SimulationResult result) {
        if (trainId == null || trainId.isBlank() || request == null || request.getCurrentState() == null) return;
        ActiveSimulationContext context = contexts.get(trainId);
        // Normal web driving must not implicitly create a PLC-bound context.
        // A context is created only by a simulation run that opts in explicitly.
        if (context == null) return;
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
        publishToDispatch(context);
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
            context.emergencyBrakeRunning = false;
            context.emergencyTrajectory = List.of();
            context.emergencyFrameIndex = 0;
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

    /** Signal-cycle-only departure authorization sync. */
    public void syncDepartureAuth(String trainId, boolean authorized) {
        if (trainId == null || trainId.isBlank()) return;
        ActiveSimulationContext context = contexts.get(trainId);
        if (context == null) return;
        synchronized (context) {
            context.departureAuthorized = authorized;
            context.departureState = authorized && context.doorsClosed && !context.doorCycleRequired
                    ? "ATO_READY" : context.doorsClosed ? "READY_TO_DEPART" : context.doorState;
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
     * syncCurrentState 原样保存，runContinuation 会以 position 作为 currentCumulativePos。
     * The deprecated departureAuthorized argument is intentionally ignored:
     * a state sync must never authorize physical movement.</p>
     */
    public void syncCurrentState(String trainId, TrainState currentState, DrivingMode mode,
                                 Integer fromStationId, Integer toStationId, Boolean departureAuthorized) {
        if (trainId == null || trainId.isBlank() || currentState == null) return;
        ActiveSimulationContext context = contexts.get(trainId);
        if (context == null) return;
        synchronized (context) {
            // 不清除 emergencyLatched —— 只有 resetEmergencyLatch 可以清除
            context.currentState = copy(currentState);
            if (mode != null) {
                context.mode = mode;
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

    /** Read-only diagnosis for the driver-desk ATO workflow. */
    public String atoWorkflowState(String trainId) {
        ActiveSimulationContext context = contexts.get(trainId);
        if (context == null) return "NO_ACTIVE_SIMULATION";
        synchronized (context) {
            if (context.emergencyLatched) return "ATP_EMERGENCY_BRAKE";
            if (context.atoRunning) return "ATO_RUNNING";
            if (!context.keySwitchOn) return "KEY_SWITCH_OFF";
            if (!context.doorsClosed) return "DOORS_OPEN";
            if (context.doorCycleRequired) return "DOOR_CYCLE_REQUIRED";
            if (!context.departureAuthorized) return "WAITING_SIGNAL_AUTHORIZATION";
            return "ATO_READY";
        }
    }

    /** True while this train was deliberately configured for laboratory desk control. */
    public boolean isLaboratoryControlEnabled(String trainId) {
        return trainId != null && !trainId.isBlank() && contexts.containsKey(trainId);
    }

    /** Highest-priority ATP intervention, independent of the current control owner. */
    public void applyAtpEmergencyBrake(String trainId) {
        ActiveSimulationContext context = contexts.get(trainId);
        if (context == null) return;
        synchronized (context) {
            // Signal supervision may report the same unsafe MA on consecutive
            // cycles. One emergency trajectory is enough; regenerating it from
            // an already-braking state would make the stop non-deterministic.
            if (context.emergencyLatched) return;
            context.atoRunning = false;
            context.atoTrajectory = List.of();
            context.atoFrameIndex = 0;
            context.emergencyLatched = true;
            context.departureAuthorized = false;
            context.departureState = "ATP_EMERGENCY_BRAKE";
            context.mode = DrivingMode.EMERGENCY;
            context.controlSource = SOURCE_EMERGENCY;
            context.lastCommand = "ATP_EMERGENCY_BRAKE";
            context.lastUpdatedAt = System.currentTimeMillis();
            startEmergencyTrajectory(context);
            publishToDispatch(context);
        }
    }

    /** Remove laboratory control ownership without changing any web simulation instance. */
    public void unregisterSimulation(String trainId) {
        if (trainId == null || trainId.isBlank()) return;
        contexts.remove(trainId);
        if (dispatchSimulationService != null) {
            dispatchSimulationService.releaseDriverDeskControl(trainId);
        }
    }

    /** Thread-safe read model for laboratory output adapters; never exposes mutable context. */
    public ControlStateSnapshot snapshot(String trainId) {
        ActiveSimulationContext context = contexts.get(trainId);
        if (context == null) return null;
        synchronized (context) {
            TrainState state = copy(context.currentState);
            // Vehicle dynamics keeps position relative to the selected origin
            // station. Every cross-system consumer must use this absolute value.
            double absolutePositionM = absolutePosition(context, state);
            state.setAbsolutePosition(absolutePositionM);
            return new ControlStateSnapshot(context.trainId, context.fromStationId, context.toStationId,
                    context.currentStationId, context.currentTargetStationId,
                    stationAbsolutePosition(context.currentTargetStationId),
                    state, absolutePositionM, context.mode.name(), context.controlSource, context.lastCommand,
                    Double.isFinite(context.lastLevel) ? context.lastLevel : 0,
                    context.emergencyLatched, context.departureAuthorized, context.doorsClosed,
                    context.doorState, context.atoRunning, context.doorCycleRequired, context.keySwitchOn,
                    context.keyPreparationComplete,
                    context.directionHandle, context.masterHandle,
                    context.lastUpdatedAt);
        }
    }

    /** Snapshot the train IDs currently participating in the laboratory control model. */
    public List<String> activeLaboratoryTrainIds() {
        return new ArrayList<>(contexts.keySet());
    }

    public record ControlStateSnapshot(String trainId, int fromStationId, int toStationId,
                                       int currentStationId, int nextTargetStationId,
                                       double nextTargetAbsolutePositionM,
                                       TrainState state, double absolutePositionM,
                                       String mode, String controlSource,
                                       String lastCommand, double lastLevelPercent,
                                       boolean emergencyLatched, boolean departureAuthorized,
                                       boolean doorsClosed, String doorState,
                                       boolean atoRunning, boolean doorCycleRequired,
                                       boolean keySwitchOn,
                                       boolean keyPreparationComplete,
                                       int directionHandle, int masterHandle,
                                       long lastUpdatedAt) {}

    /**
     * Applies discrete cab inputs that are not motion commands. The PLC sends a
     * continuous 46-byte image, so door buttons are handled on their rising edge.
     * The simulator treats a completed open/close cycle as its local door-lock
     * confirmation; no physical PLC output is implied here.
     */
    public void updateCabInputs(String trainId, Map<String, Object> fields) {
        if (trainId == null || trainId.isBlank() || fields == null) return;
        ActiveSimulationContext context = contexts.get(trainId);
        if (context == null) return;
        synchronized (context) {
            if (fields.containsKey("key_switch_on")) {
                updateKeyPreparation(context, bool(fields.get("key_switch_on")));
            }
            Integer directionHandle = integer(fields.get("direction_handle"));
            if (directionHandle != null) context.directionHandle = directionHandle;
            Integer masterHandle = integer(fields.get("master_handle"));
            if (masterHandle != null) context.masterHandle = masterHandle;
            boolean openRight = bool(fields.get("door_open_right"));
            boolean closeRight = bool(fields.get("door_close_right"));
            boolean openLeft = bool(fields.get("door_open_left"));
            boolean closeLeft = bool(fields.get("door_close_left"));
            boolean atoStart = bool(fields.get("ato_start_btn"));

            // PLC carries a cyclic image. Keep only a fresh edge so a held
            // button cannot be replayed when a later signal cycle authorizes it.
            context.atoStartRisingEdge = atoStart && !context.atoStartPressed;
            context.atoStartPressed = atoStart;

            if ((openRight && !context.rightDoorOpenPressed)
                    || (openLeft && !context.leftDoorOpenPressed)) {
                if (context.currentState.getVelocity() <= STOPPED_SPEED_MPS && !context.atoRunning) {
                    context.doorsClosed = false;
                    context.doorOpenedAtStop = true;
                    context.doorState = openRight ? "RIGHT_OPEN" : "LEFT_OPEN";
                    context.departureAuthorized = false;
                    context.departureState = "DOOR_OPEN";
                    context.lastCommand = context.doorState;
                }
            }
            if ((closeRight && !context.rightDoorClosePressed)
                    || (closeLeft && !context.leftDoorClosePressed)) {
                if (context.currentState.getVelocity() <= STOPPED_SPEED_MPS && context.doorOpenedAtStop) {
                    context.doorsClosed = true;
                    context.doorCycleRequired = false;
                    context.doorState = "CLOSED";
                    // Closing doors records only a completed station operation.
                    // The next movement still needs a new route/MA authorization.
                    context.departureAuthorized = false;
                    context.departureState = "READY_TO_DEPART";
                    context.lastCommand = closeRight ? "RIGHT_DOOR_CLOSE" : "LEFT_DOOR_CLOSE";
                }
            }
            context.rightDoorOpenPressed = openRight;
            context.rightDoorClosePressed = closeRight;
            context.leftDoorOpenPressed = openLeft;
            context.leftDoorClosePressed = closeLeft;
            context.lastUpdatedAt = System.currentTimeMillis();
            publishToDispatch(context);
        }
    }

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
                    || "ATO_START".equals(command)
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

            if ("ATO_START".equals(command)) {
                if (SOURCE_PLC.equals(source) && !context.atoRunning && !consumeAtoStartRisingEdge(context)) {
                    return reject(lifecycle, "ATO_START_NOT_NEW_EDGE");
                }
                return startAto(context, mapped, lifecycle);
            }

            // Once ATO owns traction, the real desk keeps publishing its
            // spring-return master handle at zero. It is an expected keepalive,
            // not a rejected manual command or a request to leave ATO.
            if (!emergency && context.mode == DrivingMode.ATO && context.atoRunning
                    && "coast".equals(command)) {
                context.lastUpdatedAt = System.currentTimeMillis();
                lifecycle.setResultMode(DrivingMode.ATO.name());
                lifecycle.setControlSource(SOURCE_ATO);
                lifecycle.setDepartureState("RUNNING");
                lifecycle.setExecutedState(copy(context.currentState));
                lifecycle.setEmergencyLatchedAfter(false);
                lifecycle.setStatus("EXECUTED");
                return lifecycle;
            }

            // A service-brake action is an explicit driver takeover. It stops
            // the automatic timeline and then follows the normal MANUAL brake
            // path below. EB remains the higher-priority branch.
            if (!emergency && "brake".equals(command) && context.mode == DrivingMode.ATO) {
                context.atoRunning = false;
                context.atoTrajectory = List.of();
                context.atoFrameIndex = 0;
                context.mode = DrivingMode.MANUAL;
                context.controlSource = source;
                context.departureAuthorized = true;
                context.departureState = "RUNNING";
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
                } else if ("DEPART_CONFIRM".equals(command)) {
                    if (!context.departureAuthorized) {
                        return reject(lifecycle, "SIGNAL_DEPARTURE_NOT_AUTHORIZED");
                    }
                }
                context.lastCommand = command;
                context.lastUpdatedAt = System.currentTimeMillis();
                lifecycle.setResultMode(context.mode.name());
                lifecycle.setControlSource(context.controlSource);
                lifecycle.setExecutedState(copy(context.currentState));
                lifecycle.setDepartureState(context.departureState);
                lifecycle.setEmergencyLatchedAfter(context.emergencyLatched);
                lifecycle.setStatus("EXECUTED");
                publishToDispatch(context);
                return lifecycle;
            }

            // Validate the normalized semantic level, not the PLC WORD itself.
            // The physical desk uses a documented 0-100 traction WORD, but its
            // observed service-brake WORD is 0x9Cxx/0x9Dxx and is normalized by
            // Protocol704FrameParser before it reaches this bridge.
            if (("traction".equals(command) && !finiteInRange(mapped.getLevelPercent()))
                    || ("brake".equals(command) && !finiteInRange(mapped.getLevelPercent()))) {
                return reject(lifecycle, "INVALID_LEVEL");
            }

            if (mapped.getDirection() != null && !"FORWARD".equals(mapped.getDirection())
                    && !"ZERO".equals(mapped.getDirection()) && !"REVERSE".equals(mapped.getDirection())) {
                return reject(lifecycle, "UNKNOWN_DIRECTION");
            }

            // After a protected intermediate stop, a coast frame clears only
            // the handle-reset interlock. It never grants a new departure.
            if (context.directHandleControl && context.awaitingNeutralAfterStation
                    && "coast".equals(command)) {
                context.awaitingNeutralAfterStation = false;
                context.controlSource = source;
                if (connectionId != null) {
                    context.plcConnectionId = connectionId;
                    context.plcPort = port;
                }
                context.lastCommand = "coast";
                context.lastLevel = 0.0;
                context.lastUpdatedAt = System.currentTimeMillis();
                context.lastPhysicalStepAt = context.lastUpdatedAt;
                lifecycle.setControlSource(context.controlSource);
                lifecycle.setResultMode(context.mode.name());
                lifecycle.setDepartureState(context.departureState);
                lifecycle.setExecutedState(copy(context.currentState));
                lifecycle.setEmergencyLatchedAfter(context.emergencyLatched);
                lifecycle.setStatus("EXECUTED");
                publishToDispatch(context);
                return lifecycle;
            }

            if (!emergency && !context.departureAuthorized) return reject(lifecycle, "NOT_READY_TO_DEPART");
            if (!emergency && context.mode != DrivingMode.MANUAL) return reject(lifecycle, "MODE_NOT_MANUAL");

            if (!emergency && Boolean.TRUE.equals(turnbackInProgress.get(trainId)) && isOrdinaryMotionCommand) {
                return reject(lifecycle, "TURNBACK_IN_PROGRESS");
            }

            long now = System.currentTimeMillis();
            boolean steadyInput = command.equals(context.lastCommand)
                    && Double.compare(mapped.getLevelPercent(), context.lastLevel) == 0
                    && Objects.equals(context.controlSource, source);
            // Repeated PLC frames mean the driver is holding the handle. Keep the
            // command alive, but advance at the vehicle model's 0.5 s sample rate.
            if (!emergency && steadyInput && now - context.lastPhysicalStepAt < MIN_CONTINUATION_INTERVAL_MS) {
                context.lastUpdatedAt = now;
                lifecycle.setExecutedState(copy(context.currentState));
                lifecycle.setResultMode(context.mode.name());
                lifecycle.setDepartureState(context.departureState);
                lifecycle.setEmergencyLatchedAfter(context.emergencyLatched);
                lifecycle.setStatus("EXECUTED");
                return lifecycle;
            }

            if (emergency && context.emergencyLatched) {
                lifecycle.setResultMode(DrivingMode.EMERGENCY.name());
                lifecycle.setControlSource(SOURCE_EMERGENCY);
                lifecycle.setExecutedState(copy(context.currentState));
                lifecycle.setEmergencyLatchedAfter(true);
                lifecycle.setStatus("EXECUTED");
                lifecycle.setDepartureState(context.departureState);
                context.lastUpdatedAt = now;
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
                context.lastUpdatedAt = now;
                context.lastPhysicalStepAt = now;
                updateStationStop(context);
                lifecycle.setControlSource(context.controlSource);
                lifecycle.setExecutedState(copy(context.currentState));
                lifecycle.setResultMode(context.mode.name());
                lifecycle.setDepartureState(context.departureState);
                lifecycle.setEmergencyLatchedAfter(context.emergencyLatched);
                // 存储完整 SimulationResult，前端用它把 EB 制动轨迹拼接到主 result.states 后面播放
                lifecycle.setExecutedResult(result);
                lifecycle.setStatus("EXECUTED");
                publishToDispatch(context);
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

    private static boolean consumeAtoStartRisingEdge(ActiveSimulationContext context) {
        boolean edge = context.atoStartRisingEdge;
        context.atoStartRisingEdge = false;
        return edge;
    }

    /**
     * The laboratory preparation requires the physical key transition
     * open -> closed -> open. A cyclic PLC image only carries its current bit,
     * so retain this small transition history per registered desk session.
     */
    private static void updateKeyPreparation(ActiveSimulationContext context, boolean keySwitchOn) {
        boolean previous = context.keySwitchOn;
        context.keySwitchOn = keySwitchOn;

        if (!previous && keySwitchOn) {
            if (context.keyOpenedOnce && context.keyClosedAfterOpen) {
                context.keyPreparationComplete = true;
            } else {
                context.keyOpenedOnce = true;
            }
        } else if (previous && !keySwitchOn && context.keyOpenedOnce) {
            context.keyClosedAfterOpen = true;
        }
    }

    /** Advances backend-owned ATO and ATP emergency-brake trajectories. */
    @Scheduled(fixedRate = 500)
    public void advanceAtoSessions() {
        for (ActiveSimulationContext context : contexts.values()) {
            synchronized (context) {
                if (context.emergencyBrakeRunning) {
                    advanceEmergencyTrajectory(context);
                    continue;
                }
                if (!context.atoRunning && !context.emergencyLatched
                        && context.laboratoryAutoDeparture) {
                    if (advanceAutomaticDoorCycle(context)) {
                        context.lastUpdatedAt = System.currentTimeMillis();
                        publishToDispatch(context);
                        continue;
                    }
                    if (canAutomaticallyStartNextLeg(context)) {
                        MappedControlCommand automaticStart = new MappedControlCommand();
                        automaticStart.setCommand("ATO_START");
                        automaticStart.setDirection("FORWARD");
                        automaticStart.setMasterHandle(0);
                        startAto(context, automaticStart,
                                lifecycle(context.trainId, automaticStart, SOURCE_ATO,
                                        "automatic-intermediate-departure", -1));
                        continue;
                    }
                }
                if (!context.atoRunning || context.emergencyLatched) continue;
                if (context.atoFrameIndex + 1 < context.atoTrajectory.size()) {
                    context.atoFrameIndex++;
                    context.currentState = copy(context.atoTrajectory.get(context.atoFrameIndex));
                    context.lastCommand = "ATO_RUNNING";
                    context.lastUpdatedAt = System.currentTimeMillis();
                    publishToDispatch(context);
                    continue;
                }
                context.atoRunning = false;
                context.atoTrajectory = List.of();
                context.atoFrameIndex = 0;
                // ATO generated this trajectory for exactly the next station.
                // Numerical integration may stop a little short, so completion
                // must transition to the platform state instead of leaving an
                // ended ATO run in an in-between section state.
                updateStationStop(context, true);
                context.lastUpdatedAt = System.currentTimeMillis();
                publishToDispatch(context);
            }
        }
    }

    private boolean advanceAutomaticDoorCycle(ActiveSimulationContext context) {
        if (!context.automaticDoorCycleActive) return false;
        if (context.automaticDoorDwellTicks > 0) {
            context.automaticDoorDwellTicks--;
            return true;
        }
        context.automaticDoorCycleActive = false;
        context.doorsClosed = true;
        context.doorCycleRequired = false;
        context.doorState = "CLOSED";
        context.departureAuthorized = false;
        context.departureState = context.currentStationId >= context.toStationId
                ? "TERMINAL_DWELL" : "READY_TO_DEPART";
        context.lastCommand = "AUTO_DOOR_CLOSE";
        return true;
    }

    private boolean canAutomaticallyStartNextLeg(ActiveSimulationContext context) {
        return context.currentStationId > context.fromStationId
                && context.currentStationId < context.toStationId
                && context.currentTargetStationId <= context.toStationId
                && context.keySwitchOn
                && context.keyPreparationComplete
                && context.directionHandle == 1
                && context.masterHandle == 0
                && context.doorsClosed
                && !context.doorCycleRequired
                && context.departureAuthorized;
    }

    /**
     * Generates the complete ATP emergency-brake curve once, then publishes one
     * physically integrated state every 500 ms. The bridge remains the single
     * source of the desk-visible state throughout the stop.
     */
    private void startEmergencyTrajectory(ActiveSimulationContext context) {
        try {
            double targetPosition = stationDistanceFromOrigin(context, context.currentTargetStationId);
            SimulationControlRequest request = new SimulationControlRequest();
            request.setFromStationId(context.currentStationId);
            request.setToStationId(context.currentTargetStationId);
            request.setCurrentState(copy(context.currentState));
            request.setCurrentMode(DrivingMode.EMERGENCY);
            request.setControlCommand(new ControlCommand("atp_emergency_brake", 2.5, 100.0));
            request.setDepartureConfirmed(true);
            request.setTotalTargetPosition(targetPosition);
            request.setNextStationId(context.currentTargetStationId);
            request.setNextStationName(stationName(context.currentTargetStationId));
            LineProfile line = lineProfileJsonLoader.buildLineProfile(
                    context.currentStationId, context.currentTargetStationId);
            SimulationResult result = vehicleSimulationService.runContinuation(
                    request, demoScenarioProvider.buildScenario(line), targetPosition);
            if (result.getStates() == null || result.getStates().isEmpty()) {
                context.emergencyBrakeRunning = false;
                return;
            }
            context.emergencyTrajectory = result.getStates().stream()
                    .map(Protocol704VehicleControlBridge::copy).toList();
            context.emergencyFrameIndex = 0;
            context.emergencyBrakeRunning = true;
            context.currentState = copy(context.emergencyTrajectory.get(0));
        } catch (Exception ex) {
            // A failed local integration must still leave the physical desk in
            // the latched emergency state. The caller can observe this through
            // the bridge snapshot and must not resume traction automatically.
            context.emergencyBrakeRunning = false;
            context.emergencyTrajectory = List.of();
            context.emergencyFrameIndex = 0;
        }
    }

    private void advanceEmergencyTrajectory(ActiveSimulationContext context) {
        if (context.emergencyFrameIndex + 1 < context.emergencyTrajectory.size()) {
            context.emergencyFrameIndex++;
            context.currentState = copy(context.emergencyTrajectory.get(context.emergencyFrameIndex));
            context.lastCommand = "ATP_EMERGENCY_BRAKE";
            context.lastUpdatedAt = System.currentTimeMillis();
            publishToDispatch(context);
            return;
        }
        context.emergencyBrakeRunning = false;
        context.emergencyTrajectory = List.of();
        context.emergencyFrameIndex = 0;
        context.currentState.setVelocity(0.0);
        context.currentState.setAcceleration(0.0);
        context.currentState.setPhase(SimulationPhase.STOPPED);
        context.lastCommand = "ATP_EMERGENCY_BRAKE";
        context.lastUpdatedAt = System.currentTimeMillis();
        publishToDispatch(context);
    }

    private Protocol704CommandLifecycle startAto(ActiveSimulationContext context,
                                                  MappedControlCommand mapped,
                                                  Protocol704CommandLifecycle lifecycle) {
        lifecycle.setStatus("VALIDATED");
        if (context.atoRunning) {
            lifecycle.setResultMode(DrivingMode.ATO.name());
            lifecycle.setControlSource(SOURCE_ATO);
            lifecycle.setDepartureState("RUNNING");
            lifecycle.setExecutedState(copy(context.currentState));
            lifecycle.setEmergencyLatchedAfter(false);
            lifecycle.setStatus("EXECUTED");
            return lifecycle;
        }
        if (!context.keySwitchOn) return reject(lifecycle, "KEY_SWITCH_NOT_ON");
        if (SOURCE_PLC.equals(lifecycle.getSource()) && !context.keyPreparationComplete) {
            return reject(lifecycle, "KEY_CYCLE_INCOMPLETE");
        }
        if (!context.departureAuthorized) return reject(lifecycle, "SIGNAL_DEPARTURE_NOT_AUTHORIZED");
        if (!context.doorsClosed) return reject(lifecycle, "DOORS_NOT_CLOSED");
        if (context.doorCycleRequired) return reject(lifecycle, "DOOR_CYCLE_INCOMPLETE");
        if (!"FORWARD".equals(mapped.getDirection())) return reject(lifecycle, "ATO_DIRECTION_NOT_FORWARD");
        if (mapped.getMasterHandle() != 0) return reject(lifecycle, "ATO_MASTER_HANDLE_NOT_ZERO");
        if (context.currentTargetStationId > context.toStationId) return reject(lifecycle, "NO_NEXT_STATION");

        context.departureAuthorized = true;
        try {
            double targetPosition = stationDistanceFromOrigin(context, context.currentTargetStationId);
            SimulationControlRequest request = new SimulationControlRequest();
            request.setFromStationId(context.currentStationId);
            request.setToStationId(context.currentTargetStationId);
            request.setCurrentState(copy(context.currentState));
            request.setCurrentMode(DrivingMode.ATO);
            request.setControlCommand(new ControlCommand("resume_ato", 0.0, 0.0));
            request.setDepartureConfirmed(true);
            request.setTotalTargetPosition(targetPosition);
            request.setNextStationId(context.currentTargetStationId);
            request.setNextStationName(stationName(context.currentTargetStationId));
            LineProfile line = lineProfileJsonLoader.buildLineProfile(
                    context.currentStationId, context.currentTargetStationId);
            SimulationResult result = vehicleSimulationService.runContinuation(
                    request, demoScenarioProvider.buildScenario(line), targetPosition);
            if (result.getStates() == null || result.getStates().isEmpty()) {
                return reject(lifecycle, "ATO_TRAJECTORY_EMPTY");
            }
            context.atoTrajectory = result.getStates().stream().map(Protocol704VehicleControlBridge::copy).toList();
            context.atoFrameIndex = 0;
            context.atoRunning = true;
            context.mode = DrivingMode.ATO;
            context.controlSource = SOURCE_ATO;
            context.departureState = "RUNNING";
            context.lastCommand = "ATO_START";
            context.lastLevel = 0.0;
            context.lastUpdatedAt = System.currentTimeMillis();
            lifecycle.setResultMode(context.mode.name());
            lifecycle.setControlSource(context.controlSource);
            lifecycle.setDepartureState(context.departureState);
            lifecycle.setExecutedState(copy(context.currentState));
            lifecycle.setEmergencyLatchedAfter(false);
            lifecycle.setStatus("EXECUTED");
            publishToDispatch(context);
            return lifecycle;
        } catch (Exception ex) {
            lifecycle.setExecutionError(ex.getClass().getSimpleName() + ": " + ex.getMessage());
            lifecycle.setStatus("FAILED");
            return lifecycle;
        }
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
        updateStationStop(context, false);
    }

    private void updateStationStop(ActiveSimulationContext context, boolean forceAtoTarget) {
        TrainState state = context.currentState;
        double targetPosition = stationDistanceFromOrigin(context, context.currentTargetStationId);
        // A driver may brake to zero anywhere in the section. Only a stop within
        // the configured approach tolerance is an arrival at the next station.
        if (!forceAtoTarget && state.getPosition() < targetPosition - STATION_STOP_MARGIN_M) return;
        if (!forceAtoTarget && state.getVelocity() > STOPPED_SPEED_MPS) return;

        // Continuation physics can stop a few metres short on a conservative
        // approach. Treat the configured platform tolerance as the authority,
        // snap the software train to the target and require a new departure.
        state.setPosition(targetPosition);
        state.setAbsolutePosition(stationAbsolutePosition(context.currentTargetStationId));
        state.setVelocity(0.0);
        state.setAcceleration(0.0);
        state.setPhase(SimulationPhase.STOPPED);

        context.currentStationId = context.currentTargetStationId;
        if (context.currentTargetStationId < context.toStationId) {
            context.currentTargetStationId++;
            context.departureAuthorized = false;
            context.departureState = "READY_TO_DEPART";
            context.lastCommand = "STATION_DWELL";
            context.awaitingNeutralAfterStation = context.directHandleControl;
            context.doorCycleRequired = true;
            context.doorOpenedAtStop = false;
            context.doorsClosed = true;
            context.doorState = "WAITING_DOOR_OPEN";
        } else {
            context.departureAuthorized = false;
            context.departureState = "TERMINAL_DWELL";
            context.lastCommand = "TERMINAL_DWELL";
            context.awaitingNeutralAfterStation = false;
            context.doorCycleRequired = false;
            context.doorState = "TERMINAL_DWELL";
        }
        if (context.laboratoryAutoDeparture && context.currentStationId > context.fromStationId) {
            context.automaticDoorCycleActive = true;
            context.automaticDoorDwellTicks = AUTOMATIC_DOOR_DWELL_TICKS;
            context.doorsClosed = false;
            context.doorCycleRequired = true;
            context.doorOpenedAtStop = true;
            context.doorState = "RIGHT_OPEN";
            context.departureState = "DOOR_OPEN";
            context.lastCommand = "AUTO_DOOR_OPEN";
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
        pendingDispatchReports.put(context.trainId, report);
    }

    /**
     * Crosses into the synchronized dispatch service without holding a vehicle
     * context monitor. A newer report may replace an older queued frame, which
     * is intentional: all downstream consumers need the latest authoritative
     * state, not every 500 ms playback sample.
     */
    @Scheduled(fixedRate = 50)
    public void flushDispatchReports() {
        if (dispatchSimulationService == null || pendingDispatchReports.isEmpty()) return;
        for (Map.Entry<String, StatusReport> entry : new ArrayList<>(pendingDispatchReports.entrySet())) {
            String trainId = entry.getKey();
            StatusReport report = entry.getValue();
            if (!pendingDispatchReports.remove(trainId, report)) continue;
            try {
                dispatchSimulationService.acceptOnboardReport(report);
            } catch (RuntimeException ignored) {
                // A later bridge frame will retry with fresher state. Never let
                // one dispatch failure stop Spring's scheduled flush task.
            }
        }
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

    private static boolean bool(Object value) {
        return Boolean.TRUE.equals(value);
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
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
        boolean directHandleControl;
        boolean laboratoryAutoDeparture;
        boolean awaitingNeutralAfterStation;
        boolean keySwitchOn;
        boolean keyOpenedOnce;
        boolean keyClosedAfterOpen;
        boolean keyPreparationComplete;
        int directionHandle;
        int masterHandle;
        boolean doorsClosed = true;
        boolean doorCycleRequired;
        boolean doorOpenedAtStop;
        String doorState = "CLOSED";
        boolean rightDoorOpenPressed;
        boolean rightDoorClosePressed;
        boolean leftDoorOpenPressed;
        boolean leftDoorClosePressed;
        boolean atoStartPressed;
        boolean atoStartRisingEdge;
        boolean atoRunning;
        boolean automaticDoorCycleActive;
        int automaticDoorDwellTicks;
        List<TrainState> atoTrajectory = List.of();
        int atoFrameIndex;
        boolean emergencyBrakeRunning;
        List<TrainState> emergencyTrajectory = List.of();
        int emergencyFrameIndex;
        String plcConnectionId;
        int plcPort = -1;
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
