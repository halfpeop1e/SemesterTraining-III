package com.bjtu.railtransit.vehicle.dto;

import com.bjtu.railtransit.vehicle.enums.DrivingMode;

/**
 * 仿真总结指标（对应指挥书 {@code summary} 契约的最小字段集合）。
 */
public class SimulationSummary {

    /** 全程最大速度，单位 m/s。 */
    private double maxVelocity;

    /** 全程总用时，单位 s。 */
    private double totalTime;

    /** 终态位置，单位 m。 */
    private double finalPosition;

    /**
     * 线路限速，单位 m/s（阶段 1.6 704 语义对齐新增字段）。
     *
     * <p>对应 704 DMI「允许速度」（704 侧单位 cm/s，本项目内部统一使用 SI 单位 m/s，
     * 换算关系为 704值(cm/s) = 本字段(m/s) * 100）。数值取自
     * {@link com.bjtu.railtransit.vehicle.model.LineProfile#getSpeedLimit()}，
     * 由 service 在构建结果时原样填充，不改变任何既有字段的含义。</p>
     */
    private double speedLimit;

    /**
     * 后端仿真采样步长，单位 s（等于 {@link com.bjtu.railtransit.vehicle.model.ScenarioConfig#getDt()}）。
     *
     * <p>前端播放 interval（毫秒）= dtPerFrame * 1000 / speedMultiplier，
     * 由此保证播放速度与后端采样频率解耦：改变倍速只改前端 interval，不改后端 dt。</p>
     */
    private double dtPerFrame;

    public SimulationSummary() {
    }

    public SimulationSummary(double maxVelocity, double totalTime, double finalPosition) {
        this.maxVelocity = maxVelocity;
        this.totalTime = totalTime;
        this.finalPosition = finalPosition;
    }

    public SimulationSummary(double maxVelocity, double totalTime, double finalPosition, double speedLimit) {
        this.maxVelocity = maxVelocity;
        this.totalTime = totalTime;
        this.finalPosition = finalPosition;
        this.speedLimit = speedLimit;
    }

    public SimulationSummary(double maxVelocity, double totalTime, double finalPosition, double speedLimit, double dtPerFrame) {
        this.maxVelocity = maxVelocity;
        this.totalTime = totalTime;
        this.finalPosition = finalPosition;
        this.speedLimit = speedLimit;
        this.dtPerFrame = dtPerFrame;
    }

    public double getMaxVelocity() {
        return maxVelocity;
    }

    public void setMaxVelocity(double maxVelocity) {
        this.maxVelocity = maxVelocity;
    }

    public double getTotalTime() {
        return totalTime;
    }

    public void setTotalTime(double totalTime) {
        this.totalTime = totalTime;
    }

    public double getFinalPosition() {
        return finalPosition;
    }

    public void setFinalPosition(double finalPosition) {
        this.finalPosition = finalPosition;
    }

    public double getSpeedLimit() {
        return speedLimit;
    }

    public void setSpeedLimit(double speedLimit) {
        this.speedLimit = speedLimit;
    }

    public double getDtPerFrame() {
        return dtPerFrame;
    }

    public void setDtPerFrame(double dtPerFrame) {
        this.dtPerFrame = dtPerFrame;
    }

    // ---- 阶段4B 线路数据真实化新增字段 ----
    // train_state.position 仍是本次区间内相对位置（0 → runDistanceM）。
    // lineStartPosition / lineTargetPosition 是真实线路绝对里程（单位 m），
    // 供前端把相对位置映射到全线地图：
    //   absolutePosition = lineStartPosition + currentState.position

    /**
     * 本次仿真起始站的真实线路绝对里程，单位 m（= fromStation.km * 1000）。
     * 来自 configs/line-profile.json，不伪造。
     */
    private double lineStartPosition;

    /**
     * 本次仿真终止站的真实线路绝对里程，单位 m（= toStation.km * 1000）。
     * 来自 configs/line-profile.json，不伪造。
     */
    private double lineTargetPosition;

    /** 起始站中文名（来自 configs/line-profile.json stations[].name）。 */
    private String fromStationName;

    /** 终止站中文名（来自 configs/line-profile.json stations[].name）。 */
    private String toStationName;

    public double getLineStartPosition() {
        return lineStartPosition;
    }

    public void setLineStartPosition(double lineStartPosition) {
        this.lineStartPosition = lineStartPosition;
    }

    public double getLineTargetPosition() {
        return lineTargetPosition;
    }

    public void setLineTargetPosition(double lineTargetPosition) {
        this.lineTargetPosition = lineTargetPosition;
    }

    public String getFromStationName() {
        return fromStationName;
    }

    public void setFromStationName(String fromStationName) {
        this.fromStationName = fromStationName;
    }

    public String getToStationName() {
        return toStationName;
    }

    public void setToStationName(String toStationName) {
        this.toStationName = toStationName;
    }

    // ---- 驾驶模式状态机字段（本轮新增）----

    /**
     * 续算结束后的驾驶模式（control 接口返回时填充）。
     * run 接口返回时为 null（全程 ATO 仿真不维护模式字段）。
     */
    private DrivingMode currentMode;

    /**
     * 下一步允许切换的驾驶模式建议（可为 null）。
     * 例如 EMERGENCY 停稳后建议 MANUAL。
     */
    private DrivingMode nextMode;

    public DrivingMode getCurrentMode() {
        return currentMode;
    }

    public void setCurrentMode(DrivingMode currentMode) {
        this.currentMode = currentMode;
    }

    public DrivingMode getNextMode() {
        return nextMode;
    }

    public void setNextMode(DrivingMode nextMode) {
        this.nextMode = nextMode;
    }

    // ---- 多站连续仿真汇总字段（新增，单区间时为默认值）----

    /** 起始站 id（多站仿真时填充）。 */
    private int fromStationId;

    /** 终止站 id（多站仿真时填充）。 */
    private int toStationId;

    /**
     * 总经停站数（含起点和终点）。
     * 单区间：2；跨 N 个区间：N+1。
     */
    private int totalStations;

    /** 已完成停车的站数（含终点站）。多站运行中断时可能 < totalStations。 */
    private int completedStops;

    /** 所有中间站驻留时间之和，单位 s。单区间时为 0。 */
    private double totalDwellTime;

    public int getFromStationId() { return fromStationId; }
    public void setFromStationId(int fromStationId) { this.fromStationId = fromStationId; }

    public int getToStationId() { return toStationId; }
    public void setToStationId(int toStationId) { this.toStationId = toStationId; }

    public int getTotalStations() { return totalStations; }
    public void setTotalStations(int totalStations) { this.totalStations = totalStations; }

    public int getCompletedStops() { return completedStops; }
    public void setCompletedStops(int completedStops) { this.completedStops = completedStops; }

    public double getTotalDwellTime() { return totalDwellTime; }
    public void setTotalDwellTime(double totalDwellTime) { this.totalDwellTime = totalDwellTime; }
}
