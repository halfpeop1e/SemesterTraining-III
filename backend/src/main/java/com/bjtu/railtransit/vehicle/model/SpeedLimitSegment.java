package com.bjtu.railtransit.vehicle.model;

/**
 * 变限速段。限速值单位 m/s。
 */
public class SpeedLimitSegment {

    private final double startPositionM;
    private final double endPositionM;
    private final double limitMps;

    public SpeedLimitSegment(double startPositionM, double endPositionM, double limitMps) {
        this.startPositionM = startPositionM;
        this.endPositionM = endPositionM;
        this.limitMps = limitMps;
    }

    public boolean contains(double position) {
        return position >= startPositionM && position < endPositionM;
    }

    public double getStartPositionM() { return startPositionM; }
    public double getEndPositionM() { return endPositionM; }
    public double getLimitMps() { return limitMps; }
}
