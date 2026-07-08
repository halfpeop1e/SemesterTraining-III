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

import java.util.List;

/**
 * 郭逸晨车载模块 —— 车辆仿真接口（阶段4B 线路数据真实化 + 本轮驾驶员控制闭环）。
 *
 * <p>POST /api/vehicle/simulation/run：一次性仿真，返回完整 states。</p>
 * <p>POST /api/vehicle/simulation/control：驾驶员控制续算，从当前帧重新续算后续 states。</p>
 *
 * <p>站点 km 数据来自 configs/line-profile.json（通过 {@link LineProfileJsonLoader} 读取）；
 * 车辆参数、Davis 系数、制动参数、dt 仍沿用 {@link DemoScenarioProvider} 内置默认值。</p>
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
     * 运行一次车辆纵向运动仿真，一次性返回完整结果。
     *
     * <p>请求体可选：为空时默认 fromStationId=1, toStationId=2（郭公庄→丰台科技园）。
     * 非法站点 id 或 toId &le; fromId 时返回失败 ApiResponse，HTTP 状态仍为 200。</p>
     */
    @PostMapping("/run")
    public ApiResponse<SimulationResult> run(
            @RequestBody(required = false) SimulationRunRequest request) {

        int fromId = request != null ? request.resolvedFromId() : 1;
        int toId = request != null ? request.resolvedToId() : 2;
        Double dwellTime = request != null ? request.getDwellTimeSeconds() : null;

        try {
            if (toId > fromId + 1) {
                // 多站连续仿真：从 fromId 逐站到 toId
                List<LineProfileJsonLoader.StationEntry> all = lineProfileJsonLoader.listStations();
                // 先验证 fromId 和 toId 均存在（复用 findStationPair 的校验逻辑）
                lineProfileJsonLoader.findStationPair(fromId, toId);  // 不存在时抛 IllegalArgumentException
                List<LineProfileJsonLoader.StationEntry> segment = new java.util.ArrayList<>();
                for (LineProfileJsonLoader.StationEntry s : all) {
                    if (s.id >= fromId && s.id <= toId) {
                        segment.add(s);
                    }
                }
                segment.sort((a, b) -> Integer.compare(a.id, b.id));
                if (segment.size() < 2) {
                    return ApiResponse.error("找不到足够的站点进行多站仿真，id 范围: " + fromId + "~" + toId);
                }
                SimulationResult result = vehicleSimulationService.runMultiStation(segment, dwellTime, demoScenarioProvider);
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
                result.getSummary().setFromStationId(fromId);
                result.getSummary().setToStationId(toId);
                result.getSummary().setTotalStations(2);
                result.getSummary().setCompletedStops(1);

                return ApiResponse.ok("ok", result);
            }

        } catch (IllegalArgumentException e) {
            return ApiResponse.error("仿真请求参数非法: " + e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("仿真执行失败: " + e.getMessage());
        }
    }

    /**
     * 驾驶员控制续算：从请求中携带的当前帧状态开始，根据 currentMode 和 controlCommand
     * 重新续算后续状态序列。前端用返回的新 states 替换当前帧之后的旧 states 继续播放。
     *
     * <p>本接口语义保证（详见 {@link VehicleSimulationService#runContinuation}）：</p>
     * <ul>
     *   <li>ATO 下普通 brake 被拒绝（保持 ATO 自动策略续算）。</li>
     *   <li>MANUAL 下 brake 真实参与续算，速度曲线/停车位置会变化。</li>
     *   <li>EB 任意模式可触发 EMERGENCY，使用 emergencyBrakeDeceleration。</li>
     *   <li>SafetyGuard 超速会生成 SafetyEvent 并强制触发紧急制动。</li>
     * </ul>
     *
     * <p><b>仿真原型声明：</b>本项目为课程仿真原型，驾驶模式切换不代表真实 CBTC/ATP/ATO
     * 系统授权流程，ATO → MANUAL 切换不需要地面授权（真实系统中需要），不得声称实现了
     * 真实行车授权或安全级别的控制系统。</p>
     */
    @PostMapping("/control")
    public ApiResponse<SimulationResult> control(
            @RequestBody SimulationControlRequest request) {

        if (request == null) {
            return ApiResponse.error("controlRequest 不能为空");
        }

        int fromId = request.getFromStationId() > 0 ? request.getFromStationId() : 1;
        int toId = request.getToStationId() > 0 ? request.getToStationId() : 2;

        try {
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
