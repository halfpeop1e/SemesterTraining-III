package com.bjtu.railtransit.vehicle.protocol704;

import com.bjtu.railtransit.dispatch.CommandBus;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.bjtu.railtransit.signal.domain.SignalEvent;
import com.bjtu.railtransit.signal.service.MovementAuthorityRegistry;
import com.bjtu.railtransit.signal.service.SignalPlcDepartureService;
import com.bjtu.railtransit.vehicle.dto.SimulationResult;
import com.bjtu.railtransit.vehicle.dto.TrainState;
import com.bjtu.railtransit.vehicle.enums.DrivingMode;
import com.bjtu.railtransit.vehicle.enums.SimulationPhase;
import com.bjtu.railtransit.vehicle.service.DemoScenarioProvider;
import com.bjtu.railtransit.vehicle.service.LineProfileJsonLoader;
import com.bjtu.railtransit.vehicle.service.VehicleSimulationService;
import com.bjtu.railtransit.dispatch.MultiParticleSimulationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

class Protocol704ServiceBridgeTest {
    private Protocol704Service service;
    private Protocol704VehicleControlBridge bridge;
    private VehicleSimulationService vehicleService;
    private DemoScenarioProvider provider;
    private LineProfileJsonLoader loader;
    private MovementAuthorityRegistry maRegistry;

    @BeforeEach
    void setUp() {
        provider = new DemoScenarioProvider();
        loader = new LineProfileJsonLoader();
        vehicleService = new VehicleSimulationService(provider, new MultiParticleSimulationService());
        bridge = new Protocol704VehicleControlBridge(vehicleService, provider, loader);
        maRegistry = new MovementAuthorityRegistry();
        service = new Protocol704Service(bridge,
                new SignalPlcDepartureService(new CommandBus(), maRegistry, bridge));
        ReflectionTestUtils.setField(service, "defaultHost", "127.0.0.1");
        ReflectionTestUtils.setField(service, "portsConfig", "8001");
        ReflectionTestUtils.setField(service, "autoStart", false);
        service.init();
    }

    @AfterEach
    void tearDown() { service.shutdown(); }

    @Test
    void localTestFrameTraversesParserServiceBridgeAndVehicleSimulation() {
        SimulationResult result = vehicleService.run(provider.buildScenario(loader.buildLineProfile(1, 2)));
        bridge.registerSimulation("OB1", 1, 2, result, DrivingMode.MANUAL);

        Protocol704Status status = service.injectTestFrame("OB1", "traction");

        assertTrue(status.isReceivedValidFrame());
        assertNotNull(status.getLastCommandLifecycle());
        assertEquals("EXECUTED", status.getLastCommandLifecycle().getStatus());
        assertTrue(status.getLastCommandLifecycle().getExecutedState().getAcceleration() > 0.0);
        assertTrue(status.getRealtimeVehicleState().getVelocityMs() > 0.0);
    }

    @Test
    void heldTractionFramesRemainExecutedInsteadOfBeingRejectedAsDuplicates() {
        SimulationResult result = vehicleService.run(provider.buildScenario(loader.buildLineProfile(1, 2)));
        bridge.registerSimulation("HELD1", 1, 2, result, DrivingMode.MANUAL);

        Protocol704Status first = service.injectTestFrame("HELD1", "traction");
        Protocol704Status second = service.injectTestFrame("HELD1", "traction");

        assertEquals("EXECUTED", first.getLastCommandLifecycle().getStatus());
        assertEquals("EXECUTED", second.getLastCommandLifecycle().getStatus());
        assertTrue(second.getRealtimeVehicleState().getVelocityMs() > 0.0);
    }

    @Test
    void heldTractionNearNextStationIsSupervisedIntoServiceBrake() {
        SimulationResult result = vehicleService.run(provider.buildScenario(loader.buildLineProfile(1, 2)));
        bridge.registerSimulation("STOP1", 1, 2, result, DrivingMode.MANUAL);

        TrainState nearStation = new TrainState(20.0, 1300.0, 12.0, 0.0,
                SimulationPhase.TRACTION, "STOP1");
        com.bjtu.railtransit.vehicle.dto.SimulationControlRequest control =
                new com.bjtu.railtransit.vehicle.dto.SimulationControlRequest();
        control.setFromStationId(1);
        control.setToStationId(2);
        control.setCurrentMode(DrivingMode.MANUAL);
        control.setCurrentState(nearStation);
        bridge.recordWebControl("STOP1", control, result);

        Protocol704Status status = service.injectTestFrame("STOP1", "traction");

        assertEquals("EXECUTED", status.getLastCommandLifecycle().getStatus());
        assertTrue(status.getLastCommandLifecycle().getExecutedState().getAcceleration() < 0.0,
                "held traction must be overridden by approach protection near the next station");
    }

