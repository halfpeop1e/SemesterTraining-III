package com.bjtu.railtransit.vehicle.protocol704;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Protocol704FrameAccumulatorTest {

    @Test
    void rebuildsSplitFramesAndSkipsLeadingGarbage() {
        byte[] frame = validFrame();
        Protocol704FrameAccumulator accumulator = new Protocol704FrameAccumulator();

        assertEquals(0, accumulator.append(new byte[] {0x11, 0x22, frame[0], frame[1]}).size());
        List<byte[]> frames = accumulator.append(slice(frame, 2, frame.length));

        assertEquals(1, frames.size());
        assertEquals(46, frames.get(0).length);
    }

    @Test
    void rebuildsTwoFramesFromOneReadAndRecoversAfterInvalidLength() {
        byte[] first = validFrame();
        byte[] second = validFrame();
        second[40] = 60;
        byte[] invalid = validFrame();
        invalid[4] = 45;

        Protocol704FrameAccumulator accumulator = new Protocol704FrameAccumulator();
        assertEquals(2, accumulator.append(concat(invalid, first, second)).size());
    }

    private static byte[] validFrame() {
        byte[] frame = new byte[46];
        ByteBuffer bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0, 0xAA55AA55);
        bb.putShort(4, (short) 46);
        bb.putShort(6, (short) 22);
        bb.putShort(38, (short) 1);
        bb.putShort(40, (short) 50);
        return frame;
    }

    private static byte[] slice(byte[] value, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(value, from, out, 0, out.length);
        return out;
    }

    private static byte[] concat(byte[]... values) {
        int length = 0;
        for (byte[] value : values) length += value.length;
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, out, offset, value.length);
            offset += value.length;
        }
        return out;
    }
}
