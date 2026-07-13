package com.bjtu.railtransit.vehicle.protocol704;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

public class Protocol704ServiceTest {

    private Protocol704Service service;

    @BeforeEach
    public void setUp() {
        service = new Protocol704Service();
        // set defaults (without Spring container)
        ReflectionTestUtils.setField(service, "defaultHost", "192.168.100.123");
        ReflectionTestUtils.setField(service, "portsConfig", "8001,8002,8003");
        ReflectionTestUtils.setField(service, "connectTimeoutMs", 3000);
        ReflectionTestUtils.setField(service, "readTimeoutMs", 1000);
        ReflectionTestUtils.setField(service, "autoStart", false);
        service.init();
    }

    @Test
    public void testInjectTestFrameCoast() {
        Protocol704Status status = service.injectTestFrame("T1", "coast");
        assertNotNull(status);
        assertEquals("T1", status.getTrainId());
        assertNotNull(status.getLastParsedFrame());
        assertNotNull(status.getLastMappedCommand());
        assertEquals("coast", status.getLastMappedCommand().getCommand());
        assertEquals(0, status.getLastMappedCommand().getLevelPercent(), 0.001);
        assertEquals(0, status.getLastMappedCommand().getTargetDecel(), 0.001);
        assertNotNull(status.getLastRawHex());
        assertFalse(status.getLastRawHex().isEmpty());
        // verify ports
        assertNotNull(status.getPorts());
        assertFalse(status.getPorts().isEmpty());
        // verify recentLogs
        assertNotNull(status.getRecentLogs());
        assertFalse(status.getRecentLogs().isEmpty());
        Protocol704LogEntry lastLog = status.getRecentLogs().get(status.getRecentLogs().size() - 1);
        assertEquals("TEST_FRAME", lastLog.getSource());
        assertTrue(lastLog.getNote().contains("test frame"));
    }

    @Test
    public void testInjectTestFrameTraction() {
        Protocol704Status status = service.injectTestFrame("T1", "traction");
        assertNotNull(status.getLastMappedCommand());
        assertEquals("traction", status.getLastMappedCommand().getCommand());
        assertTrue(status.getLastMappedCommand().getLevelPercent() > 0);
        assertEquals(0, status.getLastMappedCommand().getTargetDecel(), 0.001);
    }

    @Test
    public void testInjectTestFrameBrake() {
        Protocol704Status status = service.injectTestFrame("T1", "brake");
        assertNotNull(status.getLastMappedCommand());
        assertEquals("brake", status.getLastMappedCommand().getCommand());
        assertTrue(status.getLastMappedCommand().getTargetDecel() > 0);
    }

    @Test
    public void testInjectTestFrameEmergencyBrake() {
        Protocol704Status status = service.injectTestFrame("T1", "emergency_brake");
        assertNotNull(status.getLastMappedCommand());
        assertEquals("emergency_brake", status.getLastMappedCommand().getCommand());
        assertEquals(100, status.getLastMappedCommand().getLevelPercent(), 0.001);
    }

    @Test
    public void testStatusStructure() {
        Protocol704Status status = service.getStatus("T1");
        assertNotNull(status);
        assertEquals("T1", status.getTrainId());
        assertEquals("192.168.100.123", status.getHost());
        assertNotNull(status.getPorts());
        assertFalse(status.getPorts().isEmpty());
        assertNotNull(status.getPortStatuses());
        assertFalse(status.getPortStatuses().isEmpty());
        assertNotNull(status.getRecentLogs());
        assertNotNull(status.getRealtimeVehicleState());
    }

    @Test
    public void testTestFrameSourceAnnotation() {
        Protocol704Status status = service.injectTestFrame("T1", "coast");
        Protocol704LogEntry lastLog = status.getRecentLogs().get(status.getRecentLogs().size() - 1);
        assertEquals("TEST_FRAME", lastLog.getSource());
        assertTrue(lastLog.getNote().contains("test frame"));
        assertEquals("test_frame", lastLog.getDirection());
    }

    @Test
    public void testGetStatusAfterTestFrame() {
        // inject test frame first
        service.injectTestFrame("T1", "traction");
        // then query status
        Protocol704Status status = service.getStatus("T1");
        assertNotNull(status.getLastParsedFrame());
        assertEquals("traction", status.getLastMappedCommand().getCommand());
        assertNotNull(status.getLastRawHex());
        assertFalse(status.getRecentLogs().isEmpty());
    }

