package com.bjtu.railtransit.domain.dto;

public class SimulationRequest {

    private int simulationDuration = 3600;
    private int steps = 1;

    public SimulationRequest() {
    }

    public int getSimulationDuration() {
        return simulationDuration;
    }

    public void setSimulationDuration(int simulationDuration) {
        this.simulationDuration = simulationDuration;
    }

    public int getSteps() {
        return steps;
    }

    public void setSteps(int steps) {
        this.steps = steps;
    }
}
