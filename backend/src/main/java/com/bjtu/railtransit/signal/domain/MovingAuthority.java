package com.bjtu.railtransit.signal.domain;

public class MovingAuthority {
    private String trainId;
    private double endOfAuthorityM;      // 授权终点里程（最远能跑到这）
    private double maxSpeedKmh;          // 该授权下允许的最大速度
    private AuthorityBasis basis;        // 哪条约束最紧
    private double timestamp;
    private SignalEvent event;           // 伴随事件/降级标记
    private Integer capSignalId;         // 截断 EoA 的边界信号机（若有）
    private Integer routeId;             // 授权所沿进路（若沿进路授权）

    public MovingAuthority() {}

    public String getTrainId() { return trainId; }
    public void setTrainId(String trainId) { this.trainId = trainId; }
    public double getEndOfAuthorityM() { return endOfAuthorityM; }
    public void setEndOfAuthorityM(double endOfAuthorityM) { this.endOfAuthorityM = endOfAuthorityM; }
    public double getMaxSpeedKmh() { return maxSpeedKmh; }
    public void setMaxSpeedKmh(double maxSpeedKmh) { this.maxSpeedKmh = maxSpeedKmh; }
    public AuthorityBasis getBasis() { return basis; }
    public void setBasis(AuthorityBasis basis) { this.basis = basis; }
    public double getTimestamp() { return timestamp; }
    public void setTimestamp(double timestamp) { this.timestamp = timestamp; }
    public SignalEvent getEvent() { return event; }
    public void setEvent(SignalEvent event) { this.event = event; }
    public Integer getCapSignalId() { return capSignalId; }
    public void setCapSignalId(Integer capSignalId) { this.capSignalId = capSignalId; }
    public Integer getRouteId() { return routeId; }
    public void setRouteId(Integer routeId) { this.routeId = routeId; }
}
