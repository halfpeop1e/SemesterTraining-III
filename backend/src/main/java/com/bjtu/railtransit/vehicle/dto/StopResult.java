package com.bjtu.railtransit.vehicle.dto;

/**
 * 自动停站结果（对应指挥书 {@code stop_result} 契约）。
 *
 * <p>阶段 1A 只做最简单的纵向运动，制动目标就是线路终点，因此这里填充的是
 * "占位但字段齐全"的真实计算结果（并非空结构）：用最后一个仿真点的位置
 * 作为实际停车点，与目标停车点比较得出误差。真正的停站策略与成功阈值判定
 * （0.5m / 0.1m/s）留给阶段 3。</p>
 */
public class StopResult {

    /** 目标停车点位置，单位 m。 */
    private double targetStopPosition;

    /** 实际停车点位置，单位 m。 */
    private double actualStopPosition;

    /** 停站误差 = 实际 - 目标，单位 m。 */
    private double stopError;

    /** 是否成功（阶段 1A 暂不做严格判定，默认按位置是否到达终点附近粗略判断）。 */
    private boolean success;

    /** 失败原因或风险提示，成功时为 null。 */
    private String reason;

    public StopResult() {
    }

    public StopResult(double targetStopPosition, double actualStopPosition, double stopError,
                       boolean success, String reason) {
        this.targetStopPosition = targetStopPosition;
        this.actualStopPosition = actualStopPosition;
        this.stopError = stopError;
        this.success = success;
        this.reason = reason;
    }

    public double getTargetStopPosition() {
        return targetStopPosition;
    }

    public void setTargetStopPosition(double targetStopPosition) {
        this.targetStopPosition = targetStopPosition;
    }

    public double getActualStopPosition() {
        return actualStopPosition;
    }

    public void setActualStopPosition(double actualStopPosition) {
        this.actualStopPosition = actualStopPosition;
    }

    public double getStopError() {
        return stopError;
    }

    public void setStopError(double stopError) {
        this.stopError = stopError;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
