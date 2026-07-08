package com.bjtu.railtransit.vehicle.dto;

/**
 * 控制指令（对应指挥书 {@code control_command} 契约）。
 *
 * <p>阶段 1A 尚未对外暴露独立的控制指令流；本类先按契约定义好结构，
 * 供阶段 2（真实动力学 + 控制策略）和阶段 4（SafetyGuard 覆盖控制指令）使用。</p>
 */
public class ControlCommand {

    /** 指令类型：traction / coast / brake / emergency_brake。 */
    private String command;

    /** 目标减速度，单位 m/s2（牵引/惰行指令下可为 0 或不使用）。 */
    private double targetDecel;

    public ControlCommand() {
    }

    public ControlCommand(String command, double targetDecel) {
        this.command = command;
        this.targetDecel = targetDecel;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public double getTargetDecel() {
        return targetDecel;
    }

    public void setTargetDecel(double targetDecel) {
        this.targetDecel = targetDecel;
    }
}
