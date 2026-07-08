package com.bjtu.railtransit.vehicle.dto;

import com.bjtu.railtransit.vehicle.enums.DrivingMode;

/**
 * 驾驶员控制续算请求（本轮新增，对应 POST /api/vehicle/simulation/control）。
 *
 * <p>前端播放到某一帧时，司机按下手动制动 / EB / 模式切换按钮，前端把当前帧状态和控制指令
 * 一起发给后端，后端从 currentState 开始基于当前区间剩余距离重新续算后续 states。</p>
 *
 * <p>语义约束（均在 service 层校验）：</p>
 * <ul>
 *   <li>ATO 模式下普通 brake 指令被忽略，返回 REFUSED 状态；EB 任意时刻生效。</li>
 *   <li>MANUAL 模式下 brake 指令真实参与续算；targetDecel 不得超过 normalBrakeDeceleration。</li>
 *   <li>EMERGENCY 模式下使用 emergencyBrakeDeceleration，ATO 指令冻结，续算到停车。</li>
 * </ul>
 */
public class SimulationControlRequest {

    /** 起始站 ID（对应 line-profile.json），用于加载线路配置。 */
    private int fromStationId;

    /** 目标站 ID（对应 line-profile.json），用于加载线路配置。 */
    private int toStationId;

    /** 当前帧列车状态（仿真续算的初始条件）。 */
    private TrainState currentState;

    /** 司机当前驾驶模式。 */
    private DrivingMode currentMode;

    /** 司机下达的控制指令（普通制动/EB/惰行等）。 */
    private ControlCommand controlCommand;

    public SimulationControlRequest() {
    }

    public int getFromStationId() {
        return fromStationId;
    }

    public void setFromStationId(int fromStationId) {
        this.fromStationId = fromStationId;
    }

    public int getToStationId() {
        return toStationId;
    }

    public void setToStationId(int toStationId) {
        this.toStationId = toStationId;
    }

    public TrainState getCurrentState() {
        return currentState;
    }

    public void setCurrentState(TrainState currentState) {
        this.currentState = currentState;
    }

    public DrivingMode getCurrentMode() {
        return currentMode;
    }

    public void setCurrentMode(DrivingMode currentMode) {
        this.currentMode = currentMode;
    }

    public ControlCommand getControlCommand() {
        return controlCommand;
    }

    public void setControlCommand(ControlCommand controlCommand) {
        this.controlCommand = controlCommand;
    }
}
