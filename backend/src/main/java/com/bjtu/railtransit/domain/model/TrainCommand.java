package com.bjtu.railtransit.domain.model;

/**
 * Dispatch-to-onboard command lifecycle. This is the only public command DTO.
 */
public class TrainCommand {
    private String commandId;
    private String trainId;
    private String commandType;
    private double targetValue;
    private String reason;
    private int priority;
    private String source;
    private String status;
    private double issuedTimeSeconds;
    private Double confirmedTimeSeconds;
    private Double acknowledgedTimeSeconds;
    private Double completedTimeSeconds;
    private int deliveryAttempts;

    public String getCommandId() { return commandId; }
    public void setCommandId(String v) { commandId = v; }
    public String getTrainId() { return trainId; }
    public void setTrainId(String v) { trainId = v; }
    public String getCommandType() { return commandType; }
    public void setCommandType(String v) { commandType = v; }
    public double getTargetValue() { return targetValue; }
    public void setTargetValue(double v) { targetValue = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { reason = v; }
    public int getPriority() { return priority; }
    public void setPriority(int v) { priority = v; }
    public String getSource() { return source; }
    public void setSource(String v) { source = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public double getIssuedTimeSeconds() { return issuedTimeSeconds; }
    public void setIssuedTimeSeconds(double v) { issuedTimeSeconds = v; }
    public Double getConfirmedTimeSeconds() { return confirmedTimeSeconds; }
    public void setConfirmedTimeSeconds(Double v) { confirmedTimeSeconds = v; }
    public Double getAcknowledgedTimeSeconds() { return acknowledgedTimeSeconds; }
    public void setAcknowledgedTimeSeconds(Double v) { acknowledgedTimeSeconds = v; }
    public Double getCompletedTimeSeconds() { return completedTimeSeconds; }
    public void setCompletedTimeSeconds(Double v) { completedTimeSeconds = v; }
    public int getDeliveryAttempts() { return deliveryAttempts; }
    public void setDeliveryAttempts(int v) { deliveryAttempts = v; }
}
