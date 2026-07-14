package com.bjtu.railtransit.vehicle.model;

import java.util.Collections;
import java.util.List;

/**
 * 线路静态配置（STATIC 数据）。
 *
 * <p>支持变坡度、变限速、隧道段、曲线段。
 * 空列表表示全程默认（平坡/全区间限速/无隧道/无曲线）。</p>
 */
public class LineProfile {

    private final double startPosition;
    private final double targetStopPosition;

    /** 区间默认限速，单位 m/s。有变限速段时按段取值。 */
    private final double speedLimit;

    /** 坡度段列表。空列表 = 全程平坡。 */
    private final List<GradeSegment> gradeSegments;

    /** 变限速段列表。空列表则全程使用 {@link #speedLimit}。 */
    private final List<SpeedLimitSegment> speedLimitSegments;

    /** 隧道段列表。用于隧道附加阻力。 */
    private final List<TunnelSegment> tunnelSegments;

    /** 平面曲线段列表。用于曲线附加阻力。 */
    private final List<CurveSegment> curveSegments;

    public LineProfile(double startPosition, double targetStopPosition, double speedLimit) {
        this(startPosition, targetStopPosition, speedLimit,
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
    }

    public LineProfile(double startPosition, double targetStopPosition, double speedLimit,
                        List<GradeSegment> gradeSegments) {
        this(startPosition, targetStopPosition, speedLimit,
                gradeSegments, Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
    }

    public LineProfile(double startPosition, double targetStopPosition, double speedLimit,
                        List<GradeSegment> gradeSegments,
                        List<SpeedLimitSegment> speedLimitSegments,
                        List<TunnelSegment> tunnelSegments,
                        List<CurveSegment> curveSegments) {
        this.startPosition = startPosition;
        this.targetStopPosition = targetStopPosition;
        this.speedLimit = speedLimit;
        this.gradeSegments = gradeSegments == null ? Collections.emptyList() : gradeSegments;
        this.speedLimitSegments = speedLimitSegments == null ? Collections.emptyList() : speedLimitSegments;
        this.tunnelSegments = tunnelSegments == null ? Collections.emptyList() : tunnelSegments;
        this.curveSegments = curveSegments == null ? Collections.emptyList() : curveSegments;
    }

    public double getStartPosition() { return startPosition; }
    public double getTargetStopPosition() { return targetStopPosition; }

    /** 全区间默认限速 m/s。 */
    public double getSpeedLimit() { return speedLimit; }

    /**
     * 给定位置的限速 m/s。有变限速段时按段取值，否则返回全区间默认限速。
     */
    public double speedLimitAt(double position) {
        for (SpeedLimitSegment seg : speedLimitSegments) {
            if (seg.contains(position)) return seg.getLimitMps();
        }
        return speedLimit;
    }

    /** 给定位置所处坡度段的坡度值（弧度）；不落在任何段内返回 0。 */
    public double gradientAt(double position) {
        for (GradeSegment segment : gradeSegments) {
            if (segment.contains(position)) return segment.getGradient();
        }
        return 0.0;
    }

    /** 给定位置是否在隧道内。 */
    public boolean isInTunnel(double position) {
        for (TunnelSegment seg : tunnelSegments) {
            if (seg.contains(position)) return true;
        }
        return false;
    }

    /** 给定位置的曲线半径 m；不在曲线段返回 0（直线）。 */
    public double curveRadiusAt(double position) {
        for (CurveSegment seg : curveSegments) {
            if (seg.contains(position)) return seg.getRadiusM();
        }
        return 0.0;
    }

    /** 检测区间内是否存在非零坡度段，用于验证坡度数据是否已加载。 */
    public boolean getGradientAtRangeNonZero() {
        for (GradeSegment seg : gradeSegments) {
            if (Math.abs(seg.getGradient()) > 1.0e-9) return true;
        }
        return false;
    }

    public List<GradeSegment> getGradeSegments() { return gradeSegments; }
    public List<SpeedLimitSegment> getSpeedLimitSegments() { return speedLimitSegments; }
    public List<TunnelSegment> getTunnelSegments() { return tunnelSegments; }
    public List<CurveSegment> getCurveSegments() { return curveSegments; }
}
