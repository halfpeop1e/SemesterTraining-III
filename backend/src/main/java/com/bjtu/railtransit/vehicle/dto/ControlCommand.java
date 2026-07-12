package com.bjtu.railtransit.vehicle.dto;

/**
 * 控制指令（对应指挥书 {@code control_command} 契约）。
 *
 * <p>阶段 1A 尚未对外暴露独立的控制指令流；本类先按契约定义好结构，
 * 供阶段 2（真实动力学 + 控制策略）和阶段 4（SafetyGuard 覆盖控制指令）使用。</p>
 */
public class ControlCommand {

    /** 指令类型：traction / coast / brake / emergency_brake / SET_MANUAL / RESUME_ATO / DEPART_CONFIRM。 */
    private String command;

    /** 目标减速度，单位 m/s2（牵引/惰行指令下可为 0 或不使用）。 */
    private double targetDecel;

    /**
     * 牵引/制动力百分比，取值范围 0~100（阶段 1.6 704 语义对齐新增字段）。
     *
     * <p>对应 704「牵引制动百分比」字段（704 侧原始编码范围 0x00~0xff，
     * 换算关系为 704值(0~0xff) = 本字段(0~100) / 100 * 0xff）。{@link #command}
     * 对应 704「牵引制动命令」（704 侧编码 0x55 = 牵引，0xaa = 制动），本项目内部
     * 仍使用可读字符串（如 traction/brake），不引入 704 原始字节编码。
     * ControlCommand 当前无调用方，本轮仅定义字段用于语义对齐，不接线、不产生
     * 任何网络/字节序相关代码。</p>
     */
    private double levelPercent;

    /** local-v1 direction semantic: FORWARD / ZERO / REVERSE. */
    private String direction;

    public ControlCommand() {
    }

    public ControlCommand(String command, double targetDecel) {
        this.command = command;
        this.targetDecel = targetDecel;
    }

    public ControlCommand(String command, double targetDecel, double levelPercent) {
        this.command = command;
        this.targetDecel = targetDecel;
        this.levelPercent = levelPercent;
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

    public double getLevelPercent() {
        return levelPercent;
    }

    public void setLevelPercent(double levelPercent) {
        this.levelPercent = levelPercent;
    }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
}
