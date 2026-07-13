package com.bjtu.railtransit.vehicle.protocol704;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * local-v1 HMI (网络屏) 572B output frame encoder; not validated with real HMI.
 *
 * <p>Per 轨交多系统平台接口协议汇总 Table 22: TCP to 192.168.100.121:8888 (or 192.168.100.122 — see 704对接可能出错的点),
 * total 572 bytes, little-endian wire format, identify bytes {@code 55 AA 55 AA}.</p>
 *
 * <p>local-v1 fills fields that have available data; remaining fields are zero.
 * Full 119-row field table requires future per-car telemetry not yet available.</p>
 */
public final class Protocol704HmiEncoder {

    /** Real 8888 TCP payload confirmed by the laboratory discrepancy note. */
    public static final int FRAME_LENGTH = 570;
    /** Header size: identify(4)+totalLen(2)+dataLen(2)+timestamp(8)+verify(2)+verifyCode(2)+protocolID(2)+msgID(2) = 24 */
    public static final int HEADER_LENGTH = 24;

    private Protocol704HmiEncoder() {
    }

    public static byte[] encodeHmiFrame(RealtimeVehicleState state, LocalDateTime timestamp) {
        RealtimeVehicleState s = state != null ? state : new RealtimeVehicleState();
        LocalDateTime ts = timestamp != null ? timestamp : LocalDateTime.now();

        byte[] frame = new byte[FRAME_LENGTH];
        ByteBuffer bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);

        // ── frame header (bytes 0-23) per Table 22 ──
        // 0-3: _uIdentify wire 55 AA 55 AA
        bb.putInt(0, 0xAA55AA55);
        // 4-5: _uTotalLen = 570
        bb.putShort(4, (short) FRAME_LENGTH);
        // 6-7: _uDataLen = 570 - 24 = 546
        bb.putShort(6, (short) (FRAME_LENGTH - HEADER_LENGTH));
        // 8-15: _timestamp DDWORD (ms epoch)
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

        // ── data area (bytes 24-571) per Table 22 ──
        // 24-35: year/month/day/hour/minute/second WORD
        bb.putShort(24, (short) ts.getYear());
        bb.putShort(26, (short) ts.getMonthValue());
        bb.putShort(28, (short) ts.getDayOfMonth());
        bb.putShort(30, (short) ts.getHour());
        bb.putShort(32, (short) ts.getMinute());
        bb.putShort(34, (short) ts.getSecond());

        // 36: _nCurrStationID (local-v1: fixed 1)
        frame[36] = (byte) 1;
        // 37: _nNextStationID
        frame[37] = (byte) 2;
        // 38: _nEndStationID
        frame[38] = (byte) 16;
        // 39: _nPowerState (车间电源: 0=无)
        frame[39] = (byte) 0;

        // 40-43: _nSpeed FLOAT LE
        bb.putFloat(40, (float) s.getVelocityMs());
        // 44-47: _fAcceleration FLOAT LE
        bb.putFloat(44, (float) s.getAccelerationMs2());
        // 48-49: _nPowerPull WORD (总牵引力, local-v1: 0)
        bb.putShort(48, (short) 0);
        // 50-51: _nNetPressure WORD (网压, local-v1: 1500V)
        bb.putShort(50, (short) 1500);
        // 52-53: _fSpeedLimit WORD (限速 km/h, local-v1: 80)
        bb.putShort(52, (short) 80);
        // 54: _nLevelPos (0=惰行,1=牵引,2=制动,3=EB)
        frame[54] = (byte) resolveLevelPos(s);
        // 55: _nRunMode (低4位:0=手动,1=ATO; 高4位:0=MM,1=AM,2=AA)
        frame[55] = (byte) resolveHmiRunMode(s);
        // 56-57: _nMasterV WORD (母线电压, local-v1: 1500)
        bb.putShort(56, (short) 1500);
        // 58: _nRunDir (0=无,1=上行,2=下行)
        frame[58] = (byte) resolveRunDir(s);
        // 59: _nDriverRoomState (低4=tc1激活, local-v1: 0x11 both activated)
        frame[59] = (byte) 0x11;
        // 60-83: _nDorrState 24 bytes (6 cars × 4 bytes, local-v1: all doors closed=0)
        // zero-filled
        // 84-89: _nStopPosState 6 bytes (brake+parking per car)
        // zero-filled
        // 108-113: _nPullSwitch 6 bytes (每车4bit: 低4位=牵引切除状态,高4位=标记, local-v1)
        boolean[] cutMask = s.getTractionCutMask();
        for (int i = 0; i < 6; i++) {
            boolean cut = cutMask != null && i < cutMask.length && cutMask[i];
            frame[108 + i] = cut ? (byte) 0x11 : (byte) 0x00;
        }
        // 168-173: _nUsageRate 6 bytes (每车载客率百分比, local-v1)
        double[] perCarLoad = s.getPerCarLoadKg();
        for (int i = 0; i < 6; i++) {
            double loadKg = perCarLoad != null && i < perCarLoad.length ? perCarLoad[i] : 0.0;
            double rate = Math.min(100, Math.max(0, (loadKg / 20000.0) * 100.0));
            frame[168 + i] = (byte) Math.round(rate);
        }
        // … remaining fields (90-571) zero-filled
        // 570-571: _nTrainNo WORD (local-v1: 1)
        bb.putShort(568, (short) 1);

        return frame;
    }

    static int resolveRunDir(RealtimeVehicleState s) {
        String dir = s.getDirection();
        if ("DOWN".equals(dir)) return 2;
        return 1;
    }

    static int resolveLevelPos(RealtimeVehicleState s) {
        String mode = s.getMode() != null ? s.getMode() : "";
        String cmd = s.getLastCommand() != null ? s.getLastCommand() : "";
        String modeLower = mode.toLowerCase();
        String cmdLower = cmd.toLowerCase();
        if (modeLower.contains("emergency") || cmdLower.contains("emergency")) {
            return 3;
        }
        if (cmdLower.contains("traction")) {
            return 1;
        }
        if (cmdLower.contains("brake")) {
            return 2;
        }
        return 0;
    }

    /** Resolve HMI run mode byte: low 4 bits = 0 manual / 1 ATO; high 4 bits = 0 MM / 1 AM / 2 AA. */
    static int resolveHmiRunMode(RealtimeVehicleState s) {
        String mode = s.getMode() != null ? s.getMode().toUpperCase() : "MANUAL";
        int low = mode.contains("ATO") || mode.contains("DTO") ? 1 : 0;
        int high = 1; // AM default
        return (high << 4) | low;
    }
}
