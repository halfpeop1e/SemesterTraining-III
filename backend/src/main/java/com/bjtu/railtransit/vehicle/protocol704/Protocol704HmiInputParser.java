package com.bjtu.railtransit.vehicle.protocol704;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

// local-v1, not validated with real hardware
public final class Protocol704HmiInputParser {

    public static final int FRAME_LENGTH = 26;
    public static final int HEADER_LENGTH = 24;
    public static final int DATA_LENGTH = FRAME_LENGTH - HEADER_LENGTH;
    public static final int IDENTIFY = 0xAA55AA55;

    private Protocol704HmiInputParser() {}

    public static HmiTractionCutRequest parseTractionCut(byte[] frame) {
        if (!isStructurallyValid(frame)) {
            return null;
        }

        ByteBuffer bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);

        int identify = bb.getInt(0);
        if (identify != IDENTIFY) {
            return null;
        }

        int totalLen = bb.getShort(4) & 0xFFFF;
        if (totalLen != FRAME_LENGTH) {
            return null;
        }

        long epochMs = bb.getLong(8);
        LocalDateTime timestamp = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());

        byte nPullCtrl = frame[24];
        boolean[] carCutMask = new boolean[6];
        for (int i = 0; i < 6; i++) {
            carCutMask[i] = ((nPullCtrl >> i) & 0x01) == 1;
        }

        HmiTractionCutRequest req = new HmiTractionCutRequest();
        req.setCarCutMask(carCutMask);
        req.setTimestamp(timestamp);
        return req;
    }

    /** Strict validation for the documented fixed 26B local-v1 frame. */
    public static boolean isStructurallyValid(byte[] frame) {
        if (frame == null || frame.length != FRAME_LENGTH) return false;
        ByteBuffer bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        return bb.getInt(0) == IDENTIFY
                && (bb.getShort(4) & 0xFFFF) == FRAME_LENGTH
                && (bb.getShort(6) & 0xFFFF) == DATA_LENGTH;
    }
}
