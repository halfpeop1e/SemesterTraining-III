package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.common.ApiResponse;
import com.bjtu.railtransit.domain.model.TrainState;
import com.bjtu.railtransit.signal.service.SignalInterlockingService;
import com.bjtu.railtransit.vehicle.protocol704.Protocol704Service;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dispatch/trains")
public class TrainOperationController {
    private final SimulationService simulationService;
    private final Protocol704Service protocol704Service;
    private final SignalInterlockingService signalInterlockingService;

    public TrainOperationController(SimulationService simulationService,
                                    Protocol704Service protocol704Service,
                                    SignalInterlockingService signalInterlockingService) {
        this.simulationService = simulationService;
        this.protocol704Service = protocol704Service;
        this.signalInterlockingService = signalInterlockingService;
    }

    @PostMapping
    public ApiResponse<TrainState> add(@RequestBody Map<String, Object> body) {
        try {
            String trainId = String.valueOf(body.getOrDefault("trainId", "T1"));
            int linkId = number(body.get("headLinkId"), 1);
            String direction = String.valueOf(body.getOrDefault("direction", "UP"));
            int stationId = number(body.get("stationId"), "DOWN".equalsIgnoreCase(direction) ? 13 : 1);
            int destinationStationId = number(body.get("destinationStationId"), 0);
            String routePattern = String.valueOf(body.getOrDefault("routePattern", "FULL"));
            return ApiResponse.ok("列车已添加", simulationService.addTrain(
                    trainId, linkId, direction, stationId, routePattern, destinationStationId));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @DeleteMapping("/{trainId}")
    public ApiResponse<TrainState> remove(@PathVariable String trainId) {
        try {
            ensureTrainExists(trainId);
            cleanupExternalBindings(trainId);
            return ApiResponse.ok("列车已下线并删除", simulationService.removeTrain(trainId));
        }
        catch (Exception e) { return ApiResponse.error(e.getMessage()); }
    }

    @PostMapping("/{trainId}/start")
    public ApiResponse<TrainState> start(@PathVariable String trainId) {
        try {
            TrainState train = simulationService.findTrain(trainId);
            if (train == null) throw new IllegalArgumentException("列车不存在: " + trainId);
            return ApiResponse.ok("ATO 发车请求已提交，等待信号与移动授权", simulationService.requestDeparture(trainId));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @DeleteMapping
    public ApiResponse<Map<String, Object>> clear() {
        var trainIds = new ArrayList<>(simulationService.getTrainIds());
        for (String trainId : trainIds) {
            cleanupExternalBindings(trainId);
            simulationService.removeTrain(trainId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("removed", trainIds.size());
        return ApiResponse.ok("已清空全部列车", result);
    }

    private void ensureTrainExists(String trainId) {
        if (simulationService.findTrain(trainId) == null) {
            throw new IllegalArgumentException("列车不存在: " + trainId);
        }
    }

    /**
     * A line-diagram delete is a full operational offlining action.  Do this before
     * removing dispatch state so an active PLC client cannot retain a stale target.
     */
    private void cleanupExternalBindings(String trainId) {
        protocol704Service.reset(trainId);
        signalInterlockingService.unassignRoute(trainId);
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

