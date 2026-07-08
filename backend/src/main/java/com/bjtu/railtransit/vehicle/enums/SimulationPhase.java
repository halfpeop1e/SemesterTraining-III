package com.bjtu.railtransit.vehicle.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 列车运行阶段。
 *
 * <p>阶段 1A 仅覆盖最简单的纵向运动过程：牵引加速到限速 -> 惰行/匀速 -> 制动至停车。
 * 更真实的牵引/惰行/制动切换策略留给阶段 2。</p>
 */
public enum SimulationPhase {

    /** 牵引加速阶段。 */
    TRACTION("traction"),

    /** 惰行/匀速阶段。 */
    COAST("coast"),

    /** 制动阶段。 */
    BRAKING("braking"),

    /** 已停车。 */
    STOPPED("stopped");

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
