package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.domain.model.TrainState;

/**
 * 实验室接口协议适配层 —— 将内部 DTO 映射为协议字段。
 *
 * 职责:
 *   1. 将内部 TrainRuntimeState 转换为实验室协议中的列车速度、累计走行距离、方向、载重、紧急制动等字段
 *   2. 将内部 TrainCommand 转换为牵引、制动、紧急制动、限速、扣车、发车等协议字段
 *   3. 处理单位转换:
 *      - speedKmh / speedMps → cm/s
 *      - positionMeters → cm
 *      - UP / DOWN → 0x55 / 0xaa
 *   4. 大小端、CRC、序列号、时效性检查 (TODO, 不在当前阶段强制完成)
 *
 * 参考: 《轨交多系统平台接口协议汇总20260630.docx》
 *
 * 当前阶段: 接口预留, 不实现完整 UDP/TCP 二进制报文。
 * 后续由信号/车辆/司机台/视觉模块分别实现具体适配器。
 */
public interface ProtocolAdapter {

    /** 方向编码: 上行 */
    byte DIR_UP = (byte) 0x55;
    /** 方向编码: 下行 */
    byte DIR_DOWN = (byte) 0xAA;

    /**
     * 将内部 TrainState 转换为协议格式的列车状态字节数组。
     * TODO: 实现完整的协议编码
     */
    byte[] encodeTrainState(TrainState state);

    /**
     * 将协议字节流解析为内部 TrainCommand。
     * TODO: 实现完整的协议解码
     */
    Object decodeCommand(byte[] raw);

    /**
     * 方向转换: UP → 0x55, DOWN → 0xAA
     */
    default byte directionToProtocol(String direction) {
        return "DOWN".equals(direction) ? DIR_DOWN : DIR_UP;
    }

    /**
     * 单位转换: m/s → cm/s (协议用 cm/s)
     */
    default int speedMsToCms(double speedMs) {
        return (int) Math.round(speedMs * 100.0);
    }

    /**
     * 单位转换: 米 → 厘米 (协议用 cm)
     */
    default long metersToCm(double meters) {
        return Math.round(meters * 100.0);
    }
}
