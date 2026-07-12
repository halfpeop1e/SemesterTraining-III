package com.bjtu.railtransit.vehicle.dto;

/**
 * 侧线驶入请求体（POST /api/vehicle/siding/enter）。
 *
 * <p>由车载模块调用：在 dispatch 层预留侧线成功后，基于列车当前状态续算一段
 * 驶入侧线的状态序列供前端播放。</p>
 */
public class SidingEnterRequest {

    /** 需要驶入侧线的列车编号。 */
    private String trainId;

    /** 目标侧线所在站点 id（1~13）。 */
    private int stationId;

    /** 列车当前状态（位置、速度、时刻），作为驶入侧线仿真的起始点。 */
    private TrainState currentState;

    public SidingEnterRequest() {
    }

    public SidingEnterRequest(String trainId, int stationId, TrainState currentState) {
        this.trainId = trainId;
        this.stationId = stationId;
        this.currentState = currentState;
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

    public TrainState getCurrentState() {
        return currentState;
    }

    public void setCurrentState(TrainState currentState) {
        this.currentState = currentState;
    }
}
