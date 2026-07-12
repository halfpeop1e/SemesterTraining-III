package com.bjtu.railtransit.vehicle.protocol704;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;

/**
 * local-v1 PLC output frame encoder (上位机→PLC 26B); not validated with real PLC.
 *
 * <p>Per 轨交多系统平台接口协议汇总 Table 18/20:
 * frame header 24 bytes + data area 2 bytes = 26 bytes total.
 * Wire format: little-endian, identify bytes {@code 55 AA 55 AA}.</p>
 */
public final class Protocol704OutputEncoder {

    /** Total frame length per protocol doc Table 18. */
    public static final int FRAME_LENGTH = 26;
    /** Data area length per protocol doc Table 18. */
    public static final int DATA_LENGTH = 2;

    private Protocol704OutputEncoder() {
    }

    /**
     * Encode the PLC output frame (26 bytes).
     *
     * @param state     current vehicle state (nullable; defaults to safe values)
     * @param timestamp timestamp for the frame (nullable; defaults to now)
     * @return 26-byte frame ready for TCP write
     */
    public static byte[] encodeOutputFrame(RealtimeVehicleState state, LocalDateTime timestamp) {
        RealtimeVehicleState s = state != null ? state : new RealtimeVehicleState();
        LocalDateTime ts = timestamp != null ? timestamp : LocalDateTime.now();

        byte[] frame = new byte[FRAME_LENGTH];
        ByteBuffer bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);

        // ── frame header (bytes 0-23) per Table 20 ──
        // 0-3: _uIdentify = 55 AA 55 AA (wire); putInt LE of 0xAA55AA55 produces 55 AA 55 AA
        bb.putInt(0, 0xAA55AA55);
        // 4-5: _uTotalLen = 26 (0x001A)  →  wire 1A 00
        bb.putShort(4, (short) FRAME_LENGTH);
        // 6-7: _uDataLen = 2 (0x0002)  →  wire 02 00
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

        // ── data area byte 24 (bit fields) per Table 20 ──
        int b24 = 0;
        // bit0: reserved
        // bit1: 高断合指示灯 1=亮 (assume normal operation → lit)
        b24 |= (1 << 1);
        // bit2: 制动缓解不良指示灯 0=灭 (assume normal → off)
        // bit3: reserved
        // bit4: 开门灯状态 1=亮 when doors are NOT closed
        if (!s.isDoorsClosed()) {
            b24 |= (1 << 4);
        }
        // bit5: 门关好指示灯 1=亮
        if (s.isDoorsClosed()) {
            b24 |= (1 << 5);
        }
        // bit6: 网络故障指示灯 1=亮
        if (s.isNetworkFault()) {
            b24 |= (1 << 6);
        }
        // bit7: 具备自动折返模式标志 (local-v1: not implemented → 0)
        frame[24] = (byte) b24;

        // ── data area byte 25 (bit fields) per Table 20 ──
        int b25 = 0;
        // bit0: 具备ATO模式标志 1=具备 (local-v1: always report capable)
        b25 |= (1 << 0);
        // bit1: 进入洗车模式标志 (local-v1: not implemented → 0)
        // bit2: 激活ATO模式标志 1=激活
        String mode = s.getMode();
        if (mode != null && mode.equalsIgnoreCase("ATO")) {
            b25 |= (1 << 2);
        }
        // bit3: 激活自动折返模式标志 (local-v1: not implemented → 0)
        // bit4-7: reserved
        frame[25] = (byte) b25;

        return frame;
    }
}
