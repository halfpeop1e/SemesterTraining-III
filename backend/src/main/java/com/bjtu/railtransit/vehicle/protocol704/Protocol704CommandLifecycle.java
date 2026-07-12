package com.bjtu.railtransit.vehicle.protocol704;

import com.bjtu.railtransit.vehicle.dto.SimulationResult;
import com.bjtu.railtransit.vehicle.dto.TrainState;

public class Protocol704CommandLifecycle {
    private String commandId;
    private String source = "PLC_704_LOCAL_V1";
    private String connectionId;
    private int port = -1;
    private long receivedAt;
    private String parsedCommand;
    private double level;
    private String activeTrainId;
    private String sessionId;
    private String previousMode;
    private String previousControlSource;
    private String resultMode;
    private String status;
    private String rejectionReason;
    private String executionError;
    private TrainState executedState;
    private String controlSource = "PLC_704_LOCAL_V1";
    private String departureState = "READY_TO_DEPART";
    private boolean emergencyLatchedBefore;
    private boolean emergencyLatchedAfter;
    /** 完整的续算仿真结果（含 states/summary/stopResult/safetyEvents），仅当命令触发物理仿真时非空。
     *  前端用此字段把 EB 制动轨迹拼接到主 SimulationResult 后面播放，避免只拿到单个 executedState。 */
    private SimulationResult executedResult;

    public String getCommandId() { return commandId; }
    public void setCommandId(String commandId) { this.commandId = commandId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public long getReceivedAt() { return receivedAt; }
    public void setReceivedAt(long receivedAt) { this.receivedAt = receivedAt; }
    public String getParsedCommand() { return parsedCommand; }
    public void setParsedCommand(String parsedCommand) { this.parsedCommand = parsedCommand; }
    public double getLevel() { return level; }
    public void setLevel(double level) { this.level = level; }
    public String getActiveTrainId() { return activeTrainId; }
    public void setActiveTrainId(String activeTrainId) { this.activeTrainId = activeTrainId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getPreviousMode() { return previousMode; }
    public void setPreviousMode(String previousMode) { this.previousMode = previousMode; }
    public String getPreviousControlSource() { return previousControlSource; }
    public void setPreviousControlSource(String previousControlSource) { this.previousControlSource = previousControlSource; }
    public String getResultMode() { return resultMode; }
    public void setResultMode(String resultMode) { this.resultMode = resultMode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    public String getExecutionError() { return executionError; }
    public void setExecutionError(String executionError) { this.executionError = executionError; }
    public TrainState getExecutedState() { return executedState; }
    public void setExecutedState(TrainState executedState) { this.executedState = executedState; }
    public String getControlSource() { return controlSource; }
    public void setControlSource(String controlSource) { this.controlSource = controlSource; }
    public String getDepartureState() { return departureState; }
    public void setDepartureState(String departureState) { this.departureState = departureState; }
    public boolean isEmergencyLatchedBefore() { return emergencyLatchedBefore; }
    public void setEmergencyLatchedBefore(boolean emergencyLatchedBefore) { this.emergencyLatchedBefore = emergencyLatchedBefore; }
    public boolean isEmergencyLatchedAfter() { return emergencyLatchedAfter; }
    public void setEmergencyLatchedAfter(boolean emergencyLatchedAfter) { this.emergencyLatchedAfter = emergencyLatchedAfter; }
    public SimulationResult getExecutedResult() { return executedResult; }
    public void setExecutedResult(SimulationResult executedResult) { this.executedResult = executedResult; }
}
