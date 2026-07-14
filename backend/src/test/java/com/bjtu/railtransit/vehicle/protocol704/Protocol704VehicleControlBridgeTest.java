package com.bjtu.railtransit.vehicle.protocol704;

import com.bjtu.railtransit.dispatch.MultiParticleSimulationService;
import com.bjtu.railtransit.vehicle.dto.SimulationResult;
import com.bjtu.railtransit.vehicle.dto.TrainState;
import com.bjtu.railtransit.vehicle.enums.DrivingMode;
import com.bjtu.railtransit.vehicle.enums.SimulationPhase;
import com.bjtu.railtransit.vehicle.service.DemoScenarioProvider;
import com.bjtu.railtransit.vehicle.service.LineProfileJsonLoader;
import com.bjtu.railtransit.vehicle.service.VehicleSimulationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Protocol704VehicleControlBridgeTest {
    private DemoScenarioProvider provider;
    private LineProfileJsonLoader loader;
    private VehicleSimulationService vehicleService;
    private Protocol704VehicleControlBridge bridge;

    @BeforeEach
    void setUp() {
        provider = new DemoScenarioProvider();
        loader = new LineProfileJsonLoader();
        vehicleService = new VehicleSimulationService(provider, new MultiParticleSimulationService());
        bridge = new Protocol704VehicleControlBridge(vehicleService, provider, loader);
    }

    @Test
    void rejectsNormalCommandWithoutActiveSimulation() {
        assertEquals("NO_ACTIVE_SIMULATION", bridge.execute("OB1", command("traction", 50)).getRejectionReason());
    }

    @Test
    void atoRejectsTractionButEmergencyBrakeExecutesAndLatches() {
        register("OB1", DrivingMode.ATO);
        assertEquals("MODE_NOT_MANUAL", bridge.execute("OB1", command("traction", 50)).getRejectionReason());

        Protocol704CommandLifecycle emergency = bridge.execute("OB1", command("emergency_brake", 100));
        assertEquals("EXECUTED", emergency.getStatus());
        assertEquals("EMERGENCY", emergency.getResultMode());
        assertEquals("EMERGENCY_BRAKE_LATCHED", bridge.execute("OB1", command("traction", 50)).getRejectionReason());
    }

    @Test
    void ebLatchRejectsSetManual() {
        register("EB1", DrivingMode.MANUAL);
        bridge.execute("EB1", command("emergency_brake", 100));
        Protocol704CommandLifecycle rejected = bridge.execute("EB1", command("SET_MANUAL", 0));
        assertEquals("REJECTED", rejected.getStatus());
        assertEquals("EMERGENCY_BRAKE_LATCHED", rejected.getRejectionReason());
        assertEquals("EMERGENCY", rejected.getResultMode());
        assertEquals("EMERGENCY", rejected.getControlSource());
    }

    @Test
    void ebLatchRejectsResumeAto() {
        register("EB2", DrivingMode.MANUAL);
        bridge.execute("EB2", command("emergency_brake", 100));
        Protocol704CommandLifecycle rejected = bridge.execute("EB2", command("RESUME_ATO", 0));
        assertEquals("REJECTED", rejected.getStatus());
        assertEquals("EMERGENCY_BRAKE_LATCHED", rejected.getRejectionReason());
        assertEquals("EMERGENCY", rejected.getResultMode());
    }

    @Test
    void ebLatchRejectsTraction() {
        register("EB3", DrivingMode.MANUAL);
        bridge.execute("EB3", command("emergency_brake", 100));
        Protocol704CommandLifecycle rejected = bridge.execute("EB3", command("traction", 50));
        assertEquals("REJECTED", rejected.getStatus());
        assertEquals("EMERGENCY_BRAKE_LATCHED", rejected.getRejectionReason());
    }

    @Test
    void ebLatchRejectsCoast() {
        register("EB4", DrivingMode.MANUAL);
        bridge.execute("EB4", command("emergency_brake", 100));
        Protocol704CommandLifecycle rejected = bridge.execute("EB4", command("coast", 0));
        assertEquals("REJECTED", rejected.getStatus());
        assertEquals("EMERGENCY_BRAKE_LATCHED", rejected.getRejectionReason());
    }

    @Test
    void ebLatchRejectsNormalBrake() {
        register("EB5", DrivingMode.MANUAL);
        bridge.execute("EB5", command("emergency_brake", 100));
        Protocol704CommandLifecycle rejected = bridge.execute("EB5", command("brake", 60));
        assertEquals("REJECTED", rejected.getStatus());
        assertEquals("EMERGENCY_BRAKE_LATCHED", rejected.getRejectionReason());
    }

    @Test
    void ebLatchKeepsModeEmergency() {
        register("EB6", DrivingMode.MANUAL);
        bridge.execute("EB6", command("emergency_brake", 100));
        bridge.execute("EB6", command("SET_MANUAL", 0));
        bridge.execute("EB6", command("traction", 50));
        bridge.execute("EB6", command("RESUME_ATO", 0));
        assertTrue(bridge.isEmergencyLatched("EB6"));
    }

    @Test
    void ebLatchKeepsControlSourceEmergency() {
        register("EB7", DrivingMode.MANUAL);
        Protocol704CommandLifecycle eb = bridge.execute("EB7", command("emergency_brake", 100));
        assertEquals("EMERGENCY", eb.getControlSource());
        Protocol704CommandLifecycle rejected = bridge.execute("EB7", command("SET_MANUAL", 0));
        assertEquals("EMERGENCY", rejected.getControlSource());
    }

    @Test
    void rejectedModeCommandDoesNotChangeVehicleState() {
        register("EB8", DrivingMode.MANUAL);
        bridge.execute("EB8", command("emergency_brake", 100));
        Protocol704CommandLifecycle rejected = bridge.execute("EB8", command("traction", 50));
        assertNull(rejected.getExecutedState());
        assertEquals("REJECTED", rejected.getStatus());
    }

    @Test
    void repeatedEmergencyBrakeIsIdempotent() {
        register("EB_REPEAT", DrivingMode.MANUAL);
        Protocol704CommandLifecycle first = bridge.execute("EB_REPEAT", command("emergency_brake", 100));
        double firstPosition = first.getExecutedState().getPosition();
        double firstVelocity = first.getExecutedState().getVelocity();

        Protocol704CommandLifecycle repeated = bridge.execute("EB_REPEAT", command("emergency_brake", 100));

        assertEquals("EXECUTED", repeated.getStatus());
        assertEquals("EMERGENCY", repeated.getResultMode());
        assertEquals(firstPosition, repeated.getExecutedState().getPosition(), 0.000001);
        assertEquals(firstVelocity, repeated.getExecutedState().getVelocity(), 0.000001);
    }

    @Test
    void repeatedEbDoesNotAdvanceTrajectory() {
        register("EB_REPEAT2", DrivingMode.MANUAL);
        bridge.execute("EB_REPEAT2", command("traction", 50));
        Protocol704CommandLifecycle eb1 = bridge.execute("EB_REPEAT2", command("emergency_brake", 100));
        double posAfterEb1 = eb1.getExecutedState().getPosition();
        double velAfterEb1 = eb1.getExecutedState().getVelocity();

        Protocol704CommandLifecycle eb2 = bridge.execute("EB_REPEAT2", command("emergency_brake", 100));
        Protocol704CommandLifecycle eb3 = bridge.execute("EB_REPEAT2", command("emergency_brake", 100));

        assertEquals(posAfterEb1, eb2.getExecutedState().getPosition(), 0.000001);
        assertEquals(velAfterEb1, eb2.getExecutedState().getVelocity(), 0.000001);
        assertEquals(posAfterEb1, eb3.getExecutedState().getPosition(), 0.000001);
    }

    @Test
    void plcDisconnectDoesNotReleaseEb() {
        register("EB_DISC", DrivingMode.MANUAL);
        bridge.execute("EB_DISC", command("emergency_brake", 100), "PLC_704_LOCAL_V1", "conn-1", 8001);
        assertTrue(bridge.isEmergencyLatched("EB_DISC"));
        bridge.notifyPlcDisconnected("EB_DISC", 8001, "conn-1");
        assertTrue(bridge.isEmergencyLatched("EB_DISC"));
        assertEquals("EMERGENCY_BRAKE_LATCHED", bridge.execute("EB_DISC", command("traction", 50)).getRejectionReason());
    }

    @Test
    void plcReconnectDoesNotReleaseEb() {
        register("EB_RECONN", DrivingMode.MANUAL);
        bridge.execute("EB_RECONN", command("emergency_brake", 100), "PLC_704_LOCAL_V1", "conn-1", 8001);
        bridge.notifyPlcDisconnected("EB_RECONN", 8001, "conn-1");
        Protocol704CommandLifecycle tractionFromNew = bridge.execute("EB_RECONN", command("traction", 50), "PLC_704_LOCAL_V1", "conn-2", 8002);
        assertEquals("EMERGENCY_BRAKE_LATCHED", tractionFromNew.getRejectionReason());
        assertTrue(bridge.isEmergencyLatched("EB_RECONN"));
    }

    @Test
    void localTestFrameCannotBypassEb() {
        register("EB_TEST", DrivingMode.MANUAL);
        bridge.execute("EB_TEST", command("emergency_brake", 100));
        Protocol704CommandLifecycle testTraction = bridge.executeFromTestFrame("EB_TEST", command("traction", 50));
        assertEquals("REJECTED", testTraction.getStatus());
        assertEquals("EMERGENCY_BRAKE_LATCHED", testTraction.getRejectionReason());
        assertEquals("LOCAL_TEST", testTraction.getSource());
    }

    @Test
    void multiPortOrdinaryControlConflicts() {
        register("PORTS", DrivingMode.MANUAL);
        Protocol704CommandLifecycle first = bridge.execute("PORTS", command("traction", 50), "PLC_704_LOCAL_V1", "conn-8001", 8001);
        Protocol704CommandLifecycle conflict = bridge.execute("PORTS", command("coast", 0), "PLC_704_LOCAL_V1", "conn-8002", 8002);

        assertEquals("EXECUTED", first.getStatus());
        assertEquals("REJECTED", conflict.getStatus());
        assertEquals("CONTROL_SOURCE_CONFLICT", conflict.getRejectionReason());
    }

    @Test
    void multiPortEmergencyStillWins() {
        register("PORTS_EB", DrivingMode.MANUAL);
        bridge.execute("PORTS_EB", command("traction", 50), "PLC_704_LOCAL_V1", "conn-8001", 8001);
        Protocol704CommandLifecycle emergency = bridge.execute("PORTS_EB", command("emergency_brake", 100), "PLC_704_LOCAL_V1", "conn-8002", 8002);

        assertEquals("EXECUTED", emergency.getStatus());
        assertEquals("EMERGENCY", emergency.getResultMode());
        assertTrue(bridge.isEmergencyLatched("PORTS_EB"));
    }

    @Test
    void failedExecutionDoesNotTransferControl() {
        register("FAIL", DrivingMode.MANUAL);
        MappedControlCommand reverse = command("traction", 50);
        reverse.setDirection("REVERSE");

        Protocol704CommandLifecycle failed = bridge.execute("FAIL", reverse, "PLC_704_LOCAL_V1", "conn-1", 8001);

        assertEquals("FAILED", failed.getStatus());
        assertNotNull(failed.getExecutionError());
    }

    @Test
    void rejectedCommandLifecycleIsRejected() {
        register("LIFECYCLE", DrivingMode.MANUAL);
        bridge.execute("LIFECYCLE", command("emergency_brake", 100));
        Protocol704CommandLifecycle rejected = bridge.execute("LIFECYCLE", command("traction", 50));
        assertEquals("REJECTED", rejected.getStatus());
        assertNotNull(rejected.getRejectionReason());
        assertNull(rejected.getExecutedState());
    }

    @Test
    void serviceExceptionLifecycleIsFailed() {
        register("FAIL2", DrivingMode.MANUAL);
        MappedControlCommand bad = command("traction", 50);
        bad.setDirection("REVERSE");
        Protocol704CommandLifecycle failed = bridge.execute("FAIL2", bad, "PLC_704_LOCAL_V1", "conn-1", 8001);
        assertEquals("FAILED", failed.getStatus());
        assertNotNull(failed.getExecutionError());
    }

    @Test
    void ordinaryCommandsWorkInManualModeWithoutRegression() {
        register("NORMAL", DrivingMode.MANUAL);
        Protocol704CommandLifecycle traction = bridge.execute("NORMAL", command("traction", 50));
        assertEquals("EXECUTED", traction.getStatus());
        assertNotNull(traction.getExecutedState());
        assertTrue(traction.getExecutedState().getAcceleration() > 0.0);
        assertTrue(traction.getExecutedState().getVelocity() > 0.0);
        // The PLC transmits a held handle repeatedly. A frame inside the 0.5 s
        // integrator interval is acknowledged with the current state rather than
        // rejected as a duplicate command.
        assertEquals("EXECUTED", bridge.execute("NORMAL", command("traction", 50)).getStatus());

        Protocol704CommandLifecycle coast = bridge.execute("NORMAL", command("coast", 0));
        assertEquals("EXECUTED", coast.getStatus());

        Protocol704CommandLifecycle brake = bridge.execute("NORMAL", command("brake", 60));
        assertEquals("EXECUTED", brake.getStatus());
        assertTrue(brake.getExecutedState().getVelocity() <= coast.getExecutedState().getVelocity());
        assertTrue(brake.getExecutedState().getAcceleration() < 0.0
                || brake.getExecutedState().getPhase() == SimulationPhase.STOPPED);

        Protocol704CommandLifecycle firstEb = bridge.execute("NORMAL", command("emergency_brake", 100));
        Protocol704CommandLifecycle repeatedEb = bridge.execute("NORMAL", command("emergency_brake", 100));
        assertEquals("EXECUTED", firstEb.getStatus(), firstEb.getExecutionError());
        assertEquals("EXECUTED", repeatedEb.getStatus());
        assertEquals("EMERGENCY", repeatedEb.getResultMode());
    }

    @Test
    void atoModeRejectsTractionAndCoastButAllowsServiceBrakeTakeover() {
        register("ATO1", DrivingMode.ATO);
        assertEquals("MODE_NOT_MANUAL", bridge.execute("ATO1", command("traction", 50)).getRejectionReason());
        assertEquals("MODE_NOT_MANUAL", bridge.execute("ATO1", command("coast", 0)).getRejectionReason());
        Protocol704CommandLifecycle brake = bridge.execute("ATO1", command("brake", 60));
        assertEquals("EXECUTED", brake.getStatus());
        assertEquals("MANUAL", brake.getResultMode());
    }

    @Test
    void atoStartRequiresCabInterlocksThenRunsAutomatically() {
        SimulationResult result = vehicleService.run(provider.buildScenario(loader.buildLineProfile(1, 2)));
        bridge.registerSimulation("ATO_START", 1, 2, result, DrivingMode.ATO, false);
        MappedControlCommand start = command("ATO_START", 0);
        start.setMasterHandle(0);
        bridge.updateCabInputs("ATO_START", java.util.Map.of("key_switch_on", true));

        assertEquals("SIGNAL_DEPARTURE_NOT_AUTHORIZED",
                bridge.execute("ATO_START", start).getRejectionReason());

        bridge.syncDepartureAuth("ATO_START", true);
        Protocol704CommandLifecycle lifecycle = bridge.execute("ATO_START", start);
        assertEquals("EXECUTED", lifecycle.getStatus(), lifecycle.getExecutionError());
        assertEquals("ATO", lifecycle.getResultMode());
        assertEquals("RUNNING", lifecycle.getDepartureState());

        double initialPosition = bridge.snapshot("ATO_START").state().getPosition();
        bridge.advanceAtoSessions();
        bridge.advanceAtoSessions();
        assertTrue(bridge.snapshot("ATO_START").state().getPosition() > initialPosition);
    }

    @Test
    void physicalAtoStartMustBeANewButtonPressAfterSignalAuthorization() {
        SimulationResult result = vehicleService.run(provider.buildScenario(loader.buildLineProfile(1, 2)));
        bridge.registerSimulation("ATO_EDGE", 1, 2, result, DrivingMode.MANUAL, false);
        MappedControlCommand start = command("ATO_START", 0);
        start.setMasterHandle(0);

        // A press made before authorization is rejected and consumed. Keeping
        // the PLC bit high must not turn into an implicit later departure.
        bridge.updateCabInputs("ATO_EDGE", java.util.Map.of(
                "key_switch_on", true, "direction_handle", 0, "master_handle", 0,
                "ato_start_btn", false));
        bridge.updateCabInputs("ATO_EDGE", java.util.Map.of(
                "key_switch_on", false, "direction_handle", 0, "master_handle", 0,
                "ato_start_btn", false));
        bridge.updateCabInputs("ATO_EDGE", java.util.Map.of(
                "key_switch_on", true, "direction_handle", 1, "master_handle", 0,
                "ato_start_btn", true));
        assertEquals("SIGNAL_DEPARTURE_NOT_AUTHORIZED", bridge.execute(
                "ATO_EDGE", start, Protocol704VehicleControlBridge.SOURCE_PLC, "desk-8001", 8001)
                .getRejectionReason());

        bridge.syncDepartureAuth("ATO_EDGE", true);
        bridge.updateCabInputs("ATO_EDGE", java.util.Map.of(
                "key_switch_on", true, "ato_start_btn", true));
        assertEquals("ATO_START_NOT_NEW_EDGE", bridge.execute(
                "ATO_EDGE", start, Protocol704VehicleControlBridge.SOURCE_PLC, "desk-8001", 8001)
                .getRejectionReason());

        bridge.updateCabInputs("ATO_EDGE", java.util.Map.of(
                "key_switch_on", true, "ato_start_btn", false));
        bridge.updateCabInputs("ATO_EDGE", java.util.Map.of(
                "key_switch_on", true, "ato_start_btn", true));
        assertEquals("EXECUTED", bridge.execute(
                "ATO_EDGE", start, Protocol704VehicleControlBridge.SOURCE_PLC, "desk-8001", 8001)
                .getStatus());
    }

    @Test
    void snapshotNormalizesAbsolutePositionWhenTheVehicleStateOnlyHasRelativeMileage() {
        SimulationResult result = vehicleService.run(provider.buildScenario(loader.buildLineProfile(2, 3)));
        result.getStates().get(0).setAbsolutePosition(null);
        bridge.registerSimulation("MIDLINE", 2, 3, result, DrivingMode.MANUAL, false);

        Protocol704VehicleControlBridge.ControlStateSnapshot snapshot = bridge.snapshot("MIDLINE");

        assertEquals(0.0, snapshot.state().getPosition(), 0.001);
        assertEquals(1660.52, snapshot.absolutePositionM(), 0.001);
        assertEquals(snapshot.absolutePositionM(), snapshot.state().getAbsolutePosition(), 0.001);
    }

    @Test
    void neutralHandleFramesAreAcceptedWhileAtoIsRunning() {
        SimulationResult result = vehicleService.run(provider.buildScenario(loader.buildLineProfile(1, 2)));
        bridge.registerSimulation("ATO_NEUTRAL", 1, 2, result, DrivingMode.ATO, true);
        MappedControlCommand start = command("ATO_START", 0);
        start.setMasterHandle(0);
        bridge.updateCabInputs("ATO_NEUTRAL", java.util.Map.of("key_switch_on", true));
        assertEquals("EXECUTED", bridge.execute("ATO_NEUTRAL", start).getStatus());

        Protocol704CommandLifecycle coast = bridge.execute("ATO_NEUTRAL", command("coast", 0));
        assertEquals("EXECUTED", coast.getStatus());
        assertEquals("ATO", coast.getResultMode());
        assertEquals("RUNNING", coast.getDepartureState());
    }

    @Test
    void neutralFramesDoNotCreateDepartureAuthorizationWhileWaitingForAtoStart() {
        SimulationResult result = vehicleService.run(provider.buildScenario(loader.buildLineProfile(1, 2)));
        bridge.registerSimulation("LAB_STANDBY", 1, 2, result, DrivingMode.ATO, false, false);

        MappedControlCommand neutral = command("coast", 0);
        neutral.setMasterHandle(0);
        Protocol704CommandLifecycle lifecycle = bridge.execute("LAB_STANDBY", neutral,
                "PLC_704_LOCAL_V1", "desk-8001", 8001);

        assertEquals("REJECTED", lifecycle.getStatus());
        assertEquals("NOT_READY_TO_DEPART", lifecycle.getRejectionReason());
        assertFalse(bridge.snapshot("LAB_STANDBY").state().getVelocity() > 0.0);
    }

    @Test
    void stationDoorCycleIsRequiredBeforeTheNextAtoStart() {
        SimulationResult result = vehicleService.run(provider.buildScenario(loader.buildLineProfile(1, 3)));
        bridge.registerSimulation("DOOR_CYCLE", 1, 3, result, DrivingMode.MANUAL, true);
        TrainState stoppedAtStationTwo = new TrainState(200.0, 1348.0, 0.0, 0.0,
                SimulationPhase.STOPPED, "DOOR_CYCLE");
        bridge.syncCurrentState("DOOR_CYCLE", stoppedAtStationTwo, DrivingMode.MANUAL, 1, 3, true);
        bridge.execute("DOOR_CYCLE", command("coast", 0));

        MappedControlCommand start = command("ATO_START", 0);
        start.setMasterHandle(0);
        bridge.updateCabInputs("DOOR_CYCLE", java.util.Map.of("key_switch_on", true));
        bridge.syncDepartureAuth("DOOR_CYCLE", true);
        assertEquals("DOOR_CYCLE_INCOMPLETE",
                bridge.execute("DOOR_CYCLE", start).getRejectionReason());

        bridge.updateCabInputs("DOOR_CYCLE", java.util.Map.of("key_switch_on", true, "door_open_right", true));
        bridge.updateCabInputs("DOOR_CYCLE", java.util.Map.of("key_switch_on", true, "door_open_right", false));
        bridge.updateCabInputs("DOOR_CYCLE", java.util.Map.of("key_switch_on", true, "door_close_right", true));
        bridge.updateCabInputs("DOOR_CYCLE", java.util.Map.of("key_switch_on", true, "door_close_right", false));
        bridge.syncDepartureAuth("DOOR_CYCLE", true);

        assertEquals("EXECUTED", bridge.execute("DOOR_CYCLE", start).getStatus());
    }

    @Test
    void closingDoorsRequiresASecondSignalAuthorizationBeforeAtoRestart() {
        SimulationResult result = vehicleService.run(provider.buildScenario(loader.buildLineProfile(1, 3)));
        bridge.registerSimulation("LAB_AUTO", 1, 3, result, DrivingMode.MANUAL, true, false);
        TrainState stoppedAtStationTwo = new TrainState(200.0, 1348.0, 0.0, 0.0,
                SimulationPhase.STOPPED, "LAB_AUTO");
        bridge.syncCurrentState("LAB_AUTO", stoppedAtStationTwo, DrivingMode.MANUAL, 1, 3, true);
        bridge.execute("LAB_AUTO", command("coast", 0));

        MappedControlCommand start = command("ATO_START", 0);
        start.setMasterHandle(0);
        start.setDirection("FORWARD");
        bridge.updateCabInputs("LAB_AUTO", java.util.Map.of("key_switch_on", true));
        assertEquals("SIGNAL_DEPARTURE_NOT_AUTHORIZED", bridge.execute("LAB_AUTO", start).getRejectionReason());

        bridge.updateCabInputs("LAB_AUTO", java.util.Map.of("key_switch_on", true, "door_open_right", true));
        bridge.updateCabInputs("LAB_AUTO", java.util.Map.of("key_switch_on", true, "door_open_right", false));
        bridge.updateCabInputs("LAB_AUTO", java.util.Map.of("key_switch_on", true, "door_close_right", true));
        bridge.updateCabInputs("LAB_AUTO", java.util.Map.of("key_switch_on", true, "door_close_right", false));

        assertFalse(bridge.snapshot("LAB_AUTO").departureAuthorized());
        assertEquals("SIGNAL_DEPARTURE_NOT_AUTHORIZED", bridge.execute("LAB_AUTO", start).getRejectionReason());
        bridge.syncDepartureAuth("LAB_AUTO", true);
        assertEquals("EXECUTED", bridge.execute("LAB_AUTO", start).getStatus());
    }

    @Test
    void modeAndDepartureCommandsWork() {
        SimulationResult result = vehicleService.run(provider.buildScenario(loader.buildLineProfile(1, 2)));
        bridge.registerSimulation("READY", 1, 2, result, DrivingMode.ATO, false);
        assertEquals("EXECUTED", bridge.execute("READY", command("SET_MANUAL", 0)).getStatus());
        assertEquals("MANUAL", bridge.execute("READY", command("SET_MANUAL", 0)).getResultMode());
        assertEquals("EXECUTED", bridge.execute("READY", command("RESUME_ATO", 0)).getStatus());
        Protocol704CommandLifecycle depart = bridge.execute("READY", command("DEPART_CONFIRM", 0));
        assertEquals("SIGNAL_DEPARTURE_NOT_AUTHORIZED", depart.getRejectionReason());
        bridge.syncDepartureAuth("READY", true);
        Protocol704CommandLifecycle authorized = bridge.execute("READY", command("DEPART_CONFIRM", 0));
        assertEquals("EXECUTED", authorized.getStatus());
        assertEquals("ATO", authorized.getResultMode());
    }

    @Test
    void explicitlyArmedDeskSessionAcceptsLeverTractionWithoutSyntheticButtons() {
        SimulationResult result = vehicleService.run(provider.buildScenario(loader.buildLineProfile(1, 2)));
        // The caller explicitly selected laboratory desk mode before creating
        // this new session. The physical desk has no verified mode/depart keys.
        bridge.registerSimulation("DESK_DIRECT", 1, 2, result, DrivingMode.MANUAL, true);

        Protocol704CommandLifecycle traction = bridge.execute(
                "DESK_DIRECT", command("traction", 20), "PLC_704_LOCAL_V1", "desk-8001", 8001);

        assertEquals("EXECUTED", traction.getStatus());
        assertEquals("MANUAL", traction.getResultMode());
        assertEquals("RUNNING", traction.getDepartureState());
        assertTrue(traction.getExecutedState().getVelocity() > 0.0);
    }

    @Test
    void directHandleSessionRequiresCoastResetBeforeDepartingAnIntermediateStation() {
        SimulationResult result = vehicleService.run(provider.buildScenario(loader.buildLineProfile(1, 3)));
        bridge.registerSimulation("DESK_MULTI", 1, 3, result, DrivingMode.MANUAL, true, true);

        TrainState stoppedAtStationTwo = new TrainState(200.0, 1348.0, 0.0, 0.0,
                SimulationPhase.STOPPED, "DESK_MULTI");
        bridge.syncCurrentState("DESK_MULTI", stoppedAtStationTwo, DrivingMode.MANUAL, 1, 3, true);
        bridge.execute("DESK_MULTI", command("traction", 20));

        // The arrival supervisor accepts the stop and puts the direct desk into
        // its one-frame neutral-reset state for the next section.
        Protocol704CommandLifecycle heldTraction = bridge.execute("DESK_MULTI", command("traction", 20));
        assertEquals("NOT_READY_TO_DEPART", heldTraction.getRejectionReason());

        Protocol704CommandLifecycle coast = bridge.execute("DESK_MULTI", command("coast", 0));
        assertEquals("EXECUTED", coast.getStatus());
        assertEquals("READY_TO_DEPART", coast.getDepartureState());

        assertEquals("NOT_READY_TO_DEPART", bridge.execute("DESK_MULTI", command("traction", 20)).getRejectionReason());
        bridge.syncDepartureAuth("DESK_MULTI", true);
        Protocol704CommandLifecycle nextTraction = bridge.execute("DESK_MULTI", command("traction", 20));
        assertEquals("EXECUTED", nextTraction.getStatus());
    }

    @Test
    void invalidLevelIsRejected() {
        register("LEVEL", DrivingMode.MANUAL);
        assertEquals("INVALID_LEVEL", bridge.execute("LEVEL", command("traction", 101)).getRejectionReason());
    }

    @Test
    void normalizedLaboratoryBrakeWordIsAccepted() {
        register("BRAKE_WORD", DrivingMode.MANUAL);
        MappedControlCommand brake = command("brake", 7);
        // The live desk encodes the service-brake position as 0x9Cxx/0x9Dxx.
        // The parser has already normalized this to the 7% semantic level.
        brake.setBrakeLevelRaw(0x9D07);

        assertEquals("EXECUTED", bridge.execute("BRAKE_WORD", brake).getStatus());
    }

    @Test
    void emergencyBrakeFromAnyModeLatches() {
        register("EB_ATO", DrivingMode.ATO);
        Protocol704CommandLifecycle eb = bridge.execute("EB_ATO", command("emergency_brake", 100));
        assertEquals("EXECUTED", eb.getStatus());
        assertEquals("EMERGENCY", eb.getResultMode());
        assertEquals("EMERGENCY", eb.getControlSource());
        assertTrue(bridge.isEmergencyLatched("EB_ATO"));
    }

    @Test
    void lifecycleRecordsSource() {
        register("SRC", DrivingMode.MANUAL);
        Protocol704CommandLifecycle lc = bridge.execute("SRC", command("traction", 50), "PLC_704_LOCAL_V1", "cid", 8001);
        assertEquals("PLC_704_LOCAL_V1", lc.getSource());
        assertEquals("cid", lc.getConnectionId());
        assertEquals(8001, lc.getPort());
    }

    @Test
    void testFrameSourceIsLocalTest() {
        register("TESTSRC", DrivingMode.MANUAL);
        Protocol704CommandLifecycle lc = bridge.executeFromTestFrame("TESTSRC", command("traction", 50));
        assertEquals("LOCAL_TEST", lc.getSource());
    }

    @Test
    void syncModeAllowsTractionInManualAndRejectsInAto() {
        SimulationResult result = vehicleService.run(provider.buildScenario(loader.buildLineProfile(1, 2)));
        bridge.registerSimulation("SYNC1", 1, 2, result, DrivingMode.ATO, true);

        assertEquals("MODE_NOT_MANUAL",
                bridge.executeFromTestFrame("SYNC1", command("traction", 50)).getRejectionReason());

        bridge.syncMode("SYNC1", DrivingMode.MANUAL, Protocol704VehicleControlBridge.SOURCE_WEB_HMI);
        Protocol704CommandLifecycle traction = bridge.executeFromTestFrame("SYNC1", command("traction", 50));
        assertEquals("EXECUTED", traction.getStatus());
        assertTrue(traction.getExecutedState().getVelocity() > 0.0
                || traction.getExecutedState().getAcceleration() > 0.0);

        bridge.syncMode("SYNC1", DrivingMode.ATO, Protocol704VehicleControlBridge.SOURCE_ATO);
        assertEquals("MODE_NOT_MANUAL",
                bridge.executeFromTestFrame("SYNC1", command("traction", 40)).getRejectionReason());
    }

    @Test
    void syncModeEmergencySetsLatchedFlag() {
        register("SYNC_EB", DrivingMode.ATO);
        bridge.syncMode("SYNC_EB", DrivingMode.EMERGENCY, Protocol704VehicleControlBridge.SOURCE_EMERGENCY);
        assertTrue(bridge.isEmergencyLatched("SYNC_EB"));
        assertEquals(DrivingMode.EMERGENCY, bridge.getMode("SYNC_EB"));
    }

    @Test
    void atpEmergencyBrakePublishesARealTrajectoryUntilStopped() {
        register("ATP_EB", DrivingMode.MANUAL);
        TrainState moving = new TrainState(10.0, 500.0, 15.0, 0.0,
                SimulationPhase.TRACTION, "ATP_EB");
        moving.setAbsolutePosition(813.0);
        bridge.syncCurrentState("ATP_EB", moving, DrivingMode.MANUAL, 1, 2, true);

        bridge.applyAtpEmergencyBrake("ATP_EB");
        assertTrue(bridge.isEmergencyLatched("ATP_EB"));
        assertEquals(DrivingMode.EMERGENCY, bridge.getMode("ATP_EB"));
        assertTrue(bridge.snapshot("ATP_EB").state().getVelocity() > 0.0);

        for (int i = 0; i < 80; i++) bridge.advanceAtoSessions();

        TrainState stopped = bridge.snapshot("ATP_EB").state();
        assertEquals(0.0, stopped.getVelocity(), 0.001);
        assertEquals(SimulationPhase.STOPPED, stopped.getPhase());
        assertTrue(bridge.isEmergencyLatched("ATP_EB"));
    }

    @Test
    void syncDepartureAuthUnlocksMotionCommands() {
        SimulationResult result = vehicleService.run(provider.buildScenario(loader.buildLineProfile(1, 2)));
        bridge.registerSimulation("SYNC_DEP", 1, 2, result, DrivingMode.MANUAL, false);
        assertEquals("NOT_READY_TO_DEPART",
                bridge.execute("SYNC_DEP", command("traction", 50)).getRejectionReason());

        bridge.syncDepartureAuth("SYNC_DEP", true);
        assertTrue(bridge.isDepartureAuthorized("SYNC_DEP"));
        assertEquals("EXECUTED", bridge.execute("SYNC_DEP", command("traction", 50)).getStatus());
    }

    // ═══════════════════════════════════════════════════════════════
    // ATO/704 状态冲突修复测试（任务要求 5 项）
    // ═══════════════════════════════════════════════════════════════

    /** 1. Bridge 同步当前状态后执行 EB，断言 EB 起点等于同步位置，而不是初始位置。 */
    @Test
    void syncCurrentStateThenEbStartsAtSyncedPosition() {
        register("SYNC_EB_POS", DrivingMode.MANUAL);
        // 注册后 Bridge.currentState 是初始状态（position ≈ 0）。同步到 500m 处。
        TrainState synced = new TrainState(40.0, 500.0, 15.0, 0.5,
                SimulationPhase.TRACTION, "SYNC_EB_POS");
        synced.setAbsolutePosition(1500.0);
        bridge.syncCurrentState("SYNC_EB_POS", synced, DrivingMode.MANUAL, 1, 2, true);

        Protocol704CommandLifecycle eb = bridge.execute("SYNC_EB_POS", command("emergency_brake", 100));
        assertEquals("EXECUTED", eb.getStatus());
        assertNotNull(eb.getExecutedResult(), "EB executedResult 不应为空");
        assertFalse(eb.getExecutedResult().getStates().isEmpty(), "EB 轨迹不应为空");

        // EB 起点应接近同步位置 500m，而不是初始位置 0
        double ebStart = eb.getExecutedResult().getStates().get(0).getPosition();
        assertTrue(ebStart >= 499.0,
                "EB 起点应接近同步位置 500m，而不是初始位置 0，实际: " + ebStart);
        assertTrue(ebStart < 510.0,
                "EB 起点不应远超同步位置，实际: " + ebStart);
    }

    /** 2. EB 返回的 executed trajectory 位置单调递增（不回退）。 */
    @Test
    void ebExecutedResultPositionsMonotonic() {
        register("EB_MONO", DrivingMode.MANUAL);
        bridge.execute("EB_MONO", command("traction", 50));
        Protocol704CommandLifecycle eb = bridge.execute("EB_MONO", command("emergency_brake", 100));
        assertEquals("EXECUTED", eb.getStatus());
        assertNotNull(eb.getExecutedResult());

        List<TrainState> states = eb.getExecutedResult().getStates();
        assertFalse(states.isEmpty(), "EB 轨迹不应为空");
        for (int i = 1; i < states.size(); i++) {
            assertTrue(states.get(i).getPosition() >= states.get(i - 1).getPosition() - 1e-6,
                    "EB 轨迹位置应单调递增，但 states[" + i + "].position=" + states.get(i).getPosition()
                            + " < states[" + (i - 1) + "].position=" + states.get(i - 1).getPosition());
        }
    }

    /** 3. EB 返回最终状态 velocity=0、phase=STOPPED。 */
    @Test
    void ebExecutedResultFinalStateStopped() {
        register("EB_STOP", DrivingMode.MANUAL);
        bridge.execute("EB_STOP", command("traction", 50));
        Protocol704CommandLifecycle eb = bridge.execute("EB_STOP", command("emergency_brake", 100));
        assertEquals("EXECUTED", eb.getStatus());
        assertNotNull(eb.getExecutedResult());

        List<TrainState> states = eb.getExecutedResult().getStates();
        assertFalse(states.isEmpty(), "EB 轨迹不应为空");
        TrainState finalState = states.get(states.size() - 1);
        assertEquals(0.0, finalState.getVelocity(), 1e-6,
                "EB 最终状态速度应为 0，实际: " + finalState.getVelocity());
        assertEquals(SimulationPhase.STOPPED, finalState.getPhase(),
                "EB 最终状态阶段应为 STOPPED，实际: " + finalState.getPhase());
    }

    /** 4. emergencyLatched 不会自动调用 reset 或清除仿真上下文。 */
    @Test
    void emergencyLatchedNotClearedBySyncState() {
        register("EB_LATCH_SYNC", DrivingMode.MANUAL);
        bridge.execute("EB_LATCH_SYNC", command("emergency_brake", 100));
        assertTrue(bridge.isEmergencyLatched("EB_LATCH_SYNC"));
        assertEquals(DrivingMode.EMERGENCY, bridge.getMode("EB_LATCH_SYNC"));

        // syncCurrentState 不应清除 emergencyLatched 或清除仿真上下文
        TrainState synced = new TrainState(50.0, 300.0, 0.0, 0.0,
                SimulationPhase.STOPPED, "EB_LATCH_SYNC");
        bridge.syncCurrentState("EB_LATCH_SYNC", synced, DrivingMode.EMERGENCY, 1, 2, true);

        assertTrue(bridge.isEmergencyLatched("EB_LATCH_SYNC"),
                "syncCurrentState 不应清除 emergencyLatched");
        assertNotNull(bridge.getMode("EB_LATCH_SYNC"),
                "syncCurrentState 不应清除仿真上下文");
        // 后续命令仍应被 EB 锁存拒绝（上下文未被清除，锁存未被释放）
        assertEquals("EMERGENCY_BRAKE_LATCHED",
                bridge.execute("EB_LATCH_SYNC", command("traction", 50)).getRejectionReason());
    }

    /** 5. 每次 execute 生成唯一 commandId，前端据此去重，避免重复轮询触发回调。 */
    @Test
    void commandIdUniquePerExecuteCall() {
        register("CID_UNIQUE", DrivingMode.MANUAL);
        Protocol704CommandLifecycle first = bridge.execute("CID_UNIQUE", command("traction", 50));
        Protocol704CommandLifecycle second = bridge.execute("CID_UNIQUE", command("coast", 0));
        assertNotNull(first.getCommandId());
        assertNotNull(second.getCommandId());
        assertNotEquals(first.getCommandId(), second.getCommandId(),
                "每次 execute 必须生成唯一 commandId，前端据此去重");
    }

    private void register(String trainId, DrivingMode mode) {
        SimulationResult result = vehicleService.run(provider.buildScenario(loader.buildLineProfile(1, 2)));
        bridge.registerSimulation(trainId, 1, 2, result, mode);
    }

    private static MappedControlCommand command(String command, double level) {
        MappedControlCommand mapped = new MappedControlCommand();
        mapped.setCommand(command);
        mapped.setLevelPercent(level);
        mapped.setTractionLevelRaw((int) level);
        mapped.setBrakeLevelRaw((int) level);
        mapped.setTargetDecel("brake".equals(command) ? 1.2 * level / 100.0 : 0.0);
        mapped.setDirection("FORWARD");
        return mapped;
    }
}
