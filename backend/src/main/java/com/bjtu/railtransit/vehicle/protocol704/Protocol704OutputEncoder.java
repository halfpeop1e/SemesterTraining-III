package com.bjtu.railtransit.vehicle.protocol704;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;

/**
 * local-v1 PLC output frame encoder (上位机→PLC 28B).
 *
 * <p>
 * Per 2026-07-12 laboratory capture: the friend system writes 28B with
 * totalLen=28 (0x1C), dataLen=4. Frame layout:
 * header 24 bytes + byte24 lamp + byte25 mode + byte26-27 speed WORD.
 * Wire format: little-endian, identify bytes {@code 55 AA 55 AA}.
 * </p>
 */
public final class Protocol704OutputEncoder {

    /** Total frame length per laboratory capture (28B). */
    public static final int FRAME_LENGTH = 28;
    /** Data area length (lamp + mode + speed = 4 bytes). */
    public static final int DATA_LENGTH = 4;

    private Protocol704OutputEncoder() {
    }

    /**
     * Encode the PLC output frame (28 bytes).
     *
     * @param state     current vehicle state (nullable; defaults to safe values)
     * @param timestamp timestamp for the frame (nullable; defaults to now)
     * @return 28-byte frame ready for TCP write
     */
    public static byte[] encodeOutputFrame(RealtimeVehicleState state, LocalDateTime timestamp) {
        RealtimeVehicleState s = state != null ? state : new RealtimeVehicleState();
        LocalDateTime ts = timestamp != null ? timestamp : LocalDateTime.now();

        byte[] frame = new byte[FRAME_LENGTH];
        ByteBuffer bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);

        // ── frame header (bytes 0-23) ──
        // 0-3: _uIdentify = 55 AA 55 AA (wire); putInt LE of 0xAA55AA55 produces 55 AA
        // 55 AA
        bb.putInt(0, 0xAA55AA55);
        // 4-5: _uTotalLen = 28 (0x001C) → wire 1C 00
        bb.putShort(4, (short) FRAME_LENGTH);
        // 6-7: _uDataLen = 4 (0x0004) → wire 04 00
        bb.putShort(6, (short) DATA_LENGTH);
        // 8-9: _uYear
        bb.putShort(8, (short) ts.getYear());
        // 10-11: _uMonth
        bb.putShort(10, (short) ts.getMonthValue());
        // 12-13: _uDay
        bb.putShort(12, (short) ts.getDayOfMonth());
        // 14-15: _uHour
        bb.putShort(14, (short) ts.getHour());
        // 16-17: _uMinute
        bb.putShort(16, (short) ts.getMinute());
        // 18-19: _uSecond
        bb.putShort(18, (short) ts.getSecond());
        // 20-21: _uVerifyType
        bb.putShort(20, (short) 0);
        // 22-23: _uVerifyCode
        bb.putShort(22, (short) 0);

        // ── data area (bytes 24-27) ──
        PlcLampEncoder.LampInputs lamps = PlcLampEncoder.LampInputs.fromVehicleState(s);
        frame[24] = (byte) PlcLampEncoder.encodeByte24(lamps);
        frame[25] = (byte) PlcLampEncoder.encodeByte25(lamps);
        // 26-27: vehicle speed in km/h (unsigned 16-bit WORD, little-endian)
        int speedKmh = clampU16((int) Math.round(Math.max(0.0, s.getVelocityMs()) * 3.6));
        bb.putShort(26, (short) speedKmh);

        return frame;
    }

    private static int clampU16(int value) {
        return Math.max(0, Math.min(0xFFFF, value));
    }
}
