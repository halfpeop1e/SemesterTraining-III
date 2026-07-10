package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.common.ApiResponse;
import com.bjtu.railtransit.domain.dto.SimulationRequest;
import com.bjtu.railtransit.domain.dto.StrategyRequest;
import com.bjtu.railtransit.domain.model.BrakingSystemState;
import com.bjtu.railtransit.domain.model.DispatchPlan;
import com.bjtu.railtransit.domain.model.SimulationLog;
import com.bjtu.railtransit.domain.model.SimulationSnapshot;
import com.bjtu.railtransit.domain.model.StationGeo;
import com.bjtu.railtransit.domain.model.TractionSystemState;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DispatchController {

    private final SimulationService simulationService;
    private final LineDataService lineDataService;

    public DispatchController(SimulationService simulationService, LineDataService lineDataService) {
        this.simulationService = simulationService;
        this.lineDataService = lineDataService;
    }

    @PostMapping("/simulations/start")
    public ApiResponse<String> startSimulation(@RequestBody SimulationRequest request) {
        simulationService.startSimulation();
        return ApiResponse.ok("simulation started",
                "Simulation started with duration " + request.getSimulationDuration() + "s");
    }

    @PostMapping("/simulations/step")
    public ApiResponse<String> stepSimulation(@RequestBody(required = false) SimulationRequest request) {
        return ApiResponse.error("Simulation clock is backend-owned; use start/pause/reset");
    }

    @PostMapping("/simulations/pause")
    public ApiResponse<String> pauseSimulation() {
        simulationService.pauseSimulation();
        return ApiResponse.ok("simulation paused", "Authoritative clock paused");
    }

    @PostMapping("/simulations/reset")
    public ApiResponse<String> resetSimulation() {
        simulationService.resetSimulation();
        return ApiResponse.ok("simulation reset", "All simulation state cleared");
    }

    @GetMapping("/simulations/snapshot")
    public ApiResponse<SimulationSnapshot> getSnapshot() {
        if (!simulationService.isRunning()) {
            return ApiResponse.ok("simulation not running", null);
        }
        SimulationSnapshot snapshot = simulationService.getSnapshot();
        return ApiResponse.ok("snapshot retrieved", snapshot);
    }

    @GetMapping("/dispatch/plan")
    public ApiResponse<DispatchPlan> getDispatchPlan() {
        DispatchPlan plan = simulationService.getDispatchPlan();
        return ApiResponse.ok("dispatch plan", plan);
    }

    @GetMapping("/dispatch/line-map")
    public ApiResponse<List<StationGeo>> getLineMap() {
        List<StationGeo> geoList = lineDataService.getStationGeoList();
        return ApiResponse.ok("line map", geoList);
    }

    @PostMapping("/dispatch/strategy")
    public ApiResponse<String> applyStrategy(@RequestBody StrategyRequest request) {
        if (!simulationService.isRunning()) {
            return ApiResponse.ok("simulation not running", null);
        }
        simulationService.applyStrategy(request.getTrainId(), request.getStrategyType(), request.getTargetValue());
        return ApiResponse.ok("strategy applied",
                "Strategy " + request.getStrategyType() + " applied to " + request.getTrainId());
    }

    @GetMapping("/simulations/logs")
    public ApiResponse<List<SimulationLog>> getSimulationLogs() {
        List<SimulationLog> logs = simulationService.getSimulationLogs();
        return ApiResponse.ok("simulation logs", logs);
    }

    // ================================================================
    // CBTC 执行层: 故障注入 (对应 doc.md §1 多车司控台)
    // ================================================================

    @PostMapping("/dispatch/fault/inject")
    public ApiResponse<Map<String, Object>> injectFault(@RequestBody Map<String, Object> body) {
        String trainId = String.valueOf(body.get("trainId"));
        String faultType = String.valueOf(body.get("faultType"));
        int severity = body.containsKey("severity") ? ((Number) body.get("severity")).intValue() : 4;
        simulationService.injectFault(trainId, faultType, severity);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("trainId", trainId);
        result.put("faultType", faultType);
        result.put("status", "INJECTED");
        return ApiResponse.ok("fault injected", result);
    }

    @PostMapping("/dispatch/fault/clear")
    public ApiResponse<Map<String, Object>> clearFault(@RequestBody Map<String, Object> body) {
        String trainId = String.valueOf(body.get("trainId"));
        String faultType = String.valueOf(body.get("faultType"));
        simulationService.clearFault(trainId, faultType);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("trainId", trainId);
        result.put("faultType", faultType);
        result.put("status", "CLEARED");
        return ApiResponse.ok("fault cleared", result);
    }

    @GetMapping("/dispatch/states")
    public ApiResponse<Map<String, Object>> getSystemStates() {
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, TractionSystemState> traction = simulationService.getTractionStates();
        Map<String, BrakingSystemState> brake = simulationService.getBrakeStates();
        data.put("traction", traction);
        data.put("brake", brake);
        return ApiResponse.ok("system states", data);
    }

    @GetMapping("/dispatch/power/status")
    public ApiResponse<Map<String, Object>> getPowerStatus() {
        return ApiResponse.ok("power supply", simulationService.getPowerStates());
    }

    @GetMapping("/dispatch/network/status")
    public ApiResponse<Map<String, Object>> getNetworkStatus() {
        return ApiResponse.ok("train network", simulationService.getNetworkStates());
    }
}
