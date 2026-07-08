package com.bjtu.railtransit.signal.web;

import com.bjtu.railtransit.common.ApiResponse;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.bjtu.railtransit.signal.domain.TrainState;
import com.bjtu.railtransit.signal.model.LineProfile;
import com.bjtu.railtransit.signal.service.MovingAuthorityService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 移动授权 REST 接口。
 *  - `POST /api/signal/ma`，入参 MaRequest{lineProfile, trains[, routes, nowSec]}
 *  - 返回真实 ApiResponse{success, message, data}，data 为 Map<trainId, MovingAuthority>
 *  - 解析/计算异常 → success=false + 错误信息，不抛 500
 */
@RestController
@RequestMapping("/api/signal")
public class SignalController {

    private final MovingAuthorityService maService;

    public SignalController(MovingAuthorityService maService) {
        this.maService = maService;
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