    @Test
    void protectedApproachStopsAtStationAndRequiresAnotherDepartureConfirmation() throws Exception {
        SimulationResult result = vehicleService.run(provider.buildScenario(loader.buildLineProfile(1, 2)));
        bridge.registerSimulation("ARRIVE1", 1, 2, result, DrivingMode.MANUAL);

        TrainState nearStation = new TrainState(30.0, 1332.0, 4.0, 0.0,
                SimulationPhase.TRACTION, "ARRIVE1");
        com.bjtu.railtransit.vehicle.dto.SimulationControlRequest control =
                new com.bjtu.railtransit.vehicle.dto.SimulationControlRequest();
        control.setFromStationId(1);
        control.setToStationId(2);
        control.setCurrentMode(DrivingMode.MANUAL);
        control.setCurrentState(nearStation);
        bridge.recordWebControl("ARRIVE1", control, result);

        Protocol704Status status = null;
        for (int i = 0; i < 20; i++) {
            status = service.injectTestFrame("ARRIVE1", "traction");
            if ("READY_TO_DEPART".equals(status.getLastCommandLifecycle().getDepartureState())) break;
            Thread.sleep(460);
        }

        assertNotNull(status);
        assertEquals("TERMINAL_DWELL", status.getLastCommandLifecycle().getDepartureState());
        assertTrue(status.getRealtimeVehicleState().getVelocityMs() <= 0.05);
    }

    @Test
    void noBoundSimulationIsVisibleAsRejectedRatherThanExecuted() {
        Protocol704Status status = service.injectTestFrame("OB1", "traction");
        assertTrue(!status.isSimulationReady());
        assertEquals("NO_ACTIVE_SIMULATION", status.getSimulationReadiness());
        assertEquals("REJECTED", status.getLastCommandLifecycle().getStatus());
        assertEquals("NO_ACTIVE_SIMULATION", status.getLastCommandLifecycle().getRejectionReason());
    }

    @Test
    void statusShowsWhenTheSameTrainIdHasBeenPreparedForPlcControl() {
        SimulationResult result = vehicleService.run(provider.buildScenario(loader.buildLineProfile(1, 2)));
        bridge.registerSimulation("READY1", 1, 2, result, DrivingMode.ATO, false);

        Protocol704Status status = service.getStatus("READY1");

        assertTrue(status.isSimulationReady());
        assertEquals("READY", status.getSimulationReadiness());
    }

