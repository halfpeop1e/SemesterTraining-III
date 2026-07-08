package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.common.ApiResponse;
import com.bjtu.railtransit.domain.dto.SimulationRequest;
import com.bjtu.railtransit.domain.model.DispatchPlan;
import com.bjtu.railtransit.domain.model.SimulationSnapshot;
import com.bjtu.railtransit.domain.model.StationGeo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
}
