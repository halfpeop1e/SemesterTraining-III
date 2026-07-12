package com.bjtu.railtransit.vehicle.protocol704;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

public class Protocol704FrameParserTest {

    private byte[] buildValidPlcFrame(int masterHandle, int tractionLevel, int brakeLevel, boolean ebButton) {
        byte[] frame = new byte[46];
        ByteBuffer bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);

        bb.putInt(0, 0xAA55AA55);
        bb.putShort(4, (short) 46);
        bb.putShort(6, (short) 22);

        bb.putShort(8, (short) 2026);
        bb.putShort(10, (short) 7);
        bb.putShort(12, (short) 9);
        bb.putShort(14, (short) 14);
        bb.putShort(16, (short) 30);
        bb.putShort(18, (short) 0);

        bb.putShort(20, (short) 0);
        bb.putShort(22, (short) 0);

        frame[24] = 0x20;
        frame[25] = 0x00;

        bb.putShort(26, (short) 0);

        if (ebButton) {
            frame[28] = 0x01;
        } else {
            frame[28] = 0x00;
        }
        frame[29] = 0x00;
        bb.putShort(30, (short) 0);
        bb.putShort(32, (short) 0);
        frame[34] = 0x00;
        frame[35] = 0x02;

        bb.putShort(36, (short) 1);
        bb.putShort(38, (short) masterHandle);
        bb.putShort(40, (short) tractionLevel);
        bb.putShort(42, (short) brakeLevel);
        bb.putShort(44, (short) 0);

