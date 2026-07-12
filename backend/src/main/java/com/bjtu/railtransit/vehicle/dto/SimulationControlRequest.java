package com.bjtu.railtransit.vehicle.dto;

import com.bjtu.railtransit.vehicle.enums.DrivingMode;

/**
 * POST /api/vehicle/simulation/control 请求体。
 *
 * <p>坐标约定：{@link #currentState} 中的 {@code position} 必须是从本次仿真
 * 起点站（fromStation）开始的连续累计相对里程，与 {@link SimulationResult} 中
 * 的 states 坐标系一致。{@link #totalTargetPosition} 是本次仿真从 fromStation
 * 到最终 toStation 的总距离（= stopResult.targetStopPosition）。</p>
 */
public class SimulationControlRequest {

    /** 起始站 id，用于加载线路配置。 */
    private int fromStationId;

    /** 目标站 id，用于加载线路配置。 */
    private int toStationId;

    /**
     * 当前帧列车状态（续算初始条件）。
     * position 是从 fromStation 起的累计相对里程，不是局部区间里程。
     */
    private TrainState currentState;

    /** 司机当前驾驶模式。 */
    private DrivingMode currentMode;

    /** 司机下达的控制指令（command + targetDecel）。 */
    private ControlCommand controlCommand;

    /**
     * 本次仿真从 fromStation 到最终 toStation 的总目标距离，单位 m。
     * 等于原 stopResult.targetStopPosition。续算时以此为制动目标。
     *
     * <p>Bug B2 修复后语义：前端在多站仿真中应传"下一未到达站的累计里程"，
     * 而非最终终点里程，以避免 ATO 跳过中间站。</p>
     */
    private double totalTargetPosition;

    /** 下一目标站 id（前端计算后传入，供日志/返回使用）。可选字段，不传时按原逻辑处理。 */
    private Integer nextStationId;

    /** 下一目标站名（前端计算后传入，供日志/返回使用）。可选字段。 */
    private String nextStationName;
    /** Optional local vehicle identity; used only for WEB_HMI/PLC control arbitration. */
    private String trainId;

    /** 车载确认发车后才允许仿真进入 RUNNING；兼容旧请求时默认为 false。 */
    private boolean departureConfirmed;

    public SimulationControlRequest() {
    }

    public int getFromStationId() { return fromStationId; }
    public void setFromStationId(int fromStationId) { this.fromStationId = fromStationId; }

    public int getToStationId() { return toStationId; }
    public void setToStationId(int toStationId) { this.toStationId = toStationId; }

    public TrainState getCurrentState() { return currentState; }
    public void setCurrentState(TrainState currentState) { this.currentState = currentState; }

    public DrivingMode getCurrentMode() { return currentMode; }
    public void setCurrentMode(DrivingMode currentMode) { this.currentMode = currentMode; }

    public ControlCommand getControlCommand() { return controlCommand; }
    public void setControlCommand(ControlCommand controlCommand) { this.controlCommand = controlCommand; }

    public double getTotalTargetPosition() { return totalTargetPosition; }
    public void setTotalTargetPosition(double totalTargetPosition) { this.totalTargetPosition = totalTargetPosition; }

    public Integer getNextStationId() { return nextStationId; }
    public void setNextStationId(Integer nextStationId) { this.nextStationId = nextStationId; }

    public String getNextStationName() { return nextStationName; }
    public void setNextStationName(String nextStationName) { this.nextStationName = nextStationName; }
    public String getTrainId() { return trainId; }
    public void setTrainId(String trainId) { this.trainId = trainId; }
    public boolean isDepartureConfirmed() { return departureConfirmed; }
    public void setDepartureConfirmed(boolean departureConfirmed) { this.departureConfirmed = departureConfirmed; }
}
