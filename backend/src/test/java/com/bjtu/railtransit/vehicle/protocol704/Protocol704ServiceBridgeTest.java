package com.bjtu.railtransit.vehicle.protocol704;

import com.bjtu.railtransit.dispatch.MultiParticleSimulationService;
import com.bjtu.railtransit.vehicle.dto.SimulationResult;
import com.bjtu.railtransit.vehicle.enums.DrivingMode;
import com.bjtu.railtransit.vehicle.service.DemoScenarioProvider;
import com.bjtu.railtransit.vehicle.service.LineProfileJsonLoader;
import com.bjtu.railtransit.vehicle.service.VehicleSimulationService;
import com.bjtu.railtransit.dispatch.MultiParticleSimulationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.ArrayList;

class Protocol704ServiceBridgeTest {
    private Protocol704Service service;
    private Protocol704VehicleControlBridge bridge;
    private VehicleSimulationService vehicleService;
    private DemoScenarioProvider provider;
    private LineProfileJsonLoader loader;

    @BeforeEach
    void setUp() {
        provider = new DemoScenarioProvider();
        loader = new LineProfileJsonLoader();
        vehicleService = new VehicleSimulationService(provider, new MultiParticleSimulationService());
        bridge = new Protocol704VehicleControlBridge(vehicleService, provider, loader);
        service = new Protocol704Service(bridge);
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
    void hardwareTcpFrameTraversesAccumulatorParserAdapterBridgeAndExecutedState() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) { port = probe.getLocalPort(); }
        DemoScenarioProvider p = new DemoScenarioProvider();
        LineProfileJsonLoader l = new LineProfileJsonLoader();
        VehicleSimulationService vs = new VehicleSimulationService(p, new MultiParticleSimulationService());
        Protocol704VehicleControlBridge b = new Protocol704VehicleControlBridge(vs, p, l);
        SimulationResult result = vs.run(p.buildScenario(l.buildLineProfile(1, 2)));
        b.registerSimulation("TCP1", 1, 2, result, DrivingMode.MANUAL);
        Protocol704Service wired = new Protocol704Service(b);
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
        Protocol704Service wired = new Protocol704Service(b);
        ReflectionTestUtils.setField(wired, "defaultHost", "127.0.0.1");
        ReflectionTestUtils.setField(wired, "portsConfig", String.valueOf(port));
        ReflectionTestUtils.setField(wired, "ports", new ArrayList<>());
        ReflectionTestUtils.setField(wired, "autoStart", false);
        wired.init();
        return wired;
    }

    private static byte[] frame() {
        byte[] frame = new byte[46];
        ByteBuffer bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0, 0xAA55AA55); bb.putShort(4, (short) 46); bb.putShort(6, (short) 22);
        bb.putShort(8, (short) 2026); bb.putShort(10, (short) 7); bb.putShort(12, (short) 12);
        frame[24] = 0x20; bb.putShort(36, (short) 1); bb.putShort(38, (short) 1);
        bb.putShort(40, (short) 50); return frame;
    }
}