    @Test
    void statusDoesNotAdvertiseAtoReadyWhenSignalIsAuthorizedButKeyIsOff() {
        authorizeAtoSummary("KEY_OFF");

        Protocol704Status status = service.getStatus("KEY_OFF");

        assertFalse(status.getRealtimeVehicleState().isAtoReady());
        assertNotEquals("ATO_READY", status.getRealtimeVehicleState().getDepartureState());
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleInputWatchdogStartsTheScheduledAtpBrakeTrajectory() {
        SimulationResult result = vehicleService.run(provider.buildScenario(loader.buildLineProfile(1, 2)));
        bridge.registerSimulation("STALE_WATCHDOG", 1, 2, result, DrivingMode.ATO, true);
        TrainState moving = new TrainState(40.0, 500.0, 12.0, 0.0,
                SimulationPhase.TRACTION, "STALE_WATCHDOG");
        moving.setAbsolutePosition(931.0);
        bridge.syncCurrentState("STALE_WATCHDOG", moving, DrivingMode.ATO, 1, 2, true);

        Protocol704Status status = service.getStatus("STALE_WATCHDOG");
        status.setReceivedValidFrame(true);
        status.setLastValidFrameTime(System.currentTimeMillis() - 1000);
        ReflectionTestUtils.setField(service, "staleInputMs", 1L);
        Map<String, AtomicBoolean> running = (ConcurrentHashMap<String, AtomicBoolean>)
                ReflectionTestUtils.getField(service, "runningFlags");
        assertNotNull(running);
        running.put("STALE_WATCHDOG", new AtomicBoolean(true));

        ReflectionTestUtils.invokeMethod(service, "checkStaleInputs");

        assertTrue(bridge.isEmergencyLatched("STALE_WATCHDOG"));
        assertEquals("EMERGENCY", bridge.snapshot("STALE_WATCHDOG").mode());
        assertTrue(bridge.snapshot("STALE_WATCHDOG").state().getVelocity() > 0.0,
                "the watchdog must schedule a physical brake curve instead of teleporting to a stop");
        assertNotNull(status.getLastCommandLifecycle());
        assertEquals(Protocol704VehicleControlBridge.SOURCE_EMERGENCY,
                status.getLastCommandLifecycle().getSource());

        double before = bridge.snapshot("STALE_WATCHDOG").state().getPosition();
        bridge.advanceAtoSessions();
        assertTrue(bridge.snapshot("STALE_WATCHDOG").state().getPosition() >= before);
    }

    @Test
    void statusAdvertisesAtoReadyOnlyAfterKeyIsOnAndMaReachesNextPlatform() {
        authorizeAtoSummary("KEY_ON");
        bridge.updateCabInputs("KEY_ON", Map.of(
                "key_switch_on", true, "direction_handle", 0, "master_handle", 0));
        bridge.updateCabInputs("KEY_ON", Map.of(
                "key_switch_on", false, "direction_handle", 0, "master_handle", 0));
        bridge.updateCabInputs("KEY_ON", Map.of(
                "key_switch_on", true, "direction_handle", 1, "master_handle", 0));

        Protocol704Status status = service.getStatus("KEY_ON");

        assertTrue(status.getRealtimeVehicleState().isAtoReady());
        assertEquals("ATO_READY", status.getRealtimeVehicleState().getDepartureState());
    }

    @Test
    void physicalAtoStartIsRejectedWhenItsCachedBridgeAuthorizationHasNoCurrentMa() {
        SimulationResult result = vehicleService.run(provider.buildScenario(loader.buildLineProfile(1, 2)));
        bridge.registerSimulation("STALE_MA", 1, 2, result, DrivingMode.MANUAL, false, false);
        // Simulate a route that was withdrawn after the previous signal cycle:
        // the bridge still has an old bit, but the authoritative MA registry
        // does not cover station 2.
        bridge.updateCabInputs("STALE_MA", Map.of(
                "key_switch_on", true, "direction_handle", 0, "master_handle", 0));
        bridge.updateCabInputs("STALE_MA", Map.of(
                "key_switch_on", false, "direction_handle", 0, "master_handle", 0));
        bridge.updateCabInputs("STALE_MA", Map.of(
                "key_switch_on", true, "direction_handle", 1, "master_handle", 0));
        bridge.syncDepartureAuth("STALE_MA", true);
        Protocol704Status status = service.getStatus("STALE_MA");

        ReflectionTestUtils.invokeMethod(service, "processCompleteFrame",
                "STALE_MA", status, 8001, atoStartFrame(),
                Protocol704VehicleControlBridge.SOURCE_PLC, "PLC", "desk-8001", "unit test");

        assertEquals("REJECTED", status.getLastCommandLifecycle().getStatus());
        assertEquals("SIGNAL_DEPARTURE_NOT_AUTHORIZED",
                status.getLastCommandLifecycle().getRejectionReason());
        assertFalse(bridge.snapshot("STALE_MA").atoRunning());
        assertFalse(bridge.snapshot("STALE_MA").departureAuthorized());
    }

    @Test
    void hardwareTcpFrameTraversesAccumulatorParserAdapterBridgeAndExecutedState() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) { port = probe.getLocalPort(); }
        DemoScenarioProvider p = new DemoScenarioProvider();
        LineProfileJsonLoader l = new LineProfileJsonLoader();
        VehicleSimulationService vs = new VehicleSimulationService(p, new MultiParticleSimulationService());
        Protocol704VehicleControlBridge b = new Protocol704VehicleControlBridge(vs, p, l);
        SimulationResult result = vs.run(p.buildScenario(l.buildLineProfile(1, 2)));
        b.registerSimulation("TCP1", 1, 2, result, DrivingMode.MANUAL);
        Protocol704Service wired = new Protocol704Service(b, null);
        ReflectionTestUtils.setField(wired, "defaultHost", "127.0.0.1");
        ReflectionTestUtils.setField(wired, "portsConfig", String.valueOf(port));
        ReflectionTestUtils.setField(wired, "ports", new ArrayList<>());
        ReflectionTestUtils.setField(wired, "autoStart", false);
        wired.init();
        try (ServerSocket server = new ServerSocket(port)) {
            Thread sender = new Thread(() -> {
                try (java.net.Socket socket = server.accept()) {
                    socket.getOutputStream().write(frame());
                    socket.getOutputStream().flush();
                    Thread.sleep(250);
                } catch (Exception ignored) { }
            });
            sender.start();
            wired.start("TCP1");
            long deadline = System.currentTimeMillis() + 4000;
            Protocol704Status status;
            do {
                status = wired.getStatus("TCP1");
                if (status.getLastCommandLifecycle() != null) break;
                Thread.sleep(50);
            } while (System.currentTimeMillis() < deadline);
            assertNotNull(status.getLastParsedFrame());
            assertEquals("EXECUTED", status.getLastCommandLifecycle().getStatus());
            assertTrue(status.getLastCommandLifecycle().getExecutedState().getVelocity() > 0.0);
            sender.join(1000);
        } finally {
            wired.shutdown();
        }
    }

    @Test
    void outboundFrameUsesTheSamePlcSocketOwnedByTheReader() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) { port = probe.getLocalPort(); }
        Protocol704Service wired = wiredService(port, "OUT1");
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        try (ServerSocket server = new ServerSocket(port)) {
            Thread device = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.getOutputStream().write(frame());
                    socket.getOutputStream().flush();
                    byte[] buf = new byte[26];
                    int offset = 0;
                    while (offset < buf.length) {
                        int count = socket.getInputStream().read(buf, offset, buf.length - offset);
                        if (count < 0) break;
                        offset += count;
                    }
                    received.write(buf, 0, offset);
                } catch (Exception ignored) { }
            });
            device.start();
            wired.start("OUT1");
            long deadline = System.currentTimeMillis() + 3000;
            while (!wired.getStatus("OUT1").isConnected() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            byte[] outbound = new byte[26];
            ByteBuffer.wrap(outbound).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(0, 0xAA55AA55).putShort(4, (short) 26).putShort(6, (short) 2);
            assertEquals(1, wired.writeOutbound("OUT1", outbound));
            device.join(2000);
            assertEquals(26, received.size());
            assertEquals(26, wired.getStatus("OUT1").getPortStatuses().get(port).getBytesSent());
        } finally {
            wired.shutdown();
        }
    }

    private Protocol704Service wiredService(int port, String trainId) {
        DemoScenarioProvider p = new DemoScenarioProvider();
        LineProfileJsonLoader l = new LineProfileJsonLoader();
        VehicleSimulationService vs = new VehicleSimulationService(p, new MultiParticleSimulationService());
        Protocol704VehicleControlBridge b = new Protocol704VehicleControlBridge(vs, p, l);
        SimulationResult result = vs.run(p.buildScenario(l.buildLineProfile(1, 2)));
        b.registerSimulation(trainId, 1, 2, result, DrivingMode.MANUAL);
        Protocol704Service wired = new Protocol704Service(b, null);
        ReflectionTestUtils.setField(wired, "defaultHost", "127.0.0.1");
        ReflectionTestUtils.setField(wired, "portsConfig", String.valueOf(port));
        ReflectionTestUtils.setField(wired, "ports", new ArrayList<>());
        ReflectionTestUtils.setField(wired, "autoStart", false);
        wired.init();
        return wired;
    }

    private void authorizeAtoSummary(String trainId) {
        SimulationResult result = vehicleService.run(provider.buildScenario(loader.buildLineProfile(1, 2)));
        bridge.registerSimulation(trainId, 1, 2, result, DrivingMode.MANUAL, false, false);
        bridge.syncDepartureAuth(trainId, true);

        MovingAuthority ma = new MovingAuthority();
        ma.setTrainId(trainId);
        ma.setEndOfAuthorityM(1_778.52);
        ma.setMaxSpeedKmh(65);
        ma.setEvent(SignalEvent.NONE);
        Map<String, MovingAuthority> authorities = new LinkedHashMap<>();
        authorities.put(trainId, ma);
        maRegistry.replace(authorities, 1.0, "TEST");
    }

    private static byte[] frame() {
        byte[] frame = new byte[46];
        ByteBuffer bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0, 0xAA55AA55); bb.putShort(4, (short) 46); bb.putShort(6, (short) 22);
        bb.putShort(8, (short) 2026); bb.putShort(10, (short) 7); bb.putShort(12, (short) 12);
        frame[24] = 0x20; bb.putShort(36, (short) 1); bb.putShort(38, (short) 1);
        bb.putShort(40, (short) 50); return frame;
    }

    private static byte[] atoStartFrame() {
        byte[] frame = frame();
        frame[34] = (byte) 0x80;
        frame[35] = 0x02;
        ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).putShort(38, (short) 0);
        return frame;
    }
}
