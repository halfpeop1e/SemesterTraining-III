package com.bjtu.railtransit.dispatch.dto;

/**
 * 侧线释放请求体（POST /api/dispatch/siding/release）。
 */
public class SidingReleaseRequest {

    /** 需要释放侧线的列车编号。 */
    private String trainId;

    public SidingReleaseRequest() {
    }

    public SidingReleaseRequest(String trainId) {
        this.trainId = trainId;
    }

    public String getTrainId() {
        return trainId;
    }

    public void setTrainId(String trainId) {
        this.trainId = trainId;
    }
}
