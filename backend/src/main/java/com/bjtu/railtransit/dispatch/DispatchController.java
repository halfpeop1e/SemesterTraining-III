package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.common.ApiResponse;
import com.bjtu.railtransit.domain.dto.EnergySnapshot;
import com.bjtu.railtransit.domain.dto.SimulationRequest;
import com.bjtu.railtransit.domain.model.*;

import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class DispatchController {

    private final SimulationService simulationService;
    private final LineDataService lineDataService;
    private final EnergyIntegrationService energyIntegrationService;

    public DispatchController(SimulationService simulationService,
                              LineDataService lineDataService,
                              EnergyIntegrationService energyIntegrationService) {
        this.simulationService = simulationService;
        this.lineDataService = lineDataService;
        this.energyIntegrationService = energyIntegrationService;
    }

    @PostMapping("/simulations/start")
    public ApiResponse<String> startSimulation(@RequestBody SimulationRequest request) {
        simulationService.startSimulation(request.getSimulationDuration());
        return ApiResponse.ok("simulation started", "Simulation started with duration " + request.getSimulationDuration() + "s");
    }

    @PostMapping("/simulations/step")
    public ApiResponse<String> stepSimulation(@RequestBody(required = false) SimulationRequest request) {
        if (!simulationService.isRunning()) {
            return ApiResponse.ok("simulation not running", "Please start the simulation first");
        }
        int steps = (request != null && request.getSteps() > 0) ? request.getSteps() : 1;
        simulationService.stepSimulation(steps);
        return ApiResponse.ok("step executed", "Simulation advanced by " + steps + " second(s)");
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

    @GetMapping("/dispatch/track-geometry")
    public ApiResponse<TrackGeometry> getTrackGeometry() {
        TrackGeometry geometry = lineDataService.getTrackGeometry();
        return ApiResponse.ok("track geometry", geometry);
    }

    // ============ NEW: Full line data endpoints ============

    @GetMapping("/dispatch/signals")
    public ApiResponse<List<LineData.Signal>> getSignals() {
        LineData ld = lineDataService.getLineData();
        return ApiResponse.ok("signals", ld.getSignals());
    }

    @GetMapping("/dispatch/switches")
    public ApiResponse<List<LineData.Switch>> getSwitches() {
        LineData ld = lineDataService.getLineData();
        return ApiResponse.ok("switches", ld.getSwitches());
    }

    @GetMapping("/dispatch/routes")
    public ApiResponse<List<LineData.Route>> getRoutes() {
        LineData ld = lineDataService.getLineData();
        return ApiResponse.ok("routes", ld.getRoutes());
    }

    @GetMapping("/dispatch/balises")
    public ApiResponse<List<LineData.Balise>> getBalises() {
        LineData ld = lineDataService.getLineData();
        return ApiResponse.ok("balises", ld.getBalises());
    }

    @GetMapping("/dispatch/speed-limits")
    public ApiResponse<List<LineData.StaticSpeedLimit>> getSpeedLimits() {
        LineData ld = lineDataService.getLineData();
        return ApiResponse.ok("speed-limits", ld.getStaticSpeedLimits());
    }

    @GetMapping("/dispatch/gradients")
    public ApiResponse<List<LineData.Gradient>> getGradients() {
        LineData ld = lineDataService.getLineData();
        return ApiResponse.ok("gradients", ld.getGradients());
    }

    @GetMapping("/dispatch/tunnels")
    public ApiResponse<List<LineData.Tunnel>> getTunnels() {
        LineData ld = lineDataService.getLineData();
        return ApiResponse.ok("tunnels", ld.getTunnels());
    }

    @GetMapping("/dispatch/bumpers")
    public ApiResponse<List<LineData.Bumper>> getBumpers() {
        LineData ld = lineDataService.getLineData();
        return ApiResponse.ok("bumpers", ld.getBumpers());
    }

    @GetMapping("/dispatch/flood-gates")
    public ApiResponse<List<LineData.FloodGate>> getFloodGates() {
        LineData ld = lineDataService.getLineData();
        return ApiResponse.ok("flood-gates", ld.getFloodGates());
    }

    @GetMapping("/dispatch/platform-doors")
    public ApiResponse<List<LineData.PlatformDoor>> getPlatformDoors() {
        LineData ld = lineDataService.getLineData();
        return ApiResponse.ok("platform-doors", ld.getPlatformDoors());
    }

    @GetMapping("/dispatch/emergency-buttons")
    public ApiResponse<List<LineData.EmergencyButton>> getEmergencyButtons() {
        LineData ld = lineDataService.getLineData();
        return ApiResponse.ok("emergency-buttons", ld.getEmergencyButtons());
    }

    @GetMapping("/dispatch/spks-switches")
    public ApiResponse<List<LineData.SpksSwitch>> getSpksSwitches() {
        LineData ld = lineDataService.getLineData();
        return ApiResponse.ok("spks-switches", ld.getSpksSwitches());
    }

    @GetMapping("/dispatch/axle-counter-sections")
    public ApiResponse<List<LineData.AxleCounterSection>> getAxleCounterSections() {
        LineData ld = lineDataService.getLineData();
        return ApiResponse.ok("axle-counter-sections", ld.getAxleCounterSections());
    }

    @GetMapping("/dispatch/axle-counters")
    public ApiResponse<List<LineData.AxleCounter>> getAxleCounters() {
        LineData ld = lineDataService.getLineData();
        return ApiResponse.ok("axle-counters", ld.getAxleCounters());
    }

    @GetMapping("/dispatch/collision-zones")
    public ApiResponse<List<LineData.CollisionZone>> getCollisionZones() {
        LineData ld = lineDataService.getLineData();
        return ApiResponse.ok("collision-zones", ld.getCollisionZones());
    }

    @GetMapping("/dispatch/depot-doors")
    public ApiResponse<List<LineData.DepotDoor>> getDepotDoors() {
        LineData ld = lineDataService.getLineData();
        return ApiResponse.ok("depot-doors", ld.getDepotDoors());
    }

    /**
     * 获取当前位置的速度限制（查询用）
     */
    @GetMapping("/dispatch/speed-limit-at")
    public ApiResponse<Map<String, Object>> getSpeedLimitAt(@RequestParam double km) {
        int limit = lineDataService.getSpeedLimitAtKm(km);
        int gradient = lineDataService.getGradientAtKm(km);
        Map<String, Object> result = new HashMap<>();
        result.put("km", km);
        result.put("speedLimitKmh", limit);
        result.put("gradientPermille", gradient);
        return ApiResponse.ok("speed-limit", result);
    }

    /**
     * 获取所有线路数据汇总统计
     */
    @GetMapping("/dispatch/data-summary")
    public ApiResponse<Map<String, Integer>> getDataSummary() {
        LineData ld = lineDataService.getLineData();
        Map<String, Integer> summary = new HashMap<>();
        summary.put("signals", ld.getSignals() != null ? ld.getSignals().size() : 0);
        summary.put("switches", ld.getSwitches() != null ? ld.getSwitches().size() : 0);
        summary.put("routes", ld.getRoutes() != null ? ld.getRoutes().size() : 0);
        summary.put("balises", ld.getBalises() != null ? ld.getBalises().size() : 0);
        summary.put("speedLimits", ld.getStaticSpeedLimits() != null ? ld.getStaticSpeedLimits().size() : 0);
        summary.put("gradients", ld.getGradients() != null ? ld.getGradients().size() : 0);
        summary.put("tunnels", ld.getTunnels() != null ? ld.getTunnels().size() : 0);
        summary.put("bumpers", ld.getBumpers() != null ? ld.getBumpers().size() : 0);
        summary.put("floodGates", ld.getFloodGates() != null ? ld.getFloodGates().size() : 0);
        summary.put("platformDoors", ld.getPlatformDoors() != null ? ld.getPlatformDoors().size() : 0);
        summary.put("axleCounterSections", ld.getAxleCounterSections() != null ? ld.getAxleCounterSections().size() : 0);
        summary.put("depotDoors", ld.getDepotDoors() != null ? ld.getDepotDoors().size() : 0);
        summary.put("trackPoints", ld.getTrackPoints() != null ? ld.getTrackPoints().size() : 0);
        summary.put("logicalSections", ld.getLogicalSections() != null ? ld.getLogicalSections().size() : 0);
        summary.put("collisionZones", ld.getCollisionZones() != null ? ld.getCollisionZones().size() : 0);
        return ApiResponse.ok("data-summary", summary);
    }

    // ============ Energy ============

    @GetMapping("/simulations/energy")
    public ApiResponse<EnergySnapshot> getEnergy(
            @RequestParam(defaultValue = "0.85") double tractionEfficiency,
            @RequestParam(defaultValue = "0.65") double regenEfficiency,
            @RequestParam(defaultValue = "2000") double powerThresholdKw) {
        List<SimulationLog> logs = simulationService.getSimulationLogs();
        EnergySnapshot snapshot = energyIntegrationService.compute(
                logs, tractionEfficiency, regenEfficiency, powerThresholdKw);
        return ApiResponse.ok("energy snapshot", snapshot);
    }

    @GetMapping("/simulations/logs")
    public ApiResponse<List<SimulationLog>> getSimulationLogs() {
        List<SimulationLog> logs = simulationService.getSimulationLogs();
        return ApiResponse.ok("simulation logs", logs);
    }
}
