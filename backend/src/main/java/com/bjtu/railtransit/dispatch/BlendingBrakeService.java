package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.domain.model.BrakingSystemState;
import com.bjtu.railtransit.domain.model.TractionSystemState;
import org.springframework.stereotype.Service;

/**
 * 电空配合 (Blending) 制动分配器 —— 对应 doc.md §6 牵引/制动协同交互。
 *
 * 真实地铁车辆逻辑:
 *   1. 电制动优先 (再生能量回收)
 *   2. 电制动不足时, 空气制动补充 (摩擦制动)
 *   3. 低速 (~5km/h) 电制动退出, 完全切换至空气制动
 *   4. 电制动故障时, 100% 空气制动
 *
 * 来源: 中车四方所《城轨电空配合制动控制策略》及 TB/T 1407.2
 */
@Service
public class BlendingBrakeService {

    /** 电制动退出速度 (km/h) —— 低于此速电制动归零 */
    private static final double ELEX_EXIT_SPEED_KMH = 5.0;
    /** 电空分配: 空气制动响应比例 (当电制动不足时) */
    private static final double AIR_BLEND_RATIO = 1.0;

    /**
     * 电空配合分配结果
     */
    public record BrakeAllocation(
            double electricBrakeN,    // 电制动实际施加 (N)
            double airBrakeN,         // 空气制动实际施加 (N)
            double totalBrakeN,       // 总制动 (N)
            String blendingMode       // ELEC_ONLY | BLEND | AIR_ONLY
    ) {}

    /**
     * 根据总制动需求, 分配电制动和空气制动分量.
     *
     * @param totalBrakeForceN 总制动需求 (N)
     * @param speedKmh         当前速度 (km/h)
     * @param ts               牵引系统状态 (含电制动能力)
     * @param bs               制动系统状态
     * @return 电空分配结果
     */
    public BrakeAllocation allocate(double totalBrakeForceN, double speedKmh,
                                    TractionSystemState ts, BrakingSystemState bs) {

        // 1. 电制动可用性检查
        boolean elecUsable = ts != null && ts.isElectricBrakeAvailable()
                && !"FAULT".equals(ts.getHealth());

        // 2. 低速退出 (电制动切出, 完全空气制动)
        if (speedKmh < ELEX_EXIT_SPEED_KMH || !elecUsable) {
            // 制动→牵引方向: 请求电制动 (值为0表示无请求)
            if (bs != null) bs.setElectricBrakeRequestN(0);
            // 牵引→制动方向: 实际施加0
            if (ts != null) ts.setElectricBrakeAppliedN(0);
            return new BrakeAllocation(0, totalBrakeForceN, totalBrakeForceN, "AIR_ONLY");
        }

        // 3. 电制动最大能力
        double maxElec = ts.getMaxElectricBrakeForceN();
        // 制动→牵引: 请求电制动 (值 = 总需求, 上限由能力约束)
        if (bs != null) bs.setElectricBrakeRequestN(Math.min(totalBrakeForceN, maxElec));

        // 4. 电制动优先分配
        double elecApplied = Math.min(totalBrakeForceN, maxElec);
        // 5. 空气制动补充
        double airApplied = Math.max(0, (totalBrakeForceN - elecApplied) * AIR_BLEND_RATIO);

        // 6. 牵引→制动: 反馈实际电制动力
        ts.setElectricBrakeAppliedN(elecApplied);

        // 7. 更新配合模式
        String mode;
        if (airApplied < 1.0) mode = "ELEC_ONLY";
        else if (elecApplied < 1.0) mode = "AIR_ONLY";
        else mode = "BLEND";
        if (bs != null) bs.setBlendingMode(mode);

        return new BrakeAllocation(elecApplied, airApplied,
                elecApplied + airApplied, mode);
    }
}
