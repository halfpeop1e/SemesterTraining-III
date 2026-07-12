package com.bjtu.railtransit.vehicle.protocol704;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * local-v1 MMI (信号屏) 66B output frame encoder; not validated with real MMI hardware.
 *
 * <p>Per 轨交多系统平台接口协议汇总 Table 25: TCP to 192.168.100.122:9999 (or 192.168.100.121 — see 704对接可能出错的点),
 * total 66 bytes, little-endian wire format, identify bytes {@code 55 AA 55 AA}.</p>
 *
 * <p>Note: the protocol doc Table 25 contains known offset errors (e.g. _nSpeed listed at offset 42
 * conflicting with _nRunDir). This implementation uses corrected sequential offsets that sum to 66 bytes.
 * Marked local-v1; field offsets must be verified against real MMI before production.</p>
 */
public final class Protocol704MmiEncoder {

    /** Total frame length per protocol doc Table 25. */
    public static final int FRAME_LENGTH = 66;
    /** Data area length = FRAME_LENGTH - header (36). */
    public static final int DATA_LENGTH = 30;

    private Protocol704MmiEncoder() {
    }

    /**
     * Encode the MMI output frame (66 bytes, 上位机→信号屏).
     *
     * @param state     current vehicle state (nullable)
     * @param timestamp timestamp for the frame (nullable; defaults to now)
     * @return 66-byte frame ready for TCP write
     */
    public static byte[] encodeMmiFrame(RealtimeVehicleState state, LocalDateTime timestamp) {
        RealtimeVehicleState s = state != null ? state : new RealtimeVehicleState();
        LocalDateTime ts = timestamp != null ? timestamp : LocalDateTime.now();

        byte[] frame = new byte[FRAME_LENGTH];
        ByteBuffer bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);

        // ── frame header (bytes 0-35, 36 bytes) per Table 25 ──
        // 0-3: _uIdentify = 55 AA 55 AA (wire); putInt LE of 0xAA55AA55
        bb.putInt(0, 0xAA55AA55);
        // 4-5: _uTotalLen
        bb.putShort(4, (short) FRAME_LENGTH);
        // 6-7: _uDataLen
        bb.putShort(6, (short) DATA_LENGTH);
        // 8-15: _timestamp (ms epoch, DDWORD/8 bytes)
        long epochMs = ts.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        bb.putLong(8, epochMs);
        // 16-17: _uVerifyType
        bb.putShort(16, (short) 0);
        // 18-19: _uVerifyCode
        bb.putShort(18, (short) 0);
        // 20-21: _uProtocolID
        bb.putShort(20, (short) 0);
        // 22-23: _uMsgID
        bb.putShort(22, (short) 0);
        // 24-25: _hYear
        bb.putShort(24, (short) ts.getYear());
        // 26-27: _hMonth
        bb.putShort(26, (short) ts.getMonthValue());
        // 28-29: _hDay
        bb.putShort(28, (short) ts.getDayOfMonth());
        // 30-31: _hHour
        bb.putShort(30, (short) ts.getHour());
        // 32-33: _hMinute
        bb.putShort(32, (short) ts.getMinute());
        // 34-35: _hSec
        bb.putShort(34, (short) ts.getSecond());

        // ── data area (bytes 36-65, 30 bytes) — corrected sequential offsets ──
        // 36: _nCurrStationID (0-16, local-v1: fixed 1)
        frame[36] = (byte) 1;
        // 37: _nNextStationID
        frame[37] = (byte) 2;
        // 38: _nEndStationID
        frame[38] = (byte) 16;
        // 39: _nCMState (-1=非法,0=关闭,1=开启; local-v1: 1)
        frame[39] = (byte) 1;
        // 40: _nMMState
        frame[40] = (byte) 1;
        // 41: _nCTCState
        frame[41] = (byte) 1;
        // 42: _nRunDir (-1=非法,0=上行,1=下行; local-v1: from state.direction)
        frame[42] = (byte) resolveMmiRunDir(s);
        // 43: _nReserve (not in final 66B layout — omitted to fit 66 bytes)
        // 44-47: _nSpeed FLOAT LE (corrected from doc offset 42 to 44)
        bb.putFloat(44, (float) s.getVelocityMs());
        // 48-51: _fAcceleration FLOAT LE
        bb.putFloat(48, (float) s.getAccelerationMs2());
        // 52-53: _nPullSwitch WORD (traction cutoff bitmask, local-v1: 0)
        bb.putShort(52, (short) 0);
        // 54-55: _fSpeedLimit WORD (local-v1: 80 km/h = 22 m/s approx)
        bb.putShort(54, (short) 80);
        // 56: _nMode (DTO=0, ATO=1, AR=2, SM=3, RM=4; local-v1: infer from state)
        frame[56] = (byte) resolveMmiMode(s);
        // 57: _nPullState (0=无,1=牵引; local-v1: from lastCommand)
        frame[57] = (byte) resolvePullState(s);
        // 58: _nBrakeState (0=无,1=制动; local-v1: from lastCommand)
        frame[58] = (byte) resolveBrakeState(s);
        // 59: _nUrgencyStopState (0=无,1=紧急制动)
        frame[59] = (byte) resolveEmergencyStop(s);
        // 60-61: skipped _nEventID + _nSigState to fit 66B (2 bytes saved)
        // 62-63: _nTrainNo WORD (local-v1: 1)
        bb.putShort(62, (short) 1);
        // 64-65: _fNextStationDist — truncated to WORD to fit 66B
        bb.putShort(64, (short) 0);

        return frame;
    }

    // ── helper resolvers (local-v1) ──

    static int resolveMmiRunDir(RealtimeVehicleState s) {
        String dir = s.getDirection();
        if ("DOWN".equals(dir)) return 1;
        return 0;
    }

    static int resolveMmiMode(RealtimeVehicleState s) {
        String mode = s.getMode();
        if (mode == null) return 4; // RM default
        return switch (mode.toUpperCase()) {
            case "DTO" -> 0;
            case "ATO" -> 1;
            case "AR" -> 2;
            case "SM" -> 3;
            default -> 4; // RM
        };
    }

    static int resolvePullState(RealtimeVehicleState s) {
        String cmd = s.getLastCommand();
        return (cmd != null && cmd.toLowerCase().contains("traction")) ? 1 : 0;
    }

    static int resolveBrakeState(RealtimeVehicleState s) {
        String cmd = s.getLastCommand();
        return (cmd != null && cmd.toLowerCase().contains("brake")) ? 1 : 0;
    }

    static int resolveEmergencyStop(RealtimeVehicleState s) {
        String mode = s.getMode();
        String cmd = s.getLastCommand();
        if ((mode != null && mode.toLowerCase().contains("emergency"))
                || (cmd != null && cmd.toLowerCase().contains("emergency"))) {
            return 1;
        }
        return 0;
    }
}
