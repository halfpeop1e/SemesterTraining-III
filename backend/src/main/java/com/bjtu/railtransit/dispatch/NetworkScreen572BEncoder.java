package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.vehicle.enums.DrivingMode;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 网络屏 HMI 572B 帧编码器。
 *
 * <p>
 * 协议文档 Table 21-22 定义。总长 572 字节，小端序，magic=0x55AA55AA。
 * 包含约 118 个字段，涵盖速度、加速度、牵引力、网压、门状态、
 * 制动缸压力等车辆 HMI 信息。
 *
 * <p>
 * 本仿真仅建模部分字段（速度/加速度/牵引力/制动缸压力/门状态/模式等），
 * 大量电气量（逆变器、空调、蓄电池等）填 0/默认值，
 * 协议允许「没值显示 0」。
 */
public class NetworkScreen572BEncoder {

    private static final int MAGIC = 0x55AA55AA;
    private static final int TOTAL_LEN = 570;
    private static final int DATA_LEN = 546; // 570 - 24 header

    private NetworkScreen572BEncoder() {
    }

    /**
     * 编码 572B 网络屏帧。
     *
     * @param speedMs          速度 (m/s)
     * @param accelerationMs2  加速度 (m/s²)
     * @param mode             驾驶模式
     * @param direction        方向 (true=下行)
     * @param currStationId    当前站 ID
     * @param nextStationId    下一站 ID
     * @param endStationId     终点站 ID
     * @param nextStationDistM 距下一站距离 (m)
     * @param speedLimitKmh    限速 (km/h)
     * @param tractionForce    牵引力百分比 (0-100)
     * @param brakeForce       制动力百分比 (0-100)
     * @param trainNo          车号
     */
    public static byte[] encode(double speedMs, double accelerationMs2,
            DrivingMode mode, boolean direction,
            int currStationId, int nextStationId,
            int endStationId, double nextStationDistM,
            double speedLimitKmh, double tractionForce,
            double brakeForce, int trainNo) {
        byte[] frame = new byte[TOTAL_LEN];
        ByteBuffer bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);

        // --- Header (24 bytes) ---
        bb.putInt(0, MAGIC);
        bb.putShort(4, (short) TOTAL_LEN);
        bb.putShort(6, (short) DATA_LEN);
        bb.putLong(8, System.currentTimeMillis());
        bb.putShort(16, (short) 0); // verifyType
        bb.putShort(18, (short) 0); // verifyCode
        bb.putShort(20, (short) 0); // protocolID
        bb.putShort(22, (short) 0); // msgID

        // --- Core display fields ---

        // [24-27] speed FLOAT (km/h)
        bb.putFloat(24, (float) (speedMs * 3.6));

        // [28-31] acceleration FLOAT (m/s²)
        bb.putFloat(28, (float) accelerationMs2);

        // [32-33] runDir WORD — 0=上行, 1=下行
        bb.putShort(32, (short) (direction ? 1 : 0));

        // [34] mode BYTE
        bb.put(34, encodeMode(mode));

        // [35-36] currStationID WORD
        bb.putShort(35, (short) currStationId);

        // [37-38] nextStationID WORD
        bb.putShort(37, (short) nextStationId);

        // [39-40] endStationID WORD
        bb.putShort(39, (short) endStationId);

        // [41-44] nextStationDist FLOAT (m)
        bb.putFloat(41, (float) nextStationDistM);

        // [45-46] speedLimit WORD (km/h)
        bb.putShort(45, (short) Math.round(speedLimitKmh));

        // [47-48] tractionForce WORD (N or %)
        bb.putShort(47, (short) Math.round(tractionForce));

        // [49-50] brakeForce WORD
        bb.putShort(49, (short) Math.round(brakeForce));

        // [51-570] 大量电气量填 0（逆变器/空调/蓄电池/网络设备状态等）
        // [571-572] trainNo WORD
        bb.putShort(571, (short) trainNo);

        return frame;
    }

    private static byte encodeMode(DrivingMode mode) {
        if (mode == null)
            return 0x04; // RM
        return switch (mode) {
            case ATO -> (byte) 0x01;
            case MANUAL -> (byte) 0x04;
            case EMERGENCY -> (byte) 0xFF;
        };
    }
}
