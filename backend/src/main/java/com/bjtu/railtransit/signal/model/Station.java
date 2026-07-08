package com.bjtu.railtransit.signal.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class Station {
    private String id;
    private String name;
    private double positionM;            // 中心里程 m
    private boolean isTerminal;          // 终点站
    private double platformLengthM;
    private List<Integer> platformIds;   // 关联站台（来自车站表）

    public Station() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public double getPositionM() { return positionM; }
    public void setPositionM(double positionM) { this.positionM = positionM; }
    @JsonProperty("isTerminal")
    public boolean isTerminal() { return isTerminal; }
    @JsonProperty("isTerminal")
    public void setTerminal(boolean isTerminal) { this.isTerminal = isTerminal; }
    public double getPlatformLengthM() { return platformLengthM; }
    public void setPlatformLengthM(double platformLengthM) { this.platformLengthM = platformLengthM; }
    public List<Integer> getPlatformIds() { return platformIds; }
    public void setPlatformIds(List<Integer> platformIds) { this.platformIds = platformIds; }
}
