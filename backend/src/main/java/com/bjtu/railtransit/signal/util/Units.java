package com.bjtu.railtransit.signal.util;

/**
 * 单位换算工具（导入层）。真实线路数据口径 → 内部契约口径。
 * 内部契约：速度 km/h、位置 m、坡度 ‰（见 tech-design.md §1.1.1）。
 */
public final class Units {
    private Units() {}

    /** 1 cm/s = 0.036 km/h */
    public static final double CMPS_TO_KMH = 0.036;

    public static double cmToM(double cm) { return cm / 100.0; }
    public static double mToCm(double m) { return m * 100.0; }

    /** 限速/道岔侧向限速：cm/s → km/h */
    public static double cmpsToKmh(double cmps) { return cmps * CMPS_TO_KMH; }
    /** 反向：km/h → cm/s */
    public static double kmhToCmps(double kmh) { return kmh / CMPS_TO_KMH; }

    /** km/h → m/s（内部制动/间隔计算用） */
    public static double kmhToMps(double kmh) { return kmh / 3.6; }
    /** m/s → km/h */
    public static double mpsToKmh(double mps) { return mps * 3.6; }

    /**
     * 坡度：教师 CBTC 导出表“坡度值”字段以 0.1‰ 为整数单位（如 350 表示 35.0‰），
     * 换算为内部契约口径（真‰）。MA 制动修正公式 aEff = aBrake + G*(permille/1000) 要求真‰。
     */
    public static double tenthsPermilleToPermille(double tenths) { return tenths / 10.0; }

    /**
     * 解析站台中心公里标，如 "K0+313.000" / "k2+448.610" → 绝对里程 m。
     * 格式：K<km>+<m>，支持大小写 k。
     */
    public static double parseChainage(String chainage) {
        if (chainage == null) return 0.0;
        String s = chainage.trim();
        if (s.toLowerCase().startsWith("k")) {
            s = s.substring(1);
        }
        String[] parts = s.split("\\+");
        if (parts.length == 2) {
            double km = Double.parseDouble(parts[0]);
            double m = Double.parseDouble(parts[1]);
            return km * 1000.0 + m;
        }
        // 退化：直接解析数字
        return Double.parseDouble(s);
    }
}
