package com.bjtu.railtransit.signal.model;

public class Turnback {
    private String id;
    private double positionM;
    private TurnbackType type;

    public Turnback() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public double getPositionM() { return positionM; }
    public void setPositionM(double positionM) { this.positionM = positionM; }
    public TurnbackType getType() { return type; }
    public void setType(TurnbackType type) { this.type = type; }
}
