package com.bjtu.railtransit.hil;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TeacherDeviceFrameCodecTest {
    private final HilVehicleSnapshot state = new HilVehicleSnapshot(
            "T1", 12, 2448.61, 10, 0.5, 65,
            3, 4, 13, 0, "ATO", "TRACTION", 50,
            false, true, true, true, true,
            1480, 82, 120, 700, 0x02);

    @Test
    void encodesDocumentedPlcOutputFrameWithoutUnverifiedSpeedWord() {
        byte[] frame = TeacherDeviceFrameCodec.plcOutput(state,
                TeacherDeviceFrameCodec.PlcOutputFrameFormat.DOCUMENTED_26);
        assertEquals(26, frame.length);
        assertEquals(0xAA55AA55, le(frame).getInt(0));
        assertEquals(26, Short.toUnsignedInt(le(frame).getShort(4)));
        assertEquals(2, Short.toUnsignedInt(le(frame).getShort(6)));
        assertEquals(0x20, Byte.toUnsignedInt(frame[24]));
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
        assertEquals(66, frame.length);
        assertEquals(3, Byte.toUnsignedInt(frame[36]));
        assertEquals(4, Byte.toUnsignedInt(frame[37]));
        assertEquals(36.0, b.getFloat(42), 0.001);
        assertEquals(0.5, b.getFloat(46), 0.001);
        assertEquals(65, Short.toUnsignedInt(b.getShort(52)));
        assertEquals(1, Byte.toUnsignedInt(frame[54]));
        assertEquals(2, Byte.toUnsignedInt(frame[59]));
        assertEquals(700.0, b.getFloat(62), 0.001);
    }

    @Test
    void encodesNetworkScreenCoreAndPerCarFields() {
        byte[] frame = TeacherDeviceFrameCodec.networkScreen(state);
        ByteBuffer b = le(frame);
        assertEquals(572, frame.length);
        assertEquals(36.0, b.getFloat(40), 0.001);
        assertEquals(1480, Short.toUnsignedInt(b.getShort(50)));
        assertEquals(1, Byte.toUnsignedInt(frame[54]));
        assertEquals(800, Short.toUnsignedInt(b.getShort(144)));
        assertEquals(50, Byte.toUnsignedInt(frame[168]));
        assertEquals(82, Byte.toUnsignedInt(frame[174]));
        assertEquals(1, Short.toUnsignedInt(b.getShort(570)));
    }

    private static ByteBuffer le(byte[] frame) {
        return ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
    }
}
