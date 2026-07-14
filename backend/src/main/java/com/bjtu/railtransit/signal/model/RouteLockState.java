package com.bjtu.railtransit.signal.model;

/**
 * 进路锁闭状态枚举。
 * 对齐工业 CBI 标准：选排 → 锁闭 → 开放信号 → 接近锁闭 → 占用 → 释放完成。
 */
public enum RouteLockState {
    /** 已请求，等待条件检查 */
    REQUESTED,
    /** 选排中：征用道岔和区段，等待道岔转换到位 */
    SELECTING,
    /** 已锁闭：道岔到位、区段锁闭，等待开放信号 */
    LOCKED,
    /** 信号已开放 */
    SIGNAL_OPEN,
    /** 接近锁闭：列车已进入接近区段，取消需延时 */
    APPROACH_LOCKED,
    /** 列车已占用进路 */
    OCCUPIED,
    /** 释放中 */
    RELEASING,
    /** 已完成 */
    COMPLETED,
    /** 已取消（无接近锁闭时） */
    CANCELLED,
    /** 已拒绝（条件不满足） */
    REJECTED,
    /** 已失败（设备故障） */
    FAILED
}
