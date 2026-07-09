package com.bjtu.railtransit.signal.web;

import com.bjtu.railtransit.common.ApiResponse;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.bjtu.railtransit.signal.model.LineProfile;
import com.bjtu.railtransit.signal.service.LineProfileLoader;
import com.bjtu.railtransit.signal.service.MovingAuthorityService;
import com.bjtu.railtransit.signal.service.MovementAuthorityRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collections;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 移动授权 REST 接口。
 *  - `POST /api/signal/ma`，入参 MaRequest{lineProfile, trains[, routes, nowSec]}
 *    返回真实 ApiResponse{success, message, data}，data 为 Map<trainId, MovingAuthority>
 *  - `GET /api/signal/line`，返回换算好的 LineProfile（含里程索引），供前端画平面图
 *  - 解析/计算异常 → success=false + 错误信息，不抛 500
 */
@RestController
@RequestMapping("/api/signal")
public class SignalController {

    private final MovingAuthorityService maService;
    private final LineProfileLoader lineProfileLoader;
    private final MovementAuthorityRegistry registry;

    @Autowired
    public SignalController(MovingAuthorityService maService, LineProfileLoader lineProfileLoader,
                            MovementAuthorityRegistry registry) {
        this.maService = maService;
        this.lineProfileLoader = lineProfileLoader;
        this.registry = registry;
    }

    /** Backward-compatible constructor for the existing standalone verification harnesses. */
    public SignalController(MovingAuthorityService maService, LineProfileLoader lineProfileLoader) {
        this(maService, lineProfileLoader, new MovementAuthorityRegistry());
    }

    @GetMapping("/ma/latest")
    public ApiResponse<Map<String, MovingAuthority>> latestMa() {
        return ApiResponse.ok("authoritative MA", registry.snapshot());
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("onlineTrains", registry.snapshot().size());
        status.put("alertCount", registry.snapshot().values().stream()
                .filter(ma -> ma.getEvent() != null
                        && ma.getEvent() != com.bjtu.railtransit.signal.domain.SignalEvent.NONE)
                .count());
        status.put("health", registry.getGeneration() == 0 ? "OFFLINE" : "HEALTHY");
        status.put("simulationTime", registry.getLastCycleTimeSeconds());
        status.put("generation", registry.getGeneration());
        status.put("source", registry.getSource());
        return ApiResponse.ok("signal runtime", status);
    }

    @GetMapping("/line")
    public ApiResponse<LineProfile> getLine() {
        try {
            LineProfile lp = lineProfileLoader.loadFromClasspath("line-profile.json");
            return ApiResponse.ok("", lp);
        } catch (Exception e) {
            return new ApiResponse<>(false, "load line failed: " + e.getMessage(), null);
        }
    }

    @PostMapping("/ma")
    public ApiResponse<Map<String, MovingAuthority>> ma(@RequestBody MaRequest req) {
        try {
            if (req == null || req.getLineProfile() == null || req.getTrains() == null) {
                return new ApiResponse<>(false, "invalid request: lineProfile and trains are required", null);
            }
            Map<String, com.bjtu.railtransit.signal.model.Route> routes =
                    req.getRoutes() != null ? req.getRoutes() : Collections.emptyMap();
            Map<String, MovingAuthority> data;
            if (req.getNowSec() != null) {
                data = maService.compute(req.getLineProfile(), req.getTrains(), routes, req.getNowSec());
            } else {
                data = maService.compute(req.getLineProfile(), req.getTrains(), routes);
            }
            return ApiResponse.ok("", data);
        } catch (Exception e) {
            return new ApiResponse<>(false, "compute failed: " + e.getMessage(), null);
        }
    }
}
