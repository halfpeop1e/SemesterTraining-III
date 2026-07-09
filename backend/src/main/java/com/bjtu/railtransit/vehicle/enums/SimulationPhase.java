package com.bjtu.railtransit.vehicle.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 列车运行阶段。
 */
public enum SimulationPhase {

    /** 牵引加速阶段。 */
    TRACTION("traction"),

    /** 惰行/匀速阶段。 */
    COAST("coast"),

    /** 制动阶段。 */
    BRAKING("braking"),

    /** 已停车（单站停稳）。 */
    STOPPED("stopped"),

    /**
     * 站内驻留阶段（多站连续仿真）。
     * velocity=0, acceleration=0, position 不变，time 按 dtPerFrame 递增。
     */
    DWELL("dwell");

    private final String code;

    SimulationPhase(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    @Override
    public String toString() {
        return code;
    }
}
