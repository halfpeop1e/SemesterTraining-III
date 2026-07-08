package com.bjtu.railtransit.signal.web;

import com.bjtu.railtransit.signal.domain.TrainState;
import com.bjtu.railtransit.signal.model.LineProfile;
import com.bjtu.railtransit.signal.model.Route;

import java.util.List;
import java.util.Map;

/**
 * `POST /api/signal/ma` 入参。
 * 字段名对齐 verification.md §3 的 curl body：{ "lineProfile": {...}, "trains": [...] }。
 */
public class MaRequest {

    private LineProfile lineProfile;
    private List<TrainState> trains;
    /** 可选：按 trainId 分配的已建立进路（用于 ROUTE_END/OVERLAP_END 授权） */
    private Map<String, Route> routes;
    /** 可选：当前仿真时刻(s)，用于 MA 过期判定（缺省不做过期检查） */
    private Double nowSec;

    public LineProfile getLineProfile() { return lineProfile; }
    public void setLineProfile(LineProfile lineProfile) { this.lineProfile = lineProfile; }

    public List<TrainState> getTrains() { return trains; }
    public void setTrains(List<TrainState> trains) { this.trains = trains; }

    public Map<String, Route> getRoutes() { return routes; }
    public void setRoutes(Map<String, Route> routes) { this.routes = routes; }

    public Double getNowSec() { return nowSec; }
    public void setNowSec(Double nowSec) { this.nowSec = nowSec; }
}
