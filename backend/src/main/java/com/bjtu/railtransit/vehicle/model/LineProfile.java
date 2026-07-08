package com.bjtu.railtransit.vehicle.model;

import java.util.Collections;
import java.util.List;

/**
 * 线路静态配置（STATIC 数据）。
 *
 * <p>阶段 1A 使用后端内置的最简演示线路：单一区间、单一限速，
 * 不读取 {@code configs/} 下的 sample JSON。坡度字段在阶段 2 新增，
 * 缺失时（未传入或空列表）按 0 处理，向后兼容阶段 1 的三参数构造函数。</p>
 */
public class LineProfile {

    /** 线路起点位置，单位 m。 */
    private final double startPosition;

    /** 目标停车点位置（本轮等同于线路终点），单位 m。 */
    private final double targetStopPosition;

    /** 区间限速，单位 m/s。 */
    private final double speedLimit;

    /**
     * 坡度段列表（阶段 2 新增字段）。空列表表示全程平坡（i=0），与阶段 1 行为等价。
     *
     * <p>本轮不解析 {@code 线路数据(1).xls} 的坡度表，段落划分与坡度取值为工程假设值，
     * 详见 {@link com.bjtu.railtransit.vehicle.service.DemoScenarioProvider} 注释。</p>
     */
    private final List<GradeSegment> gradeSegments;

    public LineProfile(double startPosition, double targetStopPosition, double speedLimit) {
        this(startPosition, targetStopPosition, speedLimit, Collections.emptyList());
    }

    public LineProfile(double startPosition, double targetStopPosition, double speedLimit,
                        List<GradeSegment> gradeSegments) {
        this.startPosition = startPosition;
        this.targetStopPosition = targetStopPosition;
        this.speedLimit = speedLimit;
        this.gradeSegments = gradeSegments == null ? Collections.emptyList() : gradeSegments;
    }

    public double getStartPosition() {
        return startPosition;
    }

    public double getTargetStopPosition() {
        return targetStopPosition;
    }

    public double getSpeedLimit() {
        return speedLimit;
    }

    public List<GradeSegment> getGradeSegments() {
        return gradeSegments;
    }

    /**
     * 返回给定位置所属坡度段的坡度值；若不落在任何已定义坡度段内，按 0（平坡）处理。
     */
    public double gradientAt(double position) {
        for (GradeSegment segment : gradeSegments) {
            if (segment.contains(position)) {
                return segment.getGradient();
            }
        }
        return 0.0;
    }
}
