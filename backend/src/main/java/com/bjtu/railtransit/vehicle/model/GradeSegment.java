package com.bjtu.railtransit.vehicle.model;

/**
 * 坡度段（STATIC 数据，阶段 2 新增）。
 *
 * <p>描述线路上某一段区间 [startPosition, endPosition) 的坡度值。坡度 {@code gradient}
 * 是无量纲小数（正值表示上坡，负值表示下坡），例如 0.003 表示 3‰ 上坡。</p>
 *
 * <p><b>数据来源说明：</b>本轮任务明确不要求解析全线 {@code 线路数据(1).xls} 的坡度表
 * （那属于数据工程，留待后续阶段）。这里的坡度段仅用于演示"坡度阻力确实进入动力学计算"
 * 这一物理效应，具体段落划分与坡度取值属于工程假设值，不冒充真实线路坡度数据，
 * 详见 {@link com.bjtu.railtransit.vehicle.service.DemoScenarioProvider} 的注释。</p>
 *
 * <p>本类是本模块新增的独立小结构，未在共享域 {@code domain/} 或本模块其它地方发现
 * 可复用的"坡度段"概念，符合"复用优先，找不到才新建"的护栏要求。</p>
 */
public class GradeSegment {

    /** 坡度段起点位置（含），单位 m。 */
    private final double startPosition;

    /** 坡度段终点位置（不含），单位 m。 */
    private final double endPosition;

    /** 坡度，无量纲小数（正值上坡，负值下坡），例如 0.003 表示 3‰。 */
    private final double gradient;

    public GradeSegment(double startPosition, double endPosition, double gradient) {
        this.startPosition = startPosition;
        this.endPosition = endPosition;
        this.gradient = gradient;
    }

    public double getStartPosition() {
        return startPosition;
    }

    public double getEndPosition() {
        return endPosition;
    }

    public double getGradient() {
        return gradient;
    }

    /**
     * 判断给定位置是否落在本坡度段内（[startPosition, endPosition) 半开区间）。
     */
    public boolean contains(double position) {
        return position >= startPosition && position < endPosition;
    }
}
