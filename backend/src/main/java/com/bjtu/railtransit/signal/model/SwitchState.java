package com.bjtu.railtransit.signal.model;

/**
 * 道岔状态（与司机台协议 3.3.2 对齐）。
 * NORMAL=0x01 定位, REVERSE=0x02 反位, FAIL=0x04 四开/失表。
 * MA 规则：state == null || state != NORMAL → 道岔前截断（含 REVERSE/FAIL）。
 */
public enum SwitchState {
    NORMAL, REVERSE, FAIL;

    public static SwitchState fromProtocol(int code) {
        return switch (code & 0xFF) {
            case 0x01 -> NORMAL;
            case 0x02 -> REVERSE;
            case 0x04 -> FAIL;
            default -> null;
        };
    }

    public int toProtocol() {
        return switch (this) {
            case NORMAL -> 0x01;
            case REVERSE -> 0x02;
            case FAIL -> 0x04;
        };
    }
}
