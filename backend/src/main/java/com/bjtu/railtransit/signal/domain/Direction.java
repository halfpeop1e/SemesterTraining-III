package com.bjtu.railtransit.signal.domain;

/**
 * 行车方向。MA 模块为方向定义权威（协议 §4「线路的上行/下行方向由信号系统定义」）。
 *
 * 与司机台信号屏对齐：司机台方向编码为 0=上行 / 1=下行（见协议 司机台 章节）。
 * 提供 {@link #fromDriverConsole(int)} 做编解码；无法识别的方向一律归为 {@link #INVALID}，
 * 由 compute 以 fail-safe 收紧处理，绝不默认当某方向放行。
 */
public enum Direction {
    UP, DOWN, INVALID;

    /**
     * 司机台信号屏方向编码 → Direction。
     * 0=上行，1=下行，其余（含非法/未定义值）→ {@link #INVALID}（fail-safe）。
     */
    public static Direction fromDriverConsole(int code) {
        switch (code) {
            case 0:  return UP;
            case 1:  return DOWN;
            default: return INVALID;
        }
    }
}
