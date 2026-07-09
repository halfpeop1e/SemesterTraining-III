package com.bjtu.railtransit.vehicle.dto;

/**
 * POST /api/vehicle/simulation/run 可选请求体（阶段 4B 线路数据真实化）。
 *
 * <p>请求体允许为空（{@code @RequestBody(required=false)}），为空时 controller
 * 使用默认站点 fromStationId=1（郭公庄）、toStationId=2（丰台科技园）。</p>
 *
 * <p>约束：
 * <ul>
 *   <li>两个 id 必须存在于 configs/line-profile.json。</li>
 *   <li>toStationId 必须大于 fromStationId（本轮只支持正向运行）。</li>
 *   <li>违反约束时 service 抛出 {@link IllegalArgumentException}，controller 返回失败响应。</li>
 * </ul>
 * </p>
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

    public SimulationRunRequest() {
    }

    public SimulationRunRequest(Integer fromStationId, Integer toStationId) {
        this.fromStationId = fromStationId;
        this.toStationId = toStationId;
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

    /** 取出有效的起始站 id，null 时返回默认值 1。 */
    public int resolvedFromId() {
        return fromStationId != null ? fromStationId : 1;
    }

    /** 取出有效的终止站 id，null 时返回默认值 2。 */
    public int resolvedToId() {
        return toStationId != null ? toStationId : 2;
    }

    /**
     * 中间站驻留时间，单位 s（多站仿真新增可选字段）。
     *
     * <p>null 时后端使用默认值 30s。将来可由运行图下发具体驻留时间，
     * 此字段是扩展点，不写死在前端。</p>
     */
    private Double dwellTimeSeconds;

    public Double getDwellTimeSeconds() {
        return dwellTimeSeconds;
    }

    public void setDwellTimeSeconds(Double dwellTimeSeconds) {
        this.dwellTimeSeconds = dwellTimeSeconds;
    }

    /** 取出有效的驻留时间，null 时返回默认 30s。 */
    public double resolvedDwellTime() {
        return dwellTimeSeconds != null ? dwellTimeSeconds : 30.0;
    }
}
