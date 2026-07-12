package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.signal.domain.SignalAspect;
import com.bjtu.railtransit.vehicle.enums.DrivingMode;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;

/**
 * 信号屏 MMI 帧编码器。
 *
 * <p>
 * 基于《任务4：实时数据库与司机控制台系统通讯程序开发》C++ 参考实现：
 * 总长 62 字节，小端序，magic=0x55AA55AA。
 *
 * <p>
 * 帧布局（与老师 C++ 代码一致）：
 * 
 * <pre>
 *   [0-23]  Header: magic(4) + totalLen(2) + dataLen(2) + timestamp(8) + verify(4) + protocolID(2) + msgID(2)
 *   [24-35] Time: year/month/day/hour/minute/second (WORD × 6)
 *   [36-41] Station: currStationID/nextStationID/endStationID/CMState/MMState/CTCState (BYTE × 6)
 *   [42]    _nRunDir: 运行方向 (0=上行, 1=下行)
 *   [43]    _nReserve: 预留
 *   [44-47] _nSpeed FLOAT (m/s)
 *   [48-51] _fAcceleration FLOAT (m/s²)
 *   [52-53] _nPullSwitch WORD (牵引切除)
 *   [54-55] _fSpeedLimit WORD (km/h)
 *   [56]    _nMode BYTE
 *   [57]    _nPullState BYTE
 *   [58]    _nBrakeState BYTE
 *   [59]    _nUrgencyStopState BYTE
 *   [60]    _nEventID BYTE
 *   [61]    _nSigState BYTE
 * </pre>
 */
public class SignalScreen66BEncoder {

    private static final int MAGIC = 0x55AA55AA;
    private static final int TOTAL_LEN = 62;
    private static final int DATA_LEN = 38; // 62 - 24

    private SignalScreen66BEncoder() {
    }

    /**
     * 编码信号屏帧。
     *
     * @param speedMs         当前速度 (m/s)
     * @param accelerationMs2 加速度 (m/s²)
     * @param speedLimitKmh   限速 (km/h)
     * @param mode            驾驶模式
     * @param currStationId   当前站 ID (0-16)
     * @param nextStationId   下一站 ID
     * @param endStationId    终点站 ID
     * @param isDown          方向 (true=下行)
     * @param signalAspect    前方信号机状态
     * @param tractionActive  牵引状态
     * @param brakeActive     制动状态
     * @param emergencyBrake  紧急制动
     */
    public static byte[] encode(double speedMs, double accelerationMs2, double speedLimitKmh,
            DrivingMode mode, int currStationId, int nextStationId,
            int endStationId, boolean isDown,
            SignalAspect signalAspect,
            boolean tractionActive, boolean brakeActive,
            boolean emergencyBrake) {
        byte[] frame = new byte[TOTAL_LEN];
        ByteBuffer bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);

        // --- Header (24 bytes) ---
        bb.putInt(0, MAGIC); // [0-3] magic
        bb.putShort(4, (short) TOTAL_LEN); // [4-5] totalLen
        bb.putShort(6, (short) DATA_LEN); // [6-7] dataLen
        bb.putLong(8, System.currentTimeMillis()); // [8-15] timestamp (ms)
        bb.putShort(16, (short) 0); // [16-17] verifyType
        bb.putShort(18, (short) 0); // [18-19] verifyCode
        bb.putShort(20, (short) 0); // [20-21] protocolID
        bb.putShort(22, (short) 0); // [22-23] msgID

        // --- Time + Station (18 bytes) ---
        LocalDateTime now = LocalDateTime.now();
        bb.putShort(24, (short) now.getYear()); // [24-25] year
        bb.putShort(26, (short) now.getMonthValue()); // [26-27] month
        bb.putShort(28, (short) now.getDayOfMonth()); // [28-29] day
        bb.putShort(30, (short) now.getHour()); // [30-31] hour
        bb.putShort(32, (short) now.getMinute()); // [32-33] minute
        bb.putShort(34, (short) now.getSecond()); // [34-35] second
        frame[36] = (byte) currStationId; // [36] currStationID
        frame[37] = (byte) nextStationId; // [37] nextStationID
        frame[38] = (byte) endStationId; // [38] endStationID
        frame[39] = 0; // [39] CMState
        frame[40] = 0; // [40] MMState
        frame[41] = 0; // [41] CTCState

        // --- Direction (2 bytes) ---
        frame[42] = (byte) (isDown ? 1 : 0); // [42] _nRunDir (0=上行,1=下行)
        frame[43] = 0; // [43] _nReserve

        // --- Data Area (18 bytes, offset 44-61) ---
        bb.putFloat(44, (float) speedMs); // [44-47] speed FLOAT (m/s)
        bb.putFloat(48, (float) accelerationMs2); // [48-51] acceleration FLOAT
        bb.putShort(52, (short) 0); // [52-53] pullSwitch WORD
        bb.putShort(54, (short) Math.round(speedLimitKmh)); // [54-55] speedLimit WORD
        frame[56] = encodeMode(mode); // [56] mode BYTE
        frame[57] = (byte) (tractionActive ? 1 : 0); // [57] pullState
        frame[58] = (byte) (brakeActive ? 1 : 0); // [58] brakeState
        frame[59] = (byte) (emergencyBrake ? 1 : 0); // [59] urgencyStopState
        frame[60] = 0; // [60] eventID
        frame[61] = (byte) encodeSigState(signalAspect); // [61] sigState

        return frame;
    }

    private static byte encodeMode(DrivingMode mode) {
        if (mode == null)
            return 0x04; // RM default
        return switch (mode) {
            case ATO -> (byte) 0x01;
            case MANUAL -> (byte) 0x04;
            case EMERGENCY -> (byte) 0xFF;
        };
    }

    private static int encodeSigState(SignalAspect aspect) {
        if (aspect == null)
            return 0x01;
        return aspect.toDriverConsoleScreen();
    }
}
