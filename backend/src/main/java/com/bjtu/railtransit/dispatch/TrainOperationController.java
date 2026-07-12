package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.common.ApiResponse;
import com.bjtu.railtransit.domain.model.TrainState;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/dispatch/trains")
public class TrainOperationController {
    private final SimulationService simulationService;

    public TrainOperationController(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @PostMapping
    public ApiResponse<TrainState> add(@RequestBody Map<String, Object> body) {
        try {
            String trainId = String.valueOf(body.getOrDefault("trainId", "T1"));
            int linkId = number(body.get("headLinkId"), 1);
            String direction = String.valueOf(body.getOrDefault("direction", "UP"));
            int stationId = number(body.get("stationId"), "DOWN".equalsIgnoreCase(direction) ? 13 : 1);
            String routePattern = String.valueOf(body.getOrDefault("routePattern", "FULL"));
            return ApiResponse.ok("列车已添加", simulationService.addTrain(
                    trainId, linkId, direction, stationId, routePattern));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @DeleteMapping("/{trainId}")
    public ApiResponse<TrainState> remove(@PathVariable String trainId) {
        try { return ApiResponse.ok("列车已删除", simulationService.removeTrain(trainId)); }
        catch (Exception e) { return ApiResponse.error(e.getMessage()); }
    }

    @DeleteMapping
    public ApiResponse<Map<String, Object>> clear() {
        int count = simulationService.clearTrains();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("removed", count);
        return ApiResponse.ok("已清空全部列车", result);
    }

    @PostMapping("/{trainId}/route-pattern")
    public ApiResponse<TrainState> routePattern(@PathVariable String trainId,
                                                @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.ok("交路已设置", simulationService.setTrainRoutePattern(
                    trainId, String.valueOf(body.getOrDefault("routePattern", "FULL"))));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }
}

