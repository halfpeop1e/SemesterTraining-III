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

    /** 已停车（单区间终点停车或多站途中站停车）。 */
    STOPPED("stopped"),

    /**
     * 站内驻留阶段（多站连续仿真新增）。
     *
     * <p>velocity=0, acceleration=0, position 不变，time 按 dtPerFrame 增长。
     * 驻留结束后自动进入下一区间的 TRACTION 阶段。</p>
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
