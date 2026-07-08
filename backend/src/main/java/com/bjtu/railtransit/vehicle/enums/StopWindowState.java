package com.bjtu.railtransit.vehicle.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 停车窗到位状态（阶段 1.6 704 语义对齐新增枚举）。
 *
 * <p>对应 704《轨交多系统平台接口协议汇总》表13「停车窗到位状态：窗内/冲标/欠标/未停准」。
 * 本枚举只做语义镜像，不引入任何 704 网络/字节序/大小端相关代码，也不改变阶段 1 既有的
 * {@code StopResult.success}/{@code stopError} 判定逻辑本身，只是把同一份误差数值换一种
 * 更贴近 704 术语的方式再表达一次，供前端展示。</p>
 *
 * <p>派生规则（由 service 依据 {@code stopError} 和末速度计算，不写死）：</p>
 * <ul>
 *   <li>|stopError| &le; 0.5 且 末速度 &le; 0.1 -&gt; {@link #IN_WINDOW}（窗内）</li>
 *   <li>stopError &gt; 0.5 且 末速度 &le; 0.1 -&gt; {@link #OVERSHOOT}（冲标）</li>
 *   <li>stopError &lt; -0.5 且 末速度 &le; 0.1 -&gt; {@link #UNDERSHOOT}（欠标）</li>
 *   <li>末速度 &gt; 0.1 -&gt; {@link #NOT_ACCURATE}（未停准）</li>
 * </ul>
 */
public enum StopWindowState {

    /** 停车窗内（对应 704 表13「窗内」）。 */
    IN_WINDOW("in_window"),

    /** 冲标：越过停车窗上限（对应 704 表13「冲标」）。 */
    OVERSHOOT("overshoot"),

    /** 欠标：未到停车窗下限（对应 704 表13「欠标」）。 */
    UNDERSHOOT("undershoot"),

    /** 未停准：末速度未收敛到判定阈值内，尚不能认定为已停稳（对应 704 表13「未停准」）。 */
    NOT_ACCURATE("not_accurate");

    private final String code;

    StopWindowState(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    @Override
    public String toString() {
        return code;
    }
}
