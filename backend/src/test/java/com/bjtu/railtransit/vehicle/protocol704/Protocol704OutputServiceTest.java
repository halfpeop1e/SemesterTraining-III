package com.bjtu.railtransit.vehicle.protocol704;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.*;

class Protocol704OutputServiceTest {

    @Test
    void sendPlcOutput_success_writesAndUpdatesStats() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RealtimeVehicleState state = new RealtimeVehicleState();
        state.setMode("ATO");
        state.setDoorsClosed(true);
        PortConnectionStatus portStatus = new PortConnectionStatus();
        portStatus.setPort(8001);
        Protocol704Status status = new Protocol704Status();

        byte[] frame = Protocol704OutputService.sendPlcOutput(out, state, portStatus, status);

        assertNotNull(frame);
        assertEquals(26, frame.length);
        assertEquals(26, out.size());
        assertArrayEquals(frame, out.toByteArray());
        assertEquals(1, portStatus.getOutputFrameCount());
        assertTrue(portStatus.getLastOutputTime() > 0);
        assertNotNull(portStatus.getLastOutputHex());
        assertNull(portStatus.getLastOutputError());
        assertEquals(portStatus.getLastOutputHex(), status.getLastOutputFrame());
        assertEquals(0, portStatus.getOutputErrorCount());
    }

    @Test
    void sendPlcOutput_ioException_incrementsErrorAndDoesNotThrow() {
        OutputStream failing = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("simulated write failure");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("simulated write failure");
            }
        };
        RealtimeVehicleState state = new RealtimeVehicleState();
        PortConnectionStatus portStatus = new PortConnectionStatus();
        Protocol704Status status = new Protocol704Status();

        assertDoesNotThrow(() ->
                Protocol704OutputService.sendPlcOutput(failing, state, portStatus, status));

        assertEquals(0, portStatus.getOutputFrameCount());
        assertEquals(1, portStatus.getOutputErrorCount());
        assertNotNull(portStatus.getLastOutputError());
        assertTrue(portStatus.getLastOutputError().contains("simulated write failure"));
    }

    @Test
    void updateHmiPreview_setsFirst64BytesHex() {
        RealtimeVehicleState state = new RealtimeVehicleState();
        state.setVelocityMs(5.0);
        Protocol704Status status = new Protocol704Status();

        Protocol704OutputService.updateHmiPreview(state, status);

        assertNotNull(status.getLastOutputHmi());
        String[] parts = status.getLastOutputHmi().split(" ");
        assertEquals(64, parts.length);
        assertEquals("55", parts[0]);
        assertEquals("AA", parts[1]);
        assertEquals("55", parts[2]);
        assertEquals("AA", parts[3]);
    }

    @Test
    void sendPlcOutput_nullStream_recordsErrorWithoutThrow() {
        RealtimeVehicleState state = new RealtimeVehicleState();
        PortConnectionStatus portStatus = new PortConnectionStatus();
        Protocol704Status status = new Protocol704Status();

        byte[] frame = assertDoesNotThrow(() ->
                Protocol704OutputService.sendPlcOutput(null, state, portStatus, status));

        assertEquals(26, frame.length);
        assertEquals(1, portStatus.getOutputErrorCount());
        assertNotNull(portStatus.getLastOutputError());
    }
}
