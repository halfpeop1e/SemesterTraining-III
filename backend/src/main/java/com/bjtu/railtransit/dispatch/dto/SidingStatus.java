package com.bjtu.railtransit.dispatch.dto;

/**
 * 单个站点侧线占用状态。
 *
 * <p>状态机：
 * <pre>
 *   AVAILABLE → (requestSidingEntry) → RESERVED → (confirmOccupied) → OCCUPIED → (releaseSiding) → AVAILABLE
 * </pre>
 * </p>
 *
 * <p>13 站各一条侧线，简化为单车辆容量。</p>
 */
public class SidingStatus {

    /** 站点 id（1~13，对应 configs/line-profile.json stations[].id）。 */
    private int stationId;

    /** 站点中文名（来自 configs/line-profile.json stations[].name）。 */
    private String stationName;

    /**
     * 侧线占用状态：
     * <ul>
     *   <li>AVAILABLE：空闲，可接受撤离请求</li>
     *   <li>RESERVED：已预留，等待车辆驶入</li>
     *   <li>OCCUPIED：车辆已停入侧线</li>
     * </ul>
     */
    private String status;

    /** 当前占用侧线的列车编号，AVAILABLE 时为 null。 */
    private String occupiedTrainId;

    /** 预留时刻（epoch 毫秒），AVAILABLE 时为 null。 */
    private Long reservedAt;

    public SidingStatus() {
    }

    public SidingStatus(int stationId, String stationName, String status) {
        this.stationId = stationId;
        this.stationName = stationName;
        this.status = status;
    }

    public int getStationId() {
        return stationId;
    }

    public void setStationId(int stationId) {
        this.stationId = stationId;
    }

    public String getStationName() {
        return stationName;
    }

    public void setStationName(String stationName) {
        this.stationName = stationName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getOccupiedTrainId() {
        return occupiedTrainId;
    }

    public void setOccupiedTrainId(String occupiedTrainId) {
        this.occupiedTrainId = occupiedTrainId;
    }

    public Long getReservedAt() {
        return reservedAt;
    }

    public void setReservedAt(Long reservedAt) {
        this.reservedAt = reservedAt;
    }
}
