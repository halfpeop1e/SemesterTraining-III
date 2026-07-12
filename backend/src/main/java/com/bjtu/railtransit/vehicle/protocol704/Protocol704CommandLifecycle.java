package com.bjtu.railtransit.vehicle.protocol704;

import com.bjtu.railtransit.vehicle.dto.TrainState;

/** Process-local lifecycle record for one local-v1 PLC command. */
public class Protocol704CommandLifecycle {
    private String commandId;
    private String source = "PLC_704_LOCAL_V1";
    private long receivedAt;
    private String parsedCommand;
    private double level;
    private String activeTrainId;
    private String sessionId;
    private String previousMode;
    private String resultMode;
    private String status;
    private String rejectionReason;
    private String executionError;
    private TrainState executedState;
    private String controlSource = "PLC_704_LOCAL_V1";
    private String departureState = "READY_TO_DEPART";

    public String getCommandId() { return commandId; }
    public void setCommandId(String commandId) { this.commandId = commandId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
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
}