        return frame;
    }

    @Test
    public void testParseEmptyFrame() {
        byte[] empty = new byte[0];
        Parsed704Frame result = Protocol704FrameParser.parseFrame(empty);
        assertEquals(0, result.getFrameLength());
        assertTrue(result.isHasUnverifiedFields());
        assertNotNull(result.getFields());
        assertNotNull(result.getMappedCommand());
    }

    @Test
    public void testValidPlcFrameHeader() {
        byte[] frame = buildValidPlcFrame(0, 0, 0, false);
        Parsed704Frame result = Protocol704FrameParser.parseFrame(frame);
        assertEquals(46, result.getFrameLength());
        assertEquals("0xAA55AA55", result.getFields().get("frame_header"));
        assertEquals(true, result.getFields().get("header_valid"));
        assertEquals(46, result.getFields().get("total_len_field"));
        assertEquals(22, result.getFields().get("data_len_field"));
        assertNotNull(result.getFields().get("plc_time"));
        assertTrue(((String) result.getFields().get("plc_time")).startsWith("2026"));
    }

    @Test
    public void testFrameHeaderMismatch() {
        byte[] frame = new byte[46];
        frame[0] = 0x00;
        Parsed704Frame result = Protocol704FrameParser.parseFrame(frame);
        assertEquals(false, result.getFields().get("header_valid"));
        assertTrue(result.isHasUnverifiedFields());
        assertTrue(result.getNote().contains("frame header mismatch"));
    }

    @Test
    public void testCoastPosition() {
        byte[] frame = buildValidPlcFrame(0x0000, 0, 0, false);
        Parsed704Frame result = Protocol704FrameParser.parseFrame(frame);
        MappedControlCommand cmd = result.getMappedCommand();
        assertNotNull(cmd);
        assertEquals("coast", cmd.getCommand());
        assertEquals(0, cmd.getMasterHandle());
        assertEquals(0, cmd.getLevelPercent());
        assertEquals(0, cmd.getTargetDecel(), 0.001);
        assertFalse(cmd.isVerified());
    }

    @Test
    public void testTractionPosition() {
        byte[] frame = buildValidPlcFrame(0x0001, 50, 0, false);
        Parsed704Frame result = Protocol704FrameParser.parseFrame(frame);
        MappedControlCommand cmd = result.getMappedCommand();
        assertNotNull(cmd);
        assertEquals("traction", cmd.getCommand());
        assertEquals(1, cmd.getMasterHandle());
        assertEquals(50, cmd.getLevelPercent());
        assertEquals(0, cmd.getTargetDecel(), 0.001);
        assertEquals(50, cmd.getTractionLevelRaw());
    }

    @Test
    public void testBrakePosition() {
        byte[] frame = buildValidPlcFrame(0x0002, 0, 75, false);
        Parsed704Frame result = Protocol704FrameParser.parseFrame(frame);
        MappedControlCommand cmd = result.getMappedCommand();
        assertNotNull(cmd);
        assertEquals("brake", cmd.getCommand());
        assertEquals(2, cmd.getMasterHandle());
        assertEquals(75, cmd.getLevelPercent());
        assertTrue(cmd.getTargetDecel() > 0);
        assertEquals(75, cmd.getBrakeLevelRaw());
    }

    @Test
    public void testEmergencyBrakeFromButton() {
        byte[] frame = buildValidPlcFrame(0x0000, 0, 0, true);
        Parsed704Frame result = Protocol704FrameParser.parseFrame(frame);
        MappedControlCommand cmd = result.getMappedCommand();
        assertNotNull(cmd);
        assertEquals("emergency_brake", cmd.getCommand());
        assertEquals(100, cmd.getLevelPercent());
        assertTrue(cmd.getTargetDecel() > 1.0);
    }

    @Test
    public void testFastBrakePosition() {
        byte[] frame = buildValidPlcFrame(0x0004, 0, 100, false);
        Parsed704Frame result = Protocol704FrameParser.parseFrame(frame);
        MappedControlCommand cmd = result.getMappedCommand();
        assertNotNull(cmd);
        assertEquals("emergency_brake", cmd.getCommand());
    }

    @Test
    public void testDirectionHandle() {
        byte[] frame = buildValidPlcFrame(0, 0, 0, false);
        Parsed704Frame result = Protocol704FrameParser.parseFrame(frame);
        MappedControlCommand cmd = result.getMappedCommand();
        assertEquals(1, cmd.getDirectionHandle());
        assertEquals("向前", result.getFields().get("direction_desc"));
    }

    @Test
    public void testBytesToHex() {
        byte[] data = new byte[]{(byte) 0xAB, (byte) 0xCD, 0x12};
        String hex = Protocol704FrameParser.bytesToHex(data);
        assertEquals("AB CD 12", hex);
    }

    @Test
    public void testUnexpectedLengthFrame() {
        byte[] shortFrame = new byte[10];
        Parsed704Frame result = Protocol704FrameParser.parseFrame(shortFrame);
        assertEquals(10, result.getFrameLength());
        assertTrue(result.isHasUnverifiedFields());
        assertNotNull(result.getNote());
        assertTrue(result.getNote().contains("unexpected frame length"));
    }

    @Test
    public void testDoorIndicators() {
        byte[] frame = buildValidPlcFrame(0, 0, 0, false);
        frame[24] = 0x20;
        Parsed704Frame result = Protocol704FrameParser.parseFrame(frame);
        assertEquals(true, result.getFields().get("doors_closed_ok"));
        assertEquals(false, result.getFields().get("network_fault"));
    }

    @Test
    public void testRealtimeVehicleStateInit() {
        RealtimeVehicleState state = new RealtimeVehicleState();
        assertEquals("T1", state.getTrainId());
        assertEquals(0.0, state.getPositionM(), 0.001);
        assertEquals(0.0, state.getVelocityMs(), 0.001);
        assertEquals("MANUAL", state.getMode());
        assertEquals("none", state.getLastCommand());
    }

    @Test
    public void testEbButtonBit() {
        byte[] frame = buildValidPlcFrame(0x0001, 50, 0, false);
        frame[28] = 0x01;
        Parsed704Frame result = Protocol704FrameParser.parseFrame(frame);
        MappedControlCommand cmd = result.getMappedCommand();
        assertEquals("emergency_brake", cmd.getCommand());
        assertEquals(true, result.getFields().get("eb_button_locked"));
    }

    @Test
    public void localV1ModeDepartureAndDirectionCommandsAreMapped() {
        byte[] manual = buildValidPlcFrame(0, 0, 0, false);
        manual[34] = 0x08;
        assertEquals("SET_MANUAL", Protocol704FrameParser.parseFrame(manual).getMappedCommand().getCommand());

        byte[] ato = buildValidPlcFrame(0, 0, 0, false);
        ato[34] = (byte) 0x80;
        assertEquals("RESUME_ATO", Protocol704FrameParser.parseFrame(ato).getMappedCommand().getCommand());

        byte[] depart = buildValidPlcFrame(0, 0, 0, false);
        depart[34] = 0x10;
        assertEquals("DEPART_CONFIRM", Protocol704FrameParser.parseFrame(depart).getMappedCommand().getCommand());

        byte[] reverse = buildValidPlcFrame(0, 0, 0, false);
        ByteBuffer.wrap(reverse).order(ByteOrder.LITTLE_ENDIAN).putShort(36, (short) 2);
        assertEquals("REVERSE", Protocol704FrameParser.parseFrame(reverse).getMappedCommand().getDirection());

        byte[] unknown = buildValidPlcFrame(0, 0, 0, false);
        ByteBuffer.wrap(unknown).order(ByteOrder.LITTLE_ENDIAN).putShort(36, (short) 9);
        assertEquals("UNSUPPORTED", Protocol704FrameParser.parseFrame(unknown).getMappedCommand().getCommand());
    }

    @Test
    public void parsesRealLaboratoryDriverDeskFrameCapturedByPeerSystem() {
        // PLC -> upper-computer record from 2026-07-12. This is hardware-originated
        // input, unlike the peer system's own PLC output records.
        byte[] frame = hex("55 aa 55 aa 2e 00 16 00 ea 07 07 00 0c 00 10 00 "
                + "18 00 00 00 01 00 f3 1b 20 01 00 00 00 00 00 00 00 00 "
                + "80 02 01 00 01 00 14 00 00 00 00 00");

        Parsed704Frame result = Protocol704FrameParser.parseFrame(frame);

        assertEquals(true, result.getFields().get("header_valid"));
        assertEquals(46, result.getFrameLength());
        assertEquals(true, result.getFields().get("key_switch_on"));
        assertEquals(true, result.getFields().get("ato_start_btn"));
        assertEquals(1, result.getFields().get("direction_handle"));
        assertEquals(1, result.getFields().get("master_handle"));
        assertEquals(20, result.getFields().get("traction_level_percent_raw"));
        // Local policy maps the captured ATO-start bit to an ATO resume request.
        assertEquals("RESUME_ATO", result.getMappedCommand().getCommand());
    }

    private static byte[] hex(String value) {
        String[] parts = value.trim().split("\\s+");
        byte[] bytes = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            bytes[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return bytes;
    }
}
