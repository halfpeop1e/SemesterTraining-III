package com.bjtu.railtransit.vehicle.model;

/** 隧道段。 */
public class TunnelSegment {
    private final double startPositionM;
    private final double endPositionM;

    public TunnelSegment(double startM, double endM) {
        this.startPositionM = startM;
        this.endPositionM = endM;
    }

    public boolean contains(double pos) {
        return pos >= startPositionM && pos < endPositionM;
    }

    public double getStartPositionM() { return startPositionM; }
    public double getEndPositionM() { return endPositionM; }
}
