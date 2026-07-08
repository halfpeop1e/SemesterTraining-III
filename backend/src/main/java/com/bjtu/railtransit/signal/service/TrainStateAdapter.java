package com.bjtu.railtransit.signal.service;

import com.bjtu.railtransit.signal.domain.Direction;
import com.bjtu.railtransit.signal.domain.TrainState;
import com.bjtu.railtransit.signal.model.LineProfile;
import com.bjtu.railtransit.signal.util.Units;

/**
 * 列车运行时状态适配器（边界转换的权威入口）。
 *
 * <p>解决多模块"列车状态表示不一致"的重复定义风险：
 * <ul>
 *   <li>教学示例 / 动态运行数据用 {@code current_seg_id + offset_m + speed_mps}（段相对 + m/s）；</li>
 *   <li>704 协议用 积累走行距离(cm) + 速度(cm/s)（坐标定义待周锦泽确认，暂不在此转换）；</li>
 *   <li>MA 的 {@link TrainState} 用 <b>绝对里程 m + km/h</b>（MA 需跨区段比位置找最近边界）。</li>
 * </ul>
 *
 * <p>其他模块把"段相对 + m/s"喂进 MA 前，统一调 {@link #fromSegmentRuntime} 转换，
 * <b>不要各自实现转换逻辑、也不要新建第二个 TrainState</b>。转换全部走 {@link Units}（单位）+
 * {@link LineProfile#locateMileage}（坐标），不另写魔法数。
 *
 * <p>容错：若 segId 非法，{@code positionM} 为 NaN，MA 的 {@code validState} 判定失败 → 该车
 * （及依赖它的车）触发 {@code DEGRADED} fail-safe（见接口说明 §7）。
 */
public final class TrainStateAdapter {
    private TrainStateAdapter() {}

    /**
     * 由「段相对 + m/s」运行时表示构建 MA 的 TrainState（绝对里程 m + km/h）。
     *
     * @param line       已 {@link LineProfile#buildMileageIndex()} 的线路
     * @param trainId    列车标识
     * @param segId      列车所处 TrackSegment id
     * @param offsetM    该段内偏移 m
     * @param speedMps   当前速度 m/s
     * @param direction  行车方向（{@code null}/{@code INVALID} 触发 fail-safe）
     * @param lengthM    列车长度 m（须 > 0）
     * @param timestamp  仿真时刻 s
     * @return TrainState；segId 非法时 positionM=NaN（MA 按 fail-safe 降级）
     */
    public static TrainState fromSegmentRuntime(LineProfile line, String trainId,
                                                int segId, double offsetM, double speedMps,
                                                Direction direction, double lengthM, double timestamp) {
        TrainState t = new TrainState();
        t.setTrainId(trainId);
        // locateMileage 入参为 cm
        t.setPositionM(line.locateMileage(String.valueOf(segId), Units.mToCm(offsetM)));
        t.setSpeedKmh(Units.mpsToKmh(speedMps));
        t.setAccelerationMps2(0.0);
        t.setLengthM(lengthM);
        t.setDirection(direction);
        t.setTimestamp(timestamp);
        return t;
    }

    /**
     * 由「绝对里程 + km/h」直接构建（已知绝对里程时的便捷入口，避免各模块手写一堆 setter）。
     */
    public static TrainState fromAbsolute(String trainId, double positionM, double speedKmh,
                                          Direction direction, double lengthM, double timestamp) {
        TrainState t = new TrainState();
        t.setTrainId(trainId);
        t.setPositionM(positionM);
        t.setSpeedKmh(speedKmh);
        t.setAccelerationMps2(0.0);
        t.setLengthM(lengthM);
        t.setDirection(direction);
        t.setTimestamp(timestamp);
        return t;
    }
}
