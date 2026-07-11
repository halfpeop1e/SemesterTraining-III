package com.bjtu.railtransit.signal.service;

import org.springframework.stereotype.Component;

/**
 * MA 安全参数（可配置，不写死）。源自 tech-design.md §2.2 / §2.4。
 * 数值例：safeSeparationM=20, t_total=5s 复现 §2.5 数值算例（requiredGap=110）。
 */
@Component
public class MaConfig {
    /** 停车后两车最小净距 m */
    public double safeSeparationM = 20.0;
    /** 反应时间 s */
    public double tReactionS = 2.0;
    /** 安全余量 s */
    public double tSafetyMarginS = 1.0;
    /** 列车额定制动减速度 m/s²（车辆参数，此处消费） */
    public double aBrakeMps2 = 9.0;
    /**
     * 最小安全减速度兜底 m/s²（异常下限，非列车实际制动能力）。
     * 真实制动由 aBrakeMps2 给出；仅当 a_eff = a_brake + g·(permille/1000) 计算值更小
     * （如下坡 permille<0 使 a_eff 变小、或坡度/位置数据缺失）时，用此下限避免 tBrake 过大/除零。
     * 属偏保守 fail-safe，不代表车辆能停下的真实能力。
     */
    public double aFloorMps2 = 0.8;
    /** 线路默认限速 km/h（无具体限速表时取用） */
    public double defaultLineSpeedKmh = 80.0;
    /** 信号机前停车余量 m（EoA 止于机前留出） */
    public double signalStopMarginM = 10.0;
    /** fail-safe 降级时允许的最大速度 km/h（取 0 = 必须停车） */
    public double degradedSpeedKmh = 0.0;
    /**
     * MA 有效期 s：列车上报时间戳距 now 超过该值视为过期 → 降级（仅带 now 的重载生效）。
     * 对齐协议 ATP 报文时效 1.6s（D3）；旧默认 5.0 偏松。
     */
    public double maValiditySec = 1.6;

    /**
     * A4 载重折减系数 k（0~1）。
     * <p>有效制动减速度 aEff = aBrake * (1 - k * load) + g * permille/1000，
     * load=0 时 aEff=aBrake（与旧公式一致），load=1 时 aEff=aBrake*(1-k)（重载 gap 更大）。
     * <p>默认 0.2，避免 gap 爆炸；KISS——单一系数近似质量增大对制动距离的影响。
     */
    public double loadFactorK = 0.2;

    public MaConfig() {}

    /** 复现 §2.5 数值算例：t_total=5s → requiredGap=110m（坡度平） */
    public static MaConfig exampleConfig() {
        MaConfig c = new MaConfig();
        c.safeSeparationM = 20.0;
        c.tReactionS = 2.0;
        c.tSafetyMarginS = 1.0;
        c.aBrakeMps2 = 9.0;
        c.aFloorMps2 = 0.8;
        c.defaultLineSpeedKmh = 80.0;
        c.signalStopMarginM = 10.0;
        c.degradedSpeedKmh = 0.0;
        c.maValiditySec = 1.6;
        c.loadFactorK = 0.2;
        return c;
    }
}
