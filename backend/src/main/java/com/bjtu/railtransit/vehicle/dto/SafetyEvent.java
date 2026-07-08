package com.bjtu.railtransit.vehicle.dto;

/**
 * SafetyGuard 安全事件（对应指挥书 {@code safety_event} 契约）。
 *
 * <p>阶段 1A 不实现 SafetyGuard 逻辑，本轮仿真过程中不会触发任何事件，
 * {@link com.bjtu.railtransit.vehicle.dto.SimulationResult#getSafetyEvents()}
 * 返回空数组。本类先按契约定义好字段结构，供阶段 4 使用。</p>
 */
public class SafetyEvent {

    /** 触发原因，例如 "overspeed"。 */
    private String reason;

    /** 触发时刻，单位 s。 */
    private double time;

    /** 触发位置，单位 m。 */
    private double position;

    /** 触发速度，单位 m/s。 */
    private double velocity;

    /** 采取的保护动作，例如 "brake" / "emergency_brake"。 */
    private String action;

    public SafetyEvent() {
    }

    public SafetyEvent(String reason, double time, double position, double velocity, String action) {
        this.reason = reason;
        this.time = time;
        this.position = position;
        this.velocity = velocity;
        this.action = action;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public double getTime() {
        return time;
    }

    public void setTime(double time) {
        this.time = time;
    }

    public double getPosition() {
        return position;
    }

    public void setPosition(double position) {
        this.position = position;
    }

    public double getVelocity() {
        return velocity;
    }

    public void setVelocity(double velocity) {
        this.velocity = velocity;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }
}
