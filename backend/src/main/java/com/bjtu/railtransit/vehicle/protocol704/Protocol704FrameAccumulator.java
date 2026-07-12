package com.bjtu.railtransit.vehicle.protocol704;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * local-v1 TCP stream reassembly for the only accepted 46-byte PLC frame.
 * This class owns transport framing only; validation and business handling stay downstream.
 */
final class Protocol704FrameAccumulator {

    static final int FRAME_LENGTH = 46;
    private static final byte[] MAGIC = {(byte) 0x55, (byte) 0xAA, (byte) 0x55, (byte) 0xAA};
    private byte[] pending = new byte[0];

    List<byte[]> append(byte[] incoming) {
        if (incoming == null || incoming.length == 0) {
            return List.of();
        }
        byte[] combined = Arrays.copyOf(pending, pending.length + incoming.length);
        System.arraycopy(incoming, 0, combined, pending.length, incoming.length);
        List<byte[]> frames = new ArrayList<>();
        int offset = 0;
        while (combined.length - offset >= MAGIC.length) {
            int header = findHeader(combined, offset);
            if (header < 0) {
                offset = Math.max(offset, combined.length - (MAGIC.length - 1));
                break;
            }
            if (combined.length - header < FRAME_LENGTH) {
                offset = header;
                break;
            }
            int totalLength = unsignedLe16(combined, header + 4);
            int dataLength = unsignedLe16(combined, header + 6);
            if (totalLength != FRAME_LENGTH || dataLength != 22) {
                offset = header + 1;
                continue;
            }
            frames.add(Arrays.copyOfRange(combined, header, header + FRAME_LENGTH));
            offset = header + FRAME_LENGTH;
        }
        pending = Arrays.copyOfRange(combined, offset, combined.length);
        return frames;
    }

    void reset() {
        pending = new byte[0];
    }

    private static int findHeader(byte[] data, int start) {
        for (int i = start; i <= data.length - MAGIC.length; i++) {
            if (data[i] == MAGIC[0] && data[i + 1] == MAGIC[1]
                    && data[i + 2] == MAGIC[2] && data[i + 3] == MAGIC[3]) {
                return i;
            }
        }
        return -1;
    }

    private static int unsignedLe16(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }
}
