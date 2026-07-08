package com.bjtu.railtransit.signal.domain;

/**
 * 信号机显示状态。
 *
 * <p>与司机台协议对齐（仅 MA 模块 scope）：
 * <ul>
 *   <li>本枚举的 {@code code} 取协议 <b>3.3.2「信号系统⇒总控」</b>那套编码作为 MA 内部权威
 *       （MA 属信号系统，是该状态的生产方）。</li>
 *   <li>{@link #toDriverConsoleScreen()} 提供到「司机台信号屏」显示编码的映射——
 *       司机台信号屏仅 红(0x01)/绿(0x02)/白(0x03) 三类显示，故做聚合（放行→绿、调车白灯→白、其余→红，fail-safe）。</li>
 *   <li>{@link #isProceed()} 用于 {@code eoaFromSignals}：放行显示不截断 EoA，停车/限制/灭/断一律截断。</li>
 * </ul>
 *
 * <p>注意：协议文档内信号机编码出现 3 套（3.3.2 / 视景 TCMS2VIEW / 视景表2），
 * 另两套由总控/视景侧各自解码，不进 MA 核心。
 */
public enum SignalAspect {
    RED(0x01),              // 红灯
    YELLOW(0x02),           // 黄灯
    RED_YELLOW(0x03),       // 红黄灯
    GREEN(0x04),            // 绿灯
    YELLOW_DARK(0x05),      // 黄灭
    RED_DARK(0x06),         // 红灭
    GREEN_DARK(0x07),       // 绿灭
    WHITE(0x08),            // 白灯
    BLUE(0x0A),             // 蓝灯
    RED_BROKEN(0x09),       // 红断
    GREEN_BROKEN(0x10),     // 绿断
    YELLOW_BROKEN(0x20),    // 黄断
    WHITE_BROKEN(0x30);     // 白断

    private final int code;

    SignalAspect(int code) { this.code = code; }

    public int getCode() { return code; }

    /**
     * 协议 3.3.2 解码（信号系统⇒总控 权威编码）。未知字节 → null（fail-safe，按停车处理）。
     */
    public static SignalAspect decodeSignalSystem(int code) {
        int b = code & 0xFF;
        for (SignalAspect a : values()) {
            if (a.code == b) return a;
        }
        return null;
    }

    /**
     * 是否允许列车越过的放行显示。保守取值：仅 {@link #GREEN} 为放行；
     * 其余（红/红黄/黄/蓝/白/灭/断）一律视为不可越过。
     * 如运营规则允许绿黄放行，可在此处扩展。
     */
    public boolean isProceed() {
        return this == GREEN;
    }

    /**
     * 到「司机台信号屏」显示编码的聚合映射（协议：0x01 红 / 0x02 绿 / 0x03 白）。
     * GREEN→绿；WHITE→白；其余全部→红（最严格，fail-safe）。
     */
    public int toDriverConsoleScreen() {
        if (this == GREEN)  return 0x02;   // 绿
        if (this == WHITE)  return 0x03;   // 白
        return 0x01;                       // 红（默认最严格）
    }
}