    @Test
    public void testRealtimeStateUsesRequestedTrainIdAndIsIsolated() {
        Protocol704Status first = service.getStatus("OB1");
        Protocol704Status second = service.getStatus("OB2");

        assertEquals("OB1", first.getRealtimeVehicleState().getTrainId());
        assertEquals("OB2", second.getRealtimeVehicleState().getTrainId());
        assertNotSame(first.getRealtimeVehicleState(), second.getRealtimeVehicleState());
    }

    @Test
    public void plcAccumulatorDistinguishesTransportAndValidationStates() {
        Protocol704FrameAccumulator accumulator = new Protocol704FrameAccumulator();

        assertTrue(accumulator.append(new byte[46]).isEmpty());
        assertEquals(Protocol704InputState.HEADER_MISMATCH, accumulator.getLastInputState());
        assertEquals(0, accumulator.pendingBytes());

        byte[] valid = validPlcFrame();
        assertTrue(accumulator.append(java.util.Arrays.copyOf(valid, 23)).isEmpty());
        assertEquals(Protocol704InputState.PARTIAL_FRAME, accumulator.getLastInputState());
        assertEquals(23, accumulator.pendingBytes());

        assertEquals(1, accumulator.append(java.util.Arrays.copyOfRange(valid, 23, valid.length)).size());
        assertEquals(Protocol704InputState.FIRST_FRAME_RECEIVED, accumulator.getLastInputState());

        assertEquals(1, accumulator.append(valid).size());
        assertEquals(Protocol704InputState.FRAME_RECEIVED, accumulator.getLastInputState());
    }

    @Test
    public void plcAccumulatorRejectsLengthErrorsWithoutEmittingFrames() {
        Protocol704FrameAccumulator totalLengthError = new Protocol704FrameAccumulator();
        byte[] wrongTotal = validPlcFrame();
        ByteBuffer.wrap(wrongTotal).order(ByteOrder.LITTLE_ENDIAN).putShort(4, (short) 45);
        assertTrue(totalLengthError.append(wrongTotal).isEmpty());
        assertEquals(Protocol704InputState.TOTAL_LENGTH_MISMATCH, totalLengthError.getLastInputState());

        Protocol704FrameAccumulator dataLengthError = new Protocol704FrameAccumulator();
        byte[] wrongData = validPlcFrame();
        ByteBuffer.wrap(wrongData).order(ByteOrder.LITTLE_ENDIAN).putShort(6, (short) 21);
        assertTrue(dataLengthError.append(wrongData).isEmpty());
        assertEquals(Protocol704InputState.DATA_LENGTH_MISMATCH, dataLengthError.getLastInputState());
    }

    @Test
    public void hmiAccumulatorReportsTrailingPartialFrameAfterValidFrame() {
        Protocol704HmiFrameAccumulator accumulator = new Protocol704HmiFrameAccumulator();
        byte[] valid = validHmiFrame();
        byte[] input = new byte[valid.length + 13];
        System.arraycopy(valid, 0, input, 0, valid.length);
        System.arraycopy(valid, 0, input, valid.length, 13);

        assertEquals(1, accumulator.append(input).size());
        assertEquals(Protocol704InputState.PARTIAL_FRAME, accumulator.getLastInputState());
        assertEquals(13, accumulator.pendingBytes());
    }

    private static byte[] validPlcFrame() {
        byte[] frame = new byte[Protocol704FrameAccumulator.FRAME_LENGTH];
        ByteBuffer bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0, 0xAA55AA55);
        bb.putShort(4, (short) Protocol704FrameAccumulator.FRAME_LENGTH);
        bb.putShort(6, (short) 22);
        return frame;
    }

    private static byte[] validHmiFrame() {
        byte[] frame = new byte[Protocol704HmiInputParser.FRAME_LENGTH];
        ByteBuffer bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0, Protocol704HmiInputParser.IDENTIFY);
        bb.putShort(4, (short) Protocol704HmiInputParser.FRAME_LENGTH);
        bb.putShort(6, (short) Protocol704HmiInputParser.DATA_LENGTH);
        return frame;
    }
}
