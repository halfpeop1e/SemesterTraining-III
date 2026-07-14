package com.bjtu.railtransit.signal.service;

import com.bjtu.railtransit.dispatch.CommandBus;
import com.bjtu.railtransit.dispatch.MultiParticleSimulationService;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.bjtu.railtransit.signal.domain.SignalEvent;
import com.bjtu.railtransit.domain.model.TrainState;
import com.bjtu.railtransit.signal.service.MaConfig;
import com.bjtu.railtransit.signal.service.MovingAuthorityService;
import com.bjtu.railtransit.signal.service.SignalCycleService;
import com.bjtu.railtransit.signal.service.SignalEventLog;
import com.bjtu.railtransit.signal.service.SignalInterlockingService;
import com.bjtu.railtransit.vehicle.dto.SimulationResult;
import com.bjtu.railtransit.vehicle.enums.DrivingMode;
import com.bjtu.railtransit.vehicle.protocol704.MappedControlCommand;
import com.bjtu.railtransit.vehicle.protocol704.Parsed704Frame;
import com.bjtu.railtransit.vehicle.protocol704.Protocol704VehicleControlBridge;
import com.bjtu.railtransit.vehicle.protocol704.Protocol704FrameParser;
import com.bjtu.railtransit.vehicle.service.DemoScenarioProvider;
import com.bjtu.railtransit.vehicle.service.LineProfileJsonLoader;
import com.bjtu.railtransit.vehicle.service.VehicleSimulationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class SignalPlcDepartureServiceTest {

    private CommandBus commandBus;
    private MovementAuthorityRegistry maRegistry;
    private Protocol704VehicleControlBridge bridge;
    private SignalPlcDepartureService service;
    private VehicleSimulationService vehicleService;
    private DemoScenarioProvider provider;
    private LineProfileJsonLoader loader;

    @BeforeEach
    void setUp() {
        commandBus = new CommandBus();
        maRegistry = new MovementAuthorityRegistry();
        provider = new DemoScenarioProvider();
        loader = new LineProfileJsonLoader();
        vehicleService = new VehicleSimulationService(provider, new MultiParticleSimulationService());
        bridge = new Protocol704VehicleControlBridge(vehicleService, provider, loader);
        service = new SignalPlcDepartureService(commandBus, maRegistry, bridge);
    }

    @Test
    void atoReadyOnlyWhenMaCoversTheNextStationTarget() {
        register("T1", DrivingMode.MANUAL);
        prepareCab("T1");
        bridge.syncDepartureAuth("T1", true);
        putMa("T1", 500);

        assertFalse(service.isAtoReady("T1"), "an EoA before station 2 must not start an ATO leg");

        putMa("T1", 1_660.52);
        assertTrue(service.isAtoReady("T1"));
    }

    @Test
    void atoReadyRequiresThePhysicalKeyPreparation() {
        register("T1", DrivingMode.MANUAL);
        bridge.syncDepartureAuth("T1", true);
        putMa("T1", 1_660.52);

        assertEquals("KEY_SWITCH_OFF", service.atoReadinessBlockingReason("T1"));
        assertFalse(service.isAtoReady("T1"));

        bridge.updateCabInputs("T1", Map.of(
                "key_switch_on", true, "direction_handle", 0, "master_handle", 0));
        assertEquals("KEY_CYCLE_INCOMPLETE", service.atoReadinessBlockingReason("T1"));

        bridge.updateCabInputs("T1", Map.of(
                "key_switch_on", false, "direction_handle", 0, "master_handle", 0));
        bridge.updateCabInputs("T1", Map.of(
                "key_switch_on", true, "direction_handle", 1, "master_handle", 0));
        assertTrue(service.isAtoReady("T1"));
    }

    @Test
    void atoReadyRequiresForwardDirectionAndZeroMasterHandleAfterKeyPreparation() {
        register("T1", DrivingMode.MANUAL);
        bridge.syncDepartureAuth("T1", true);
        putMa("T1", 1_660.52);

        bridge.updateCabInputs("T1", Map.of(
                "key_switch_on", true, "direction_handle", 0, "master_handle", 0));
        bridge.updateCabInputs("T1", Map.of(
                "key_switch_on", false, "direction_handle", 0, "master_handle", 0));
        bridge.updateCabInputs("T1", Map.of(
                "key_switch_on", true, "direction_handle", 0, "master_handle", 0));
        assertEquals("DIRECTION_NOT_FORWARD", service.atoReadinessBlockingReason("T1"));

        bridge.updateCabInputs("T1", Map.of(
                "key_switch_on", true, "direction_handle", 1, "master_handle", 1));
        assertEquals("MASTER_HANDLE_NOT_ZERO", service.atoReadinessBlockingReason("T1"));

        bridge.updateCabInputs("T1", Map.of(
                "key_switch_on", true, "direction_handle", 1, "master_handle", 0));
        assertTrue(service.isAtoReady("T1"));
    }

    @Test
    void workflowNeverReportsAtoReadyWhenTheBridgeAuthorizationOutrunsTheMa() {
        register("T1", DrivingMode.MANUAL);
        prepareCab("T1");
        bridge.syncDepartureAuth("T1", true);
        putMa("T1", 500);

        assertFalse(service.isAtoReady("T1"));
        assertEquals("WAITING_MOVEMENT_AUTHORITY", service.atoWorkflowState("T1"));

        putMa("T1", 1_660.52);
        assertEquals("ATO_READY", service.atoWorkflowState("T1"));
    }

    @Test
    void atoReadyUsesAbsoluteMileageWhenStartingFromAnIntermediateStation() {
        register("T1", 2, 3, DrivingMode.MANUAL);
        bridge.syncDepartureAuth("T1", true);
        // This is behind station 2 (K1+661). Treating the local position 0 as
        // a line position would incorrectly arm the physical ATO buttons.
        putMa("T1", 1_000);

        assertFalse(service.isAtoReady("T1"));
    }

    @Test
    void atoReadyWhenOpenDepartCommandExists() {
        register("T1", DrivingMode.MANUAL);
        commandBus.issue("T1", "DEPART", 0, "ATS", 100, "ATS", 10);
        putMa("T1", 500);

        assertFalse(service.isAtoReady("T1"));
    }

    @Test
    void notAtoReadyWhenDoorsOpen() {
        register("T1", DrivingMode.MANUAL);
        bridge.syncDepartureAuth("T1", true);
        putMa("T1", 500);
        bridge.updateCabInputs("T1", Map.of("door_open_right", true));

        assertFalse(service.isAtoReady("T1"));
    }

    @Test
    void notAtoReadyWhenAlreadyInAtoMode() {
        register("T1", DrivingMode.ATO);
        putMa("T1", 500);

        assertFalse(service.isAtoReady("T1"));
    }

    @Test
    void onDepartureAuthorizedSyncsBridge() {
        register("T1", DrivingMode.MANUAL);

        service.onDepartureAuthorized("T1");

        assertTrue(bridge.isDepartureAuthorized("T1"));
    }

    @Test
    void doorCloseDoesNotCreateAtoReadyWithoutNewSignalAuthorization() {
        register("T1", DrivingMode.MANUAL);
        putMa("T1", 500);
        bridge.updateCabInputs("T1", Map.of("door_open_right", true));
        bridge.updateCabInputs("T1", Map.of("door_open_right", false));
        bridge.updateCabInputs("T1", Map.of("door_close_right", true));
        bridge.updateCabInputs("T1", Map.of("door_close_right", false));

        assertFalse(bridge.isDepartureAuthorized("T1"));
        assertFalse(service.isAtoReady("T1"));
    }

    @Test
    void establishedBoundRouteArmsDeskOnlyThroughSignalCycleAndRevokesOnCancel() throws Exception {
        register("T1", DrivingMode.MANUAL);
        prepareCab("T1");
        SignalInterlockingService interlocking = new SignalInterlockingService(new LineProfileLoader());
        SignalCycleService cycle = new SignalCycleService(
                new MovingAuthorityService(MaConfig.exampleConfig()), maRegistry,
                new LineProfileLoader(), interlocking, new SignalEventLog(), service, true);

        TrainState train = new TrainState();
        train.setTrainId("T1");
        train.setDirection("UP");
        train.setStatus("READY_TO_DEPART");
        train.setPositionMeters(313.0);
        train.setSpeed(0.0);
        train.setTrainLengthMeters(114.0);

        interlocking.buildAndAssignLaboratoryStationLeg("T1", 1, 2, 0.0);
        cycle.runCycle(List.of(train), 0.0);

        assertTrue(bridge.isDepartureAuthorized("T1"));
        assertTrue(service.isAtoReady("T1"));
        assertFalse(bridge.snapshot("T1").atoRunning());

        interlocking.cancelLaboratoryStationLeg("T1", 1.0);
        cycle.runCycle(List.of(train), 1.0);

        assertFalse(bridge.isDepartureAuthorized("T1"));
        assertFalse(service.isAtoReady("T1"));
    }

    @Test
    void laboratoryArrivalReleasesTheOldRouteAndRejectsANewRouteWhoseMaDoesNotReachTheNextStation() throws Exception {
        register("T1", 1, 3, DrivingMode.MANUAL);
        prepareCab("T1");
        SignalInterlockingService interlocking = new SignalInterlockingService(new LineProfileLoader());
        SignalCycleService cycle = new SignalCycleService(
                new MovingAuthorityService(MaConfig.exampleConfig()), maRegistry,
                new LineProfileLoader(), interlocking, new SignalEventLog(), service, true);
        TrainState train = trainAt("T1", 313.0, "READY_TO_DEPART");

        interlocking.buildAndAssignLaboratoryStationLeg("T1", 1, 2, 0.0);
        cycle.runCycle(List.of(train), 0.0);
        assertTrue(service.isAtoReady("T1"));

        MappedControlCommand atoStart = new MappedControlCommand();
        atoStart.setCommand("ATO_START");
        atoStart.setDirection("FORWARD");
        atoStart.setMasterHandle(0);
        assertEquals("EXECUTED", bridge.execute("T1", atoStart).getStatus());
        for (int i = 0; i < 10_000 && bridge.snapshot("T1").atoRunning(); i++) {
            bridge.advanceAtoSessions();
        }
        assertFalse(bridge.snapshot("T1").atoRunning(), "ATO must stop at station 2");
        assertEquals(2, bridge.snapshot("T1").currentStationId());
        assertEquals(3, bridge.snapshot("T1").nextTargetStationId());
        assertEquals(1_660.52, bridge.snapshot("T1").absolutePositionM(), 0.001);

        train.setPositionMeters(1_660.52);
        train.setStatus("READY_TO_DEPART");
        train.setSpeed(0.0);
        cycle.runCycle(List.of(train), 1.0);

        assertNull(interlocking.getRouteRuntimeForTrain("T1"));
        assertFalse(bridge.isDepartureAuthorized("T1"));
        assertFalse(service.isAtoReady("T1"));

        bridge.updateCabInputs("T1", Map.of("key_switch_on", true, "direction_handle", 1,
                "master_handle", 0, "door_open_right", true));
        bridge.updateCabInputs("T1", Map.of("key_switch_on", true, "direction_handle", 1,
                "master_handle", 0, "door_open_right", false));
        bridge.updateCabInputs("T1", Map.of("key_switch_on", true, "direction_handle", 1,
                "master_handle", 0, "door_close_right", true));
        bridge.updateCabInputs("T1", Map.of("key_switch_on", true, "direction_handle", 1,
                "master_handle", 0, "door_close_right", false));
        cycle.runCycle(List.of(train), 2.0);
        assertFalse(service.isAtoReady("T1"), "closing doors must not revive the released route");

        interlocking.buildAndAssignLaboratoryStationLeg("T1", 2, 3, 3.0);
        cycle.runCycle(List.of(train), 3.0);
        assertTrue(service.isAtoReady("T1"), "only a new topology-verified station leg may re-arm ATO");
    }

    @Test
    void physicalPlcWorkflowCompletesTwoStationLegsWithDoorCycleAndFreshAtoPresses() throws Exception {
        register("T1", 1, 3, DrivingMode.MANUAL);
        SignalInterlockingService interlocking = new SignalInterlockingService(new LineProfileLoader());
        SignalCycleService cycle = new SignalCycleService(
                new MovingAuthorityService(MaConfig.exampleConfig()), maRegistry,
                new LineProfileLoader(), interlocking, new SignalEventLog(), service, true, true);
        TrainState runtime = trainAt("T1", 313.0, "READY_TO_DEPART");

        // The cab must report neutral handles while the key completes
        // open -> closed -> open, then select forward. None of these cyclic
        // coast frames can create signal authorization by themselves.
        assertEquals("NOT_READY_TO_DEPART", receivePlc("T1", plcFrame(true, false,
                false, false, false, false, 0, 0)).getRejectionReason());
        assertEquals("NOT_READY_TO_DEPART", receivePlc("T1", plcFrame(false, false,
                false, false, false, false, 0, 0)).getRejectionReason());
        assertEquals("NOT_READY_TO_DEPART", receivePlc("T1", plcFrame(true, false,
                false, false, false, false, 1, 0)).getRejectionReason());

        cycle.runCycle(List.of(runtime), 0.0);
        assertEquals(List.of(9, 28), interlocking.getBuiltRoutesForTrain("T1").stream()
                .map(route -> route.getId()).toList());
        assertEquals(1_660.520, maRegistry.get("T1").getEndOfAuthorityM(), 0.001);
        assertTrue(service.isAtoReady("T1"));

        // byte34 bit7 is emitted only after the desk has interlocked the two
        // green buttons. The bridge accepts it as one fresh physical edge.
        assertEquals("EXECUTED", receivePlc("T1", plcFrame(true, true,
                false, false, false, false)).getStatus());
        assertTrue(bridge.snapshot("T1").atoRunning());
        assertEquals("EXECUTED", receivePlc("T1", plcFrame(true, false,
                false, false, false, false)).getStatus());
        advanceAtoToStation("T1");

        assertEquals(2, bridge.snapshot("T1").currentStationId());
        assertEquals(3, bridge.snapshot("T1").nextTargetStationId());
        assertEquals(1_660.520, bridge.snapshot("T1").absolutePositionM(), 0.001);

        runtime = trainAt("T1", 1_660.520, "READY_TO_DEPART");
        cycle.runCycle(List.of(runtime), 1.0);
        assertNull(interlocking.getRouteRuntimeForTrain("T1"),
                "arrival releases the complete old CBI route chain");
        assertFalse(service.isAtoReady("T1"));

        // The door buttons come through the same 46 B PLC cyclic image. A
        // completed close does not restore the released authority by itself.
        receivePlc("T1", plcFrame(true, false, true, false, false, false));
        receivePlc("T1", plcFrame(true, false, false, false, false, false));
        assertFalse(bridge.snapshot("T1").doorsClosed());
        receivePlc("T1", plcFrame(true, false, false, false, true, false));
        receivePlc("T1", plcFrame(true, false, false, false, false, false));
        assertTrue(bridge.snapshot("T1").doorsClosed());
        assertFalse(service.isAtoReady("T1"));

        cycle.runCycle(List.of(runtime), 2.0);
        assertEquals(List.of(29), interlocking.getBuiltRoutesForTrain("T1").stream()
                .map(route -> route.getId()).toList());
        assertEquals(2_448.610, maRegistry.get("T1").getEndOfAuthorityM(), 0.001);
        assertTrue(service.isAtoReady("T1"));

        // The release frame above re-arms edge detection. The second ATO press
        // is therefore deliberate and starts only after the second MA exists.
        assertEquals("EXECUTED", receivePlc("T1", plcFrame(true, true,
                false, false, false, false)).getStatus());
        assertTrue(bridge.snapshot("T1").atoRunning());
    }

    @Test
    void automaticallyBuildsInitialAndNextStationLegAfterDoorCycle() throws Exception {
        register("T1", 1, 3, DrivingMode.MANUAL);
        prepareCab("T1");
        SignalInterlockingService interlocking = new SignalInterlockingService(new LineProfileLoader());
        SignalCycleService cycle = new SignalCycleService(
                new MovingAuthorityService(MaConfig.exampleConfig()), maRegistry,
                new LineProfileLoader(), interlocking, new SignalEventLog(), service, true, true);
        TrainState runtime = trainAt("T1", 313.0, "READY_TO_DEPART");

        cycle.runCycle(List.of(runtime), 0.0);

        assertEquals(List.of(9, 28), interlocking.getBuiltRoutesForTrain("T1").stream()
                .map(route -> route.getId()).toList());
        assertTrue(service.isAtoReady("T1"), "the initial station leg should be automatic");

        MappedControlCommand atoStart = new MappedControlCommand();
        atoStart.setCommand("ATO_START");
        atoStart.setDirection("FORWARD");
        atoStart.setMasterHandle(0);
        assertEquals("EXECUTED", bridge.execute("T1", atoStart).getStatus());
        advanceAtoToStation("T1");

        runtime = trainAt("T1", 1_660.520, "READY_TO_DEPART");
        cycle.runCycle(List.of(runtime), 1.0);
        assertNull(interlocking.getRouteRuntimeForTrain("T1"));

        bridge.updateCabInputs("T1", Map.of("key_switch_on", true, "direction_handle", 1,
                "master_handle", 0, "door_open_right", true));
        bridge.updateCabInputs("T1", Map.of("key_switch_on", true, "direction_handle", 1,
                "master_handle", 0, "door_open_right", false));
        bridge.updateCabInputs("T1", Map.of("key_switch_on", true, "direction_handle", 1,
                "master_handle", 0, "door_close_right", true));
        bridge.updateCabInputs("T1", Map.of("key_switch_on", true, "direction_handle", 1,
                "master_handle", 0, "door_close_right", false));
        cycle.runCycle(List.of(runtime), 2.0);

        assertEquals(List.of(29), interlocking.getBuiltRoutesForTrain("T1").stream()
                .map(route -> route.getId()).toList());
        assertTrue(service.isAtoReady("T1"), "closing the doors should automatically arm the next leg");
    }

    private void register(String trainId, DrivingMode mode) {
        register(trainId, 1, 2, mode);
    }

    private void register(String trainId, int fromStationId, int toStationId, DrivingMode mode) {
        SimulationResult result = vehicleService.run(provider.buildScenario(
                loader.buildLineProfile(fromStationId, toStationId)));
        result.getStates().get(0).setAbsolutePosition(null);
        bridge.registerSimulation(trainId, fromStationId, toStationId, result, mode, false, false);
    }

    private void putMa(String trainId, double endM) {
        MovingAuthority ma = new MovingAuthority();
        ma.setTrainId(trainId);
        ma.setEndOfAuthorityM(endM);
        ma.setMaxSpeedKmh(65);
        ma.setEvent(SignalEvent.NONE);
        Map<String, MovingAuthority> values = new LinkedHashMap<>();
        values.put(trainId, ma);
        maRegistry.replace(values, 1.0, "TEST");
    }

    private static TrainState trainAt(String trainId, double positionM, String status) {
        TrainState train = new TrainState();
        train.setTrainId(trainId);
        train.setDirection("UP");
        train.setStatus(status);
        train.setPositionMeters(positionM);
        train.setSpeed(0.0);
        train.setTrainLengthMeters(114.0);
        return train;
    }

    private void prepareCab(String trainId) {
        bridge.updateCabInputs(trainId, Map.of(
                "key_switch_on", true, "direction_handle", 0, "master_handle", 0));
        bridge.updateCabInputs(trainId, Map.of(
                "key_switch_on", false, "direction_handle", 0, "master_handle", 0));
        bridge.updateCabInputs(trainId, Map.of(
                "key_switch_on", true, "direction_handle", 1, "master_handle", 0));
    }

    private com.bjtu.railtransit.vehicle.protocol704.Protocol704CommandLifecycle receivePlc(
            String trainId, byte[] frame) {
        Parsed704Frame parsed = Protocol704FrameParser.parseFrame(frame);
        bridge.updateCabInputs(trainId, parsed.getFields());
        return bridge.execute(trainId, parsed.getMappedCommand(),
                Protocol704VehicleControlBridge.SOURCE_PLC, "desk-8001", 8001);
    }

    private void advanceAtoToStation(String trainId) {
        for (int i = 0; i < 10_000 && bridge.snapshot(trainId).atoRunning(); i++) {
            bridge.advanceAtoSessions();
        }
        assertFalse(bridge.snapshot(trainId).atoRunning(), "ATO must reach the selected next station");
    }

    private static byte[] plcFrame(boolean keyOn, boolean atoStart,
                                   boolean openRight, boolean openLeft,
                                   boolean closeRight, boolean closeLeft) {
        return plcFrame(keyOn, atoStart, openRight, openLeft, closeRight, closeLeft, 1, 0);
    }

    private static byte[] plcFrame(boolean keyOn, boolean atoStart,
                                   boolean openRight, boolean openLeft,
                                   boolean closeRight, boolean closeLeft,
                                   int directionHandle, int masterHandle) {
        byte[] frame = new byte[46];
        ByteBuffer bytes = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(0, 0xAA55AA55);
        bytes.putShort(4, (short) 46);
        bytes.putShort(6, (short) 22);
        bytes.putShort(8, (short) 2026);
        bytes.putShort(10, (short) 7);
        bytes.putShort(12, (short) 14);
        frame[24] = 0x20;
        frame[29] = (byte) ((openLeft ? 0x01 : 0)
                | (openRight ? 0x02 : 0)
                | (closeLeft ? 0x04 : 0)
                | (closeRight ? 0x08 : 0));
        frame[34] = (byte) (atoStart ? 0x80 : 0);
        frame[35] = (byte) (keyOn ? 0x02 : 0);
        bytes.putShort(36, (short) directionHandle);
        bytes.putShort(38, (short) masterHandle);
        return frame;
    }
}
