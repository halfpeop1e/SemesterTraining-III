package com.bjtu.railtransit.vehicle.model;

/** 平面曲线段。半径单位 m。 */
public class CurveSegment {
    private final double startPositionM;
    private final double endPositionM;
    private final double radiusM;

    public CurveSegment(double startM, double endM, double radiusM) {
        this.startPositionM = startM;
        this.endPositionM = endM;
        this.radiusM = radiusM;
    }

    public boolean contains(double pos) {
        return pos >= startPositionM && pos < endPositionM;
    }

    public double getStartPositionM() { return startPositionM; }
    public double getEndPositionM() { return endPositionM; }
    public double getRadiusM() { return radiusM; }
}
