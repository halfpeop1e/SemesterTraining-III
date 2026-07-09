package com.bjtu.railtransit.vehicle.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 驾驶模式枚举。
 *
 * <p>状态机（仿真原型，不代表真实 CBTC 授权流程）：</p>
 * <ul>
 *   <li>ATO → MANUAL：司机申请人工接管（真实系统需地面授权，本原型简化为非 EMERGENCY 即允许）。</li>
 *   <li>ATO / MANUAL → EMERGENCY：EB 任意时刻直接触发。</li>
 *   <li>EMERGENCY → MANUAL：列车停稳 + 司机复位，不自动恢复 ATO。</li>
 * </ul>
 */
public enum DrivingMode {

    /** ATO 自动驾驶模式，普通制动手柄不生效，EB 仍有效。 */
    ATO("ato"),

    /** 人工驾驶模式，手动制动指令真实参与续算。 */
    MANUAL("manual"),

    /**
     * 紧急制动模式，使用 emergencyBrakeDeceleration 续算到停车。
     * 停稳前不允许恢复 ATO；复位后进入 MANUAL。
     */
    EMERGENCY("emergency");

    private final String code;

    DrivingMode(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }
}
