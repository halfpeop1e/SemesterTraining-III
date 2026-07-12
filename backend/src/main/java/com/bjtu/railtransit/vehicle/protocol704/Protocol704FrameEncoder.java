package com.bjtu.railtransit.vehicle.protocol704;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;

/**
 * 上位机→PLC 回写出站帧编码器。
 *
 * <p>支持两种格式：
 * <ul>
 *   <li>documented-26：26 字节，dataLen=2（老师协议通信总表明确定义）</li>
 *   <li>capture-variant-28：28 字节，dataLen=4（朋友系统真实采集记录）</li>
 * </ul>
 *
 * <p>帧布局与协议文档 Table 20 对齐：magic=0x55AA55AA，小端序。
 * byte24 指示灯位、byte25 模式位与入站 46B 定义一致，
 * 唯独 byte24.bit4 在出站帧中定义为「开门灯状态」（入站为预留）。
 */
public class Protocol704FrameEncoder {

    /** 出站帧 magic：0x55 AA 55 AA（与入站 0xAA 55 AA 55 字节序颠倒）。 */
    private static final int OUTBOUND_MAGIC = 0x55AA55AA;

    private Protocol704FrameEncoder() {}

    /**
     * 编码 26B 文档格式回写帧（dataLen=2，仅灯态+模式，不含速度 WORD）。
     *
     * @param lightsByte byte24 指示灯位（按 Table 20 位定义）
     * @param modeByte   byte25 模式位（按 Table 20 位定义）
     */
    public static byte[] encode26B(byte lightsByte, byte modeByte) {
        byte[] frame = new byte[26];
        ByteBuffer bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);

        bb.putInt(0, OUTBOUND_MAGIC);           // [0-3]   magic
        bb.putShort(4, (short) 26);             // [4-5]   totalLen
        bb.putShort(6, (short) 2);              // [6-7]   dataLen

        writeTimestamp(bb, 8);                   // [8-19]  timestamp

        bb.putShort(20, (short) 0);              // [20-21] verifyType
        bb.putShort(22, (short) 0);              // [22-23] verifyCode

        frame[24] = lightsByte;                  // [24]    指示灯
        frame[25] = modeByte;                    // [25]    模式

        return frame;
    }

    /**
     * 编码 28B 候选格式回写帧（dataLen=4，含速度 WORD 于 offset 26-27）。
     *
     * @param lightsByte byte24 指示灯位
     * @param modeByte   byte25 模式位
     * @param speedRaw   车辆速度 WORD（单位待现场确认，当前约定 0.1 km/h）
     */
    public static byte[] encode28B(byte lightsByte, byte modeByte, int speedRaw) {
        byte[] frame = new byte[28];
        ByteBuffer bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);

        bb.putInt(0, OUTBOUND_MAGIC);           // [0-3]   magic
        bb.putShort(4, (short) 28);             // [4-5]   totalLen
        bb.putShort(6, (short) 4);              // [6-7]   dataLen

        writeTimestamp(bb, 8);                   // [8-19]  timestamp

        bb.putShort(20, (short) 0);              // [20-21] verifyType
        bb.putShort(22, (short) 0);              // [22-23] verifyCode

        frame[24] = lightsByte;                  // [24]    指示灯
        frame[25] = modeByte;                    // [25]    模式
        bb.putShort(26, (short) speedRaw);       // [26-27] 车辆速度 WORD

        return frame;
    }

    /**
     * 构建 byte24 指示灯字节。
     *
     * @param doorsClosed       门关好指示灯
     * @param highBreakerOn     高断合指示灯
     * @param brakeReleaseBad   制动缓解不良指示灯
     * @param doorOpenLight     开门灯状态（出站特有）
     * @param networkFault      网络故障指示灯
     * @param arModeAvailable   具备自动折返模式
     */
    public static byte buildLightsByte(boolean doorsClosed, boolean highBreakerOn,
                                       boolean brakeReleaseBad, boolean doorOpenLight,
                                       boolean networkFault, boolean arModeAvailable) {
        int b = 0;
        if (doorsClosed)     b |= 0x20;  // bit5
        if (highBreakerOn)   b |= 0x02;  // bit1
        if (brakeReleaseBad) b |= 0x04;  // bit2
        if (doorOpenLight)   b |= 0x10;  // bit4 (出站特有)
        if (networkFault)    b |= 0x40;  // bit6
        if (arModeAvailable) b |= 0x80;  // bit7
        return (byte) b;
    }

    /**
     * 构建 byte25 模式字节。
     *
     * @param atoAvailable        具备 ATO 模式
     * @param washModeEntered     进入洗车模式
     * @param atoActive           激活 ATO 模式
     * @param arActive            激活自动折返模式
     */
    public static byte buildModeByte(boolean atoAvailable, boolean washModeEntered,
                                     boolean atoActive, boolean arActive) {
        int b = 0;
        if (atoAvailable)    b |= 0x01;  // bit0
        if (washModeEntered) b |= 0x02;  // bit1
        if (atoActive)       b |= 0x04;  // bit2
        if (arActive)        b |= 0x08;  // bit3
        return (byte) b;
    }

    private static void writeTimestamp(ByteBuffer bb, int offset) {
        LocalDateTime now = LocalDateTime.now();
        bb.putShort(offset,      (short) now.getYear());
        bb.putShort(offset + 2,  (short) now.getMonthValue());
        bb.putShort(offset + 4,  (short) now.getDayOfMonth());
        bb.putShort(offset + 6,  (short) now.getHour());
        bb.putShort(offset + 8,  (short) now.getMinute());
        bb.putShort(offset + 10, (short) now.getSecond());
    }
}
