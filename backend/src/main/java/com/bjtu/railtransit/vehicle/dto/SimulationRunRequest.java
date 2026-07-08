package com.bjtu.railtransit.vehicle.dto;

/**
 * POST /api/vehicle/simulation/run 可选请求体（阶段 4B 线路数据真实化 + 多站连续仿真）。
 *
 * <p>请求体允许为空（{@code @RequestBody(required=false)}），为空时 controller
 * 使用默认站点 fromStationId=1（郭公庄）、toStationId=2（丰台科技园）。</p>
 *
 * <p>多站扩展：当 toStationId > fromStationId+1 时，后端自动按相邻区间连续仿真：
 * from→from+1→...→to，每站驻留 dwellTimeSeconds 秒（默认 30s）。</p>
 */
public class SimulationRunRequest {

    /**
     * 出发站 id，对应 configs/line-profile.json 中 stations[].id。
     * 默认 1（郭公庄）。
     */
    private Integer fromStationId;

    /**
     * 目标站 id，对应 configs/line-profile.json 中 stations[].id。
     * 默认 2（丰台科技园）。
     */
    private Integer toStationId;

    /**
     * 中间站驻留时间，单位 s（多站仿真新增）。
     *
     * <p>为 null 时后端使用默认值 30s。将来可由运行图下发具体驻留时间，
     * DTO 字段在此预留扩展点，不写死在前端。</p>
     */
    private Double dwellTimeSeconds;

    public SimulationRunRequest() {
    }

    public SimulationRunRequest(Integer fromStationId, Integer toStationId) {
        this.fromStationId = fromStationId;
        this.toStationId = toStationId;
    }

    public SimulationRunRequest(Integer fromStationId, Integer toStationId, Double dwellTimeSeconds) {
        this.fromStationId = fromStationId;
        this.toStationId = toStationId;
        this.dwellTimeSeconds = dwellTimeSeconds;
    }

    public Integer getFromStationId() {
        return fromStationId;
    }

    public void setFromStationId(Integer fromStationId) {
        this.fromStationId = fromStationId;
    }

    public Integer getToStationId() {
        return toStationId;
    }

    public void setToStationId(Integer toStationId) {
        this.toStationId = toStationId;
    }

    public Double getDwellTimeSeconds() {
        return dwellTimeSeconds;
    }

    public void setDwellTimeSeconds(Double dwellTimeSeconds) {
        this.dwellTimeSeconds = dwellTimeSeconds;
    }

    /** 取出有效的起始站 id，null 时返回默认值 1。 */
    public int resolvedFromId() {
        return fromStationId != null ? fromStationId : 1;
    }

    /** 取出有效的终止站 id，null 时返回默认值 2。 */
    public int resolvedToId() {
        return toStationId != null ? toStationId : 2;
    }

    /** 取出有效的驻留时间，null 时返回默认 30s。 */
    public double resolvedDwellTime() {
        return dwellTimeSeconds != null ? dwellTimeSeconds : 30.0;
    }
}
