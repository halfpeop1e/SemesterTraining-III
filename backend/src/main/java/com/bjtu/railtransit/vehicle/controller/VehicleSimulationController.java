package com.bjtu.railtransit.vehicle.controller;

import com.bjtu.railtransit.common.ApiResponse;
import com.bjtu.railtransit.vehicle.dto.SimulationControlRequest;
import com.bjtu.railtransit.vehicle.dto.SimulationResult;
import com.bjtu.railtransit.vehicle.dto.SimulationRunRequest;
import com.bjtu.railtransit.vehicle.model.LineProfile;
import com.bjtu.railtransit.vehicle.model.ScenarioConfig;
import com.bjtu.railtransit.vehicle.service.DemoScenarioProvider;
import com.bjtu.railtransit.vehicle.service.LineProfileJsonLoader;
import com.bjtu.railtransit.vehicle.service.LineProfileJsonLoader.StationEntry;
import com.bjtu.railtransit.vehicle.service.VehicleSimulationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 郭逸晨车载模块——车辆仿真接口。
 *
 * <p>POST /api/vehicle/simulation/run：一次性仿真（单区间或多站连续）。</p>
 * <p>POST /api/vehicle/simulation/control：驾驶员控制续算。</p>
 */
@RestController
@RequestMapping("/api/vehicle/simulation")
public class VehicleSimulationController {

    private final VehicleSimulationService vehicleSimulationService;
    private final DemoScenarioProvider demoScenarioProvider;
    private final LineProfileJsonLoader lineProfileJsonLoader;

    public VehicleSimulationController(VehicleSimulationService vehicleSimulationService,
                                        DemoScenarioProvider demoScenarioProvider,
                                        LineProfileJsonLoader lineProfileJsonLoader) {
        this.vehicleSimulationService = vehicleSimulationService;
        this.demoScenarioProvider = demoScenarioProvider;
        this.lineProfileJsonLoader = lineProfileJsonLoader;
    }

    /**
     * 运行仿真（单区间或多站）。
     *
     * <p>toStationId > fromStationId+1 时自动触发多站连续仿真：
     * 1→2→3→...→to，中间站驻留 dwellTimeSeconds（默认 30s）。</p>
     *
     * <p>坐标约定：返回 states.position 为从 fromStation 起的连续累积里程，
     * stopResult.targetStopPosition 为总距离。</p>
     */
    @PostMapping("/run")
    public ApiResponse<SimulationResult> run(
            @RequestBody(required = false) SimulationRunRequest request) {

        int fromId = request != null ? request.resolvedFromId() : 1;
        int toId = request != null ? request.resolvedToId() : 2;
        Double dwellTime = request != null ? request.getDwellTimeSeconds() : null;

        try {
            // 先验证站点存在
            lineProfileJsonLoader.findStationPair(fromId, toId);

            if (toId > fromId + 1) {
                // 多站连续仿真
                List<StationEntry> all = lineProfileJsonLoader.listStations();
                List<StationEntry> segment = new ArrayList<>();
                for (StationEntry s : all) {
                    if (s.id >= fromId && s.id <= toId) {
                        segment.add(s);
                    }
                }
                segment.sort((a, b) -> Integer.compare(a.id, b.id));
                SimulationResult result = vehicleSimulationService.runMultiStation(
                        segment, dwellTime, demoScenarioProvider);
                return ApiResponse.ok("ok", result);
            } else {
                // 单区间仿真（原有路径）
                LineProfile lineProfile = lineProfileJsonLoader.buildLineProfile(fromId, toId);
                ScenarioConfig scenario = demoScenarioProvider.buildScenario(lineProfile);

                StationEntry[] pair = lineProfileJsonLoader.findStationPair(fromId, toId);
                StationEntry fromStation = pair[0];
                StationEntry toStation = pair[1];

                SimulationResult result = vehicleSimulationService.run(scenario);

                result.getSummary().setLineStartPosition(fromStation.km * 1000.0);
                result.getSummary().setLineTargetPosition(toStation.km * 1000.0);
                result.getSummary().setFromStationName(fromStation.name);
                result.getSummary().setToStationName(toStation.name);
                result.getSummary().setTotalStations(2);
                result.getSummary().setCompletedStops(1);

                // 为单区间 states 填充 absolutePosition
                double lineStartAbsM = fromStation.km * 1000.0;
                for (com.bjtu.railtransit.vehicle.dto.TrainState st : result.getStates()) {
                    st.setAbsolutePosition(lineStartAbsM + st.getPosition());
                }

                return ApiResponse.ok("ok", result);
            }

        } catch (IllegalArgumentException e) {
            return ApiResponse.error("仿真请求参数非法: " + e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("仿真执行失败: " + e.getMessage());
        }
    }

    /**
     * 驾驶员控制续算：从当前帧状态开始，根据 currentMode 和 controlCommand 续算后续轨迹。
     *
     * <p>ATO + brake → 拒绝，保持 ATO 自动策略（模式不变）。</p>
     * <p>MANUAL + brake → 真实制动，速度曲线/停车位置变化。</p>
     * <p>EB → EMERGENCY，中断多站任务，不继续驶向后续站。</p>
     *
     * <p>坐标约定：request.currentState.position 必须是全程累积里程；
     * 返回 states.position 以当前位置为起点继续累积。</p>
     */
    @PostMapping("/control")
    public ApiResponse<SimulationResult> control(
            @RequestBody SimulationControlRequest request) {

        if (request == null || request.getCurrentState() == null) {
            return ApiResponse.error("控制请求不能为空");
        }

        int fromId = request.getFromStationId() > 0 ? request.getFromStationId() : 1;
        int toId = request.getToStationId() > 0 ? request.getToStationId() : 2;

        try {
            // 用当前区间的 scenario 做续算（从 fromStation 到 toStation 的物理参数）
            LineProfile lineProfile = lineProfileJsonLoader.buildLineProfile(fromId, toId);
            ScenarioConfig scenario = demoScenarioProvider.buildScenario(lineProfile);

            StationEntry[] pair = lineProfileJsonLoader.findStationPair(fromId, toId);
            StationEntry fromStation = pair[0];
            StationEntry toStation = pair[1];

            SimulationResult result = vehicleSimulationService.runContinuation(request, scenario);

            result.getSummary().setLineStartPosition(fromStation.km * 1000.0);
            result.getSummary().setLineTargetPosition(toStation.km * 1000.0);
            result.getSummary().setFromStationName(fromStation.name);
            result.getSummary().setToStationName(toStation.name);

            return ApiResponse.ok("ok", result);

        } catch (IllegalArgumentException e) {
            return ApiResponse.error("控制请求参数非法: " + e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("控制续算执行失败: " + e.getMessage());
        }
    }
}
