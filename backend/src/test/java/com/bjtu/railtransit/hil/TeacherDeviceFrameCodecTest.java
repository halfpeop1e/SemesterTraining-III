package com.bjtu.railtransit.hil;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TeacherDeviceFrameCodecTest {
    private final HilVehicleSnapshot state = new HilVehicleSnapshot(
            "T1", 12, 2448.61, 10, 0.5, 65,
            3, 4, 13, 0, "ATO", "TRACTION", 50,
            false, true, true, true, true,
            1480, 82, 120, 700, 0x02, 16, 1);

    @Test
    void encodesDocumentedPlcOutputFrameWithoutUnverifiedSpeedWord() {
        byte[] frame = TeacherDeviceFrameCodec.plcOutput(state,
                TeacherDeviceFrameCodec.PlcOutputFrameFormat.DOCUMENTED_26);
        assertEquals(26, frame.length);
        assertEquals(0xAA55AA55, le(frame).getInt(0));
        assertEquals(26, Short.toUnsignedInt(le(frame).getShort(4)));
        assertEquals(2, Short.toUnsignedInt(le(frame).getShort(6)));
        assertEquals(0x22, Byte.toUnsignedInt(frame[24]));
        assertEquals(0x05, Byte.toUnsignedInt(frame[25]));
    }

    @Test
    void encodesThirdPartyCaptureVariantOnlyWhenExplicitlyRequested() {
        byte[] frame = TeacherDeviceFrameCodec.plcOutput(state,
                TeacherDeviceFrameCodec.PlcOutputFrameFormat.CAPTURE_VARIANT_28);
        assertEquals(28, frame.length);
        assertEquals(28, Short.toUnsignedInt(le(frame).getShort(4)));
        assertEquals(4, Short.toUnsignedInt(le(frame).getShort(6)));
        assertEquals(36, Short.toUnsignedInt(le(frame).getShort(26)));
    }

    @Test
    void encodesSignalScreenAtTeacherOffsets() {
        byte[] frame = TeacherDeviceFrameCodec.signalScreen(state);
        ByteBuffer b = le(frame);
        assertEquals(68, frame.length);
        assertEquals(62, Short.toUnsignedInt(b.getShort(4)));
        assertEquals(42, Short.toUnsignedInt(b.getShort(6)));
        assertEquals(3, Byte.toUnsignedInt(frame[36]));
        assertEquals(0, Byte.toUnsignedInt(frame[39]));
        assertEquals(0, Byte.toUnsignedInt(frame[40]));
        assertEquals(0, Byte.toUnsignedInt(frame[41]));
        assertEquals(4, Byte.toUnsignedInt(frame[37]));
        assertEquals(0, Byte.toUnsignedInt(frame[42]));
        assertEquals(36.0, b.getFloat(44), 0.001);
        assertEquals(0.5, b.getFloat(48), 0.001);
        assertEquals(65, Short.toUnsignedInt(b.getShort(54)));
        assertEquals(1, Byte.toUnsignedInt(frame[56]));
        assertEquals(2, Byte.toUnsignedInt(frame[61]));
        assertEquals(700.0, b.getFloat(64), 0.001);
    }

    @Test
    void encodesNetworkScreenCoreAndPerCarFields() {
        byte[] frame = TeacherDeviceFrameCodec.networkScreen(state);
        ByteBuffer b = le(frame);
        assertEquals(570, frame.length);
        assertEquals(570, Short.toUnsignedInt(b.getShort(4)));
        assertEquals(546, Short.toUnsignedInt(b.getShort(6)));
        assertEquals(36.0, b.getFloat(40), 0.001);
        assertEquals(1480, Short.toUnsignedInt(b.getShort(50)));
        assertEquals(1, Byte.toUnsignedInt(frame[54]));
        assertEquals(800, Short.toUnsignedInt(b.getShort(144)));
        assertEquals(50, Byte.toUnsignedInt(frame[168]));
        assertEquals(82, Byte.toUnsignedInt(frame[174]));
        assertEquals(1, Short.toUnsignedInt(b.getShort(568)));
    }

    @Test
    void encodesTheConfiguredPhysicalScreenTrainNumber() {
        byte[] mmi = TeacherDeviceFrameCodec.signalScreen(state, 1001);
        byte[] hmi = TeacherDeviceFrameCodec.networkScreen(state, 1001);

        assertEquals(1001, Short.toUnsignedInt(le(mmi).getShort(62)));
        assertEquals(1001, Short.toUnsignedInt(le(hmi).getShort(568)));
    }

    @Test
    void encodesDocumentedVision13BasePacketAt128Bytes() {
        byte[] signals = new byte[TeacherDeviceFrameCodec.VISION_SIGNAL_COUNT];
        byte[] switches = new byte[TeacherDeviceFrameCodec.VISION_SWITCH_COUNT];
        Arrays.fill(signals, (byte) 0x01);
        Arrays.fill(switches, (byte) 0x01);
        signals[1] = 0x02;
        switches[2] = 0x02;

        byte[] frame = TeacherDeviceFrameCodec.vision(state, 42, signals, switches);
        ByteBuffer b = le(frame);
        assertEquals(128, frame.length);
        assertEquals(42, b.getInt(0));
        assertEquals(77, Byte.toUnsignedInt(frame[4]));
        assertEquals(0x02, Byte.toUnsignedInt(frame[6]));
        assertEquals(29, Byte.toUnsignedInt(frame[82]));
        assertEquals(0x02, Byte.toUnsignedInt(frame[85]));
        assertEquals(10_000, b.getInt(112));
        assertEquals(0x11, Byte.toUnsignedInt(frame[118]));
        assertEquals(2_448_610, b.getInt(120));
        assertEquals(16, Short.toUnsignedInt(b.getShort(124)));
        assertEquals(1, Byte.toUnsignedInt(frame[126]));
        assertEquals(0, Byte.toUnsignedInt(frame[127]));
    }

    private static ByteBuffer le(byte[] frame) {
        return ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
    }
}
