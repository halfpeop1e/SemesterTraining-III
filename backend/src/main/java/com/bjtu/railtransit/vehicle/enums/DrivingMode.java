package com.bjtu.railtransit.vehicle.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 驾驶模式枚举（本轮新增，用于驾驶员控制闭环）。
 *
 * <p>状态机转换规则（参见任务指挥书）：</p>
 * <ul>
 *   <li>ATO → MANUAL：司机申请人工接管，后端确认允许切换（当前简化条件：非 EMERGENCY 即允许）。
 *       注意：真实系统还需要地面 OCC 授权，本项目作为课程仿真原型，不宣称实现了真实地面授权。</li>
 *   <li>MANUAL → ATO：本轮接口预留但功能禁用（前端按钮 disabled），
 *       若后续启用必须添加"授权假设"注释，不得声称真实地面授权已实现。</li>
 *   <li>ATO / MANUAL → EMERGENCY：司机按 EB，任意时刻直接触发，后端生成 SafetyEvent。</li>
 *   <li>EMERGENCY → MANUAL：列车停稳 + 司机复位确认，不自动恢复 ATO。</li>
 * </ul>
 */
public enum DrivingMode {

    /** ATO 自动驾驶模式：后端自动计算控制策略，司机普通制动手柄不生效（EB 仍有效）。 */
    ATO("ato"),

    /** 人工驾驶模式：司机手动制动指令真实参与续算。 */
    MANUAL("manual"),

    /**
     * 紧急制动模式：使用 emergencyBrakeDeceleration 续算到停车。
     * 停稳前不允许恢复 ATO；复位后进入 MANUAL，不自动回 ATO。
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
