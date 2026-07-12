package com.bjtu.railtransit.dispatch.dto;

/**
 * 侧线撤离请求体（POST /api/dispatch/siding/request）。
 */
public class SidingEntryRequest {

    /** 需要撤离的列车编号。 */
    private String trainId;

    /** 故障车所在站点 id（1~13）。 */
    private int stationId;

    public SidingEntryRequest() {
    }

    public SidingEntryRequest(String trainId, int stationId) {
        this.trainId = trainId;
        this.stationId = stationId;
    }

    public String getTrainId() {
        return trainId;
    }

    public void setTrainId(String trainId) {
        this.trainId = trainId;
    }

    public int getStationId() {
        return stationId;
    }

    public void setStationId(int stationId) {
        this.stationId = stationId;
    }
}
