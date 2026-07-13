package com.bjtu.railtransit.vehicle.protocol704;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class Protocol704HmiInputIntegrationTest {

    @Test
    void disabledChannelIsReportedInsteadOfWaitingForFirstFrame() {
        Protocol704Service service = newService(-1, false, false);
        try {
            PortConnectionStatus status = hmiPortStatus(service, 6553);
            assertEquals(Protocol704InputState.CHANNEL_DISABLED, status.getInputState());
            assertTrue(status.getInputDiagnostic().contains("disabled"));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void connectedWithoutBytesDiffersFromInvalidFrame() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Protocol704Service service = newService(server.getLocalPort(), true, true);
            try {
                Socket peer = acceptHmi(service, server);
                try (peer) {
                    PortConnectionStatus connected = waitForHmi(service, server.getLocalPort(),
                            value -> value.isConnected() && value.getBytesReceived() == 0);
                    assertEquals(Protocol704InputState.CONNECTED_NO_BYTES, connected.getInputState());

                    peer.getOutputStream().write(new byte[26]);
                    peer.getOutputStream().flush();
                    PortConnectionStatus invalid = waitForHmi(service, server.getLocalPort(),
                            value -> value.getBytesReceived() == 26
                                    && value.getInputState() == Protocol704InputState.HEADER_MISMATCH);
                    assertEquals(Protocol704InputState.HEADER_MISMATCH, invalid.getInputState());
                    assertEquals(0, invalid.getFrameCount());
                }
            } finally {
                service.shutdown();
            }
        }
    }

    @Test
    void halfFrameWaitsAndLegal26ByteFrameIncrementsFrameCount() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Protocol704Service service = newService(server.getLocalPort(), true, true);
            try {
                Socket peer = acceptHmi(service, server);
                try (peer) {
                    byte[] frame = validHmiFrame();
                    OutputStream out = peer.getOutputStream();
                    out.write(frame, 0, 13);
                    out.flush();
                    PortConnectionStatus partial = waitForHmi(service, server.getLocalPort(),
                            value -> value.getBytesReceived() == 13
                                    && value.getInputState() == Protocol704InputState.PARTIAL_FRAME);
                    assertEquals(13, partial.getPendingInputBytes());
                    assertEquals(0, partial.getFrameCount());

                    out.write(frame, 13, frame.length - 13);
                    out.flush();
                    PortConnectionStatus complete = waitForHmi(service, server.getLocalPort(),
                            value -> value.getFrameCount() == 1);
                    assertEquals(Protocol704InputState.FIRST_FRAME_RECEIVED, complete.getInputState());
                    assertEquals(26, complete.getLastFrameLength());
                }
            } finally {
                service.shutdown();
            }
        }
    }

    @Test
    void wrongTotalLengthIsDiagnosedAndNotCounted() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Protocol704Service service = newService(server.getLocalPort(), true, true);
            try {
                Socket peer = acceptHmi(service, server);
                try (peer) {
                    byte[] frame = validHmiFrame();
                    ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).putShort(4, (short) 25);
                    peer.getOutputStream().write(frame);
                    peer.getOutputStream().flush();
                    PortConnectionStatus invalid = waitForHmi(service, server.getLocalPort(),
                            value -> value.getBytesReceived() == 26
                                    && value.getInputState() == Protocol704InputState.TOTAL_LENGTH_MISMATCH);
                    assertEquals(25, invalid.getLastInputTotalLength());
                    assertEquals(0, invalid.getFrameCount());
                }
            } finally {
                service.shutdown();
            }
        }
    }

    @Test
    void wrongDataLengthIsDiagnosedAndNotCounted() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Protocol704Service service = newService(server.getLocalPort(), true, true);
            try {
                Socket peer = acceptHmi(service, server);
                try (peer) {
                    byte[] frame = validHmiFrame();
                    ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).putShort(6, (short) 1);
                    peer.getOutputStream().write(frame);
                    peer.getOutputStream().flush();
                    PortConnectionStatus invalid = waitForHmi(service, server.getLocalPort(),
                            value -> value.getBytesReceived() == 26
                                    && value.getInputState() == Protocol704InputState.DATA_LENGTH_MISMATCH);
                    assertEquals(1, invalid.getLastInputDataLength());
                    assertEquals(0, invalid.getFrameCount());
                }
            } finally {
                service.shutdown();
            }
        }
    }

    @Test
    void plcAndHmiKeepIndependentInputDiagnostics() throws Exception {
        try (ServerSocket plcServer = new ServerSocket(0);
             ServerSocket hmiServer = new ServerSocket(0)) {
            Protocol704Service service = newService(plcServer.getLocalPort(), hmiServer.getLocalPort());
            try {
                plcServer.setSoTimeout(2000);
                hmiServer.setSoTimeout(2000);
                try (Socket plcPeer = plcServer.accept(); Socket hmiPeer = hmiServer.accept()) {
                    waitForPort(service, plcServer.getLocalPort(), PortConnectionStatus::isConnected);
                    waitForPort(service, hmiServer.getLocalPort(), PortConnectionStatus::isConnected);

                    byte[] plcFrame = validPlcFrame();
                    plcPeer.getOutputStream().write(plcFrame, 0, plcFrame.length / 2);
                    plcPeer.getOutputStream().flush();

                    hmiPeer.getOutputStream().write(validHmiFrame());
                    hmiPeer.getOutputStream().flush();

                    PortConnectionStatus plc = waitForPort(service, plcServer.getLocalPort(),
                            value -> value.getInputState() == Protocol704InputState.PARTIAL_FRAME);
                    PortConnectionStatus hmi = waitForPort(service, hmiServer.getLocalPort(),
                            value -> value.getInputState() == Protocol704InputState.FIRST_FRAME_RECEIVED);

                    assertEquals(plcFrame.length / 2, plc.getBytesReceived());
                    assertEquals(0, plc.getFrameCount());
                    assertEquals(Protocol704InputState.PARTIAL_FRAME, plc.getInputState());
                    assertEquals(26, hmi.getBytesReceived());
                    assertEquals(1, hmi.getFrameCount());
                    assertEquals(Protocol704InputState.FIRST_FRAME_RECEIVED, hmi.getInputState());
                }
            } finally {
                service.shutdown();
            }
        }
    }

    private static Protocol704Service newService(int hmiPort, boolean start, boolean hmiInputEnabled) {
        Protocol704Service service = new Protocol704Service();
        ReflectionTestUtils.setField(service, "defaultHost", "127.0.0.1");
        ReflectionTestUtils.setField(service, "portsConfig", "65000");
        ReflectionTestUtils.setField(service, "connectTimeoutMs", 200);
        ReflectionTestUtils.setField(service, "readTimeoutMs", 100);
        ReflectionTestUtils.setField(service, "hmiHost", "127.0.0.1");
        ReflectionTestUtils.setField(service, "hmiPort", hmiPort > 0 ? hmiPort : 6553);
        ReflectionTestUtils.setField(service, "hmiInputEnabled", hmiInputEnabled);
        service.init();
        if (start) service.start("T1");
        return service;
    }

    private static Protocol704Service newService(int plcPort, int hmiPort) {
        Protocol704Service service = new Protocol704Service();
        ReflectionTestUtils.setField(service, "defaultHost", "127.0.0.1");
        ReflectionTestUtils.setField(service, "portsConfig", String.valueOf(plcPort));
        ReflectionTestUtils.setField(service, "connectTimeoutMs", 200);
        ReflectionTestUtils.setField(service, "readTimeoutMs", 100);
        ReflectionTestUtils.setField(service, "hmiHost", "127.0.0.1");
        ReflectionTestUtils.setField(service, "hmiPort", hmiPort);
        ReflectionTestUtils.setField(service, "hmiInputEnabled", true);
        ReflectionTestUtils.setField(service, "mmiHost", "127.0.0.1");
        ReflectionTestUtils.setField(service, "mmiPort", 65001);
        service.init();
        service.start("T1");
        return service;
    }

    private static Socket acceptHmi(Protocol704Service service, ServerSocket server) throws Exception {
        server.setSoTimeout(2000);
        Socket peer = server.accept();
        waitForHmi(service, server.getLocalPort(), PortConnectionStatus::isConnected);
        return peer;
    }

    private static PortConnectionStatus waitForHmi(Protocol704Service service, int hmiPort,
                                                    Predicate<PortConnectionStatus> predicate)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        PortConnectionStatus last = null;
        while (System.currentTimeMillis() < deadline) {
            last = hmiPortStatus(service, hmiPort);
            if (predicate.test(last)) return last;
            Thread.sleep(20);
        }
        fail("timed out waiting for HMI input state, last=" + last);
        return last;
    }

    private static PortConnectionStatus waitForPort(Protocol704Service service, int port,
                                                     Predicate<PortConnectionStatus> predicate)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        PortConnectionStatus last = null;
        while (System.currentTimeMillis() < deadline) {
            last = service.getStatus("T1").getPortStatuses().get(port);
            if (last != null && predicate.test(last)) return last;
            Thread.sleep(20);
        }
        fail("timed out waiting for protocol704 port state, port=" + port + ", last=" + last);
        return last;
    }

    private static PortConnectionStatus hmiPortStatus(Protocol704Service service, int hmiPort) {
        return service.getStatus("T1").getPortStatuses().values().stream()
                .filter(value -> value.getPort() == hmiPort)
                .findFirst()
                .orElseThrow();
    }

    private static byte[] validHmiFrame() {
        byte[] frame = new byte[Protocol704HmiInputParser.FRAME_LENGTH];
        ByteBuffer bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0, 0xAA55AA55);
        bb.putShort(4, (short) 26);
        bb.putShort(6, (short) 2);
        bb.putLong(8, System.currentTimeMillis());
        frame[24] = 0x01;
        return frame;
    }

    private static byte[] validPlcFrame() {
        byte[] frame = new byte[Protocol704FrameAccumulator.FRAME_LENGTH];
        ByteBuffer bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0, 0xAA55AA55);
        bb.putShort(4, (short) Protocol704FrameAccumulator.FRAME_LENGTH);
        bb.putShort(6, (short) 22);
        return frame;
    }
}
