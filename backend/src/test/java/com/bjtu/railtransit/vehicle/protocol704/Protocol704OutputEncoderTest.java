package com.bjtu.railtransit.vehicle.protocol704;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class Protocol704OutputEncoderTest {

    @Test
    void encodeOutputFrame_headerAndLengths() {
        RealtimeVehicleState state = new RealtimeVehicleState();
        LocalDateTime ts = LocalDateTime.of(2026, 7, 12, 10, 30, 45);
        byte[] frame = Protocol704OutputEncoder.encodeOutputFrame(state, ts);

        assertEquals(26, frame.length);
        assertEquals((byte) 0x55, frame[0]);
        assertEquals((byte) 0xAA, frame[1]);
        assertEquals((byte) 0x55, frame[2]);
        assertEquals((byte) 0xAA, frame[3]);

        ByteBuffer bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(26, bb.getShort(4) & 0xFFFF);
        assertEquals(2, bb.getShort(6) & 0xFFFF);
    }

    @Test
    void encodeOutputFrame_timestampFields() {
        RealtimeVehicleState state = new RealtimeVehicleState();
        LocalDateTime ts = LocalDateTime.of(2026, 7, 12, 10, 30, 45);
        byte[] frame = Protocol704OutputEncoder.encodeOutputFrame(state, ts);
        ByteBuffer bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(2026, bb.getShort(8) & 0xFFFF);
        assertEquals(7, bb.getShort(10) & 0xFFFF);
        assertEquals(12, bb.getShort(12) & 0xFFFF);
        assertEquals(10, bb.getShort(14) & 0xFFFF);
        assertEquals(30, bb.getShort(16) & 0xFFFF);
        assertEquals(45, bb.getShort(18) & 0xFFFF);
    }

    @Test
    void encodeOutputFrame_doorsClosedBit5() {
        RealtimeVehicleState state = new RealtimeVehicleState();
        state.setDoorsClosed(true);
        state.setNetworkFault(false);
        byte[] frame = Protocol704OutputEncoder.encodeOutputFrame(state, LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        assertEquals(1, (frame[24] >> 5) & 1);
        assertEquals(0, (frame[24] >> 6) & 1);
        assertEquals(0, (frame[24] >> 7) & 1);
    }

    @Test
    void encodeOutputFrame_modeAto_bits() {
        RealtimeVehicleState state = new RealtimeVehicleState();
        state.setMode("ATO");
        byte[] frame = Protocol704OutputEncoder.encodeOutputFrame(state, LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        assertEquals(1, frame[25] & 0x01);
        assertEquals(1, (frame[25] >> 2) & 1);
    }

    @Test
    void encodeOutputFrame_emergencyMode_doesNotAffectByte24Bits() {
        RealtimeVehicleState state = new RealtimeVehicleState();
        state.setMode("EMERGENCY");
        state.setDoorsClosed(true);
        state.setNetworkFault(true);
        byte[] frame = Protocol704OutputEncoder.encodeOutputFrame(state, LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        assertEquals(1, (frame[24] >> 5) & 1);
        assertEquals(1, (frame[24] >> 6) & 1);
        assertEquals(0, (frame[24] >> 7) & 1);
        assertEquals(1, frame[25] & 0x01);
        assertEquals(0, (frame[25] >> 2) & 1);
    }

    @Test
    void encodeOutputFrame_nullStateAndTimestamp_safeDefaults() {
        byte[] frame = Protocol704OutputEncoder.encodeOutputFrame(null, null);
        assertEquals(26, frame.length);
        assertEquals((byte) 0x55, frame[0]);
        assertEquals(1, (frame[24] >> 5) & 1);
        assertEquals(1, frame[25] & 0x01);
    }

    @Test
    void encodeHmiFrame_lengthAndSpeedAndLevelPos() {
        RealtimeVehicleState state = new RealtimeVehicleState();
        state.setVelocityMs(12.5);
        state.setAccelerationMs2(-0.8);
        state.setMode("EMERGENCY");
        state.setLastCommand("emergency_brake");
        LocalDateTime ts = LocalDateTime.of(2026, 7, 12, 10, 30, 45);
        byte[] hmi = Protocol704HmiEncoder.encodeHmiFrame(state, ts);

        assertEquals(572, hmi.length);
        assertEquals((byte) 0x55, hmi[0]);
        assertEquals((byte) 0xAA, hmi[1]);
        assertEquals((byte) 0x55, hmi[2]);
        assertEquals((byte) 0xAA, hmi[3]);

        ByteBuffer bb = ByteBuffer.wrap(hmi).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(572, bb.getShort(4) & 0xFFFF);
        assertEquals(12.5f, bb.getFloat(40), 1e-5);
        assertEquals(-0.8f, bb.getFloat(44), 1e-5);
        assertEquals(3, hmi[54] & 0xFF);
    }

    @Test
    void encodeHmiFrame_levelPos_tractionAndBrake() {
        RealtimeVehicleState traction = new RealtimeVehicleState();
        traction.setMode("MANUAL");
        traction.setLastCommand("traction");
        byte[] hmiTraction = Protocol704HmiEncoder.encodeHmiFrame(traction, LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        assertEquals(1, hmiTraction[54] & 0xFF);

        RealtimeVehicleState brake = new RealtimeVehicleState();
        brake.setMode("MANUAL");
        brake.setLastCommand("brake");
        byte[] hmiBrake = Protocol704HmiEncoder.encodeHmiFrame(brake, LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        assertEquals(2, hmiBrake[54] & 0xFF);

        RealtimeVehicleState coast = new RealtimeVehicleState();
        coast.setMode("ATO");
        coast.setLastCommand("coast");
        byte[] hmiCoast = Protocol704HmiEncoder.encodeHmiFrame(coast, LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        assertEquals(0, hmiCoast[54] & 0xFF);
    }

    @Test
    void encodeHmiFrame_runModeManual() {
        RealtimeVehicleState state = new RealtimeVehicleState();
        state.setMode("MANUAL");
        byte[] hmi = Protocol704HmiEncoder.encodeHmiFrame(state, LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        // low nibble = 0 (manual), high nibble = 1 (AM)
        assertEquals(0x10, hmi[55] & 0xFF);
    }

    @Test
    void encodeHmiFrame_runModeAto() {
        RealtimeVehicleState state = new RealtimeVehicleState();
        state.setMode("ATO");
        byte[] hmi = Protocol704HmiEncoder.encodeHmiFrame(state, LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        // low nibble = 1 (ATO), high nibble = 1 (AM)
        assertEquals(0x11, hmi[55] & 0xFF);
    }

    // ── MMI 66B encoder tests ──

    @Test
    void encodeMmiFrame_lengthAndHeader() {
        RealtimeVehicleState state = new RealtimeVehicleState();
        byte[] mmi = Protocol704MmiEncoder.encodeMmiFrame(state, LocalDateTime.of(2026, 7, 12, 10, 30, 45));
        assertEquals(66, mmi.length);
        // wire bytes 55 AA 55 AA
        assertEquals((byte) 0x55, mmi[0]);
        assertEquals((byte) 0xAA, mmi[1]);
        assertEquals((byte) 0x55, mmi[2]);
        assertEquals((byte) 0xAA, mmi[3]);

        ByteBuffer bb = ByteBuffer.wrap(mmi).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(66, bb.getShort(4) & 0xFFFF);
        assertEquals(30, bb.getShort(6) & 0xFFFF);
    }

    @Test
    void encodeMmiFrame_speedAndAcceleration() {
        RealtimeVehicleState state = new RealtimeVehicleState();
        state.setVelocityMs(15.5);
        state.setAccelerationMs2(0.8);
        byte[] mmi = Protocol704MmiEncoder.encodeMmiFrame(state, LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        ByteBuffer bb = ByteBuffer.wrap(mmi).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(15.5f, bb.getFloat(44), 1e-5);
        assertEquals(0.8f, bb.getFloat(48), 1e-5);
    }

    @Test
    void encodeMmiFrame_modeMapping() {
        RealtimeVehicleState state = new RealtimeVehicleState();
        state.setMode("ATO");
        byte[] mmi = Protocol704MmiEncoder.encodeMmiFrame(state, LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        assertEquals(1, mmi[56] & 0xFF); // ATO → 1

        state.setMode("EMERGENCY");
        mmi = Protocol704MmiEncoder.encodeMmiFrame(state, LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        assertEquals(4, mmi[56] & 0xFF); // unknown → RM(4)
    }

    @Test
    void encodeMmiFrame_pullBrakeEmergencyStates() {
        RealtimeVehicleState state = new RealtimeVehicleState();
        state.setLastCommand("traction");
        byte[] mmi = Protocol704MmiEncoder.encodeMmiFrame(state, LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        assertEquals(1, mmi[57] & 0xFF); // pull state = 1
        assertEquals(0, mmi[58] & 0xFF); // brake state = 0
        assertEquals(0, mmi[59] & 0xFF); // emergency = 0

        state.setLastCommand("emergency_brake");
        state.setMode("EMERGENCY");
        mmi = Protocol704MmiEncoder.encodeMmiFrame(state, LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        assertEquals(1, mmi[59] & 0xFF); // emergency = 1
    }

    @Test
    void encodeHmiFrame_runDir_defaultUp() {
        RealtimeVehicleState state = new RealtimeVehicleState();
        state.setDirection("UP");
        byte[] hmi = Protocol704HmiEncoder.encodeHmiFrame(state, LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        assertEquals(1, hmi[58] & 0xFF);
    }

    @Test
    void encodeHmiFrame_runDir_down() {
        RealtimeVehicleState state = new RealtimeVehicleState();
        state.setDirection("DOWN");
        byte[] hmi = Protocol704HmiEncoder.encodeHmiFrame(state, LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        assertEquals(2, hmi[58] & 0xFF);
    }

    @Test
    void encodeHmiFrame_pullSwitch_tractionCutMask() {
        RealtimeVehicleState state = new RealtimeVehicleState();
        boolean[] mask = {true, false, true, false, false, false};
        state.setTractionCutMask(mask);
        byte[] hmi = Protocol704HmiEncoder.encodeHmiFrame(state, LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        assertEquals(0x11, hmi[108] & 0xFF);
        assertEquals(0x00, hmi[109] & 0xFF);
        assertEquals(0x11, hmi[110] & 0xFF);
        for (int i = 3; i < 6; i++) {
            assertEquals(0x00, hmi[108 + i] & 0xFF);
        }
    }

    @Test
    void encodeHmiFrame_usageRate_fromPerCarLoad() {
        RealtimeVehicleState state = new RealtimeVehicleState();
        double[] loads = {20000, 10000, 0, 0, 0, 0};
        state.setPerCarLoadKg(loads);
        byte[] hmi = Protocol704HmiEncoder.encodeHmiFrame(state, LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        assertEquals(100, hmi[168] & 0xFF);
        assertEquals(50, hmi[169] & 0xFF);
        for (int i = 2; i < 6; i++) {
            assertEquals(0, hmi[168 + i] & 0xFF);
        }
    }

    @Test
    void encodeMmiFrame_runDir_defaultUp() {
        RealtimeVehicleState state = new RealtimeVehicleState();
        state.setDirection("UP");
        byte[] mmi = Protocol704MmiEncoder.encodeMmiFrame(state, LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        assertEquals(0, mmi[42] & 0xFF);
    }

    @Test
    void encodeMmiFrame_runDir_down() {
        RealtimeVehicleState state = new RealtimeVehicleState();
        state.setDirection("DOWN");
        byte[] mmi = Protocol704MmiEncoder.encodeMmiFrame(state, LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        assertEquals(1, mmi[42] & 0xFF);
    }
}
