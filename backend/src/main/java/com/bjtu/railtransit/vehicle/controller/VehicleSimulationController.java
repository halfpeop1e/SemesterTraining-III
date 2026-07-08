package com.bjtu.railtransit.vehicle.controller;

import com.bjtu.railtransit.common.ApiResponse;
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

/**
 * 郭逸晨车载模块 —— 车辆仿真接口（阶段4B 线路数据真实化）。
 *
 * <p>POST /api/vehicle/simulation/run 接收可选的 {@link SimulationRunRequest}：
 * 请求体为空时默认使用 fromStationId=1（郭公庄）→ toStationId=2（丰台科技园）。</p>
 *
 * <p>站点 km 数据来自 configs/line-profile.json（通过 {@link LineProfileJsonLoader} 读取）；
 * 车辆参数、Davis 系数、制动参数、dt 仍沿用 {@link DemoScenarioProvider} 内置默认值；
 * 限速和坡度当前 JSON 中没有对应字段，仍使用假设值，不声称来自真实配置。</p>
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

        try {
            // 从 line-profile.json 读取站点公里标，构造相对坐标 LineProfile
            LineProfile lineProfile = lineProfileJsonLoader.buildLineProfile(fromId, toId);
            ScenarioConfig scenario = demoScenarioProvider.buildScenario(lineProfile);

            // 查找站点名称，填入 summary 的绝对里程与站名字段
            StationEntry[] pair = lineProfileJsonLoader.findStationPair(fromId, toId);
            StationEntry fromStation = pair[0];
            StationEntry toStation = pair[1];

            SimulationResult result = vehicleSimulationService.run(scenario);

            // 写入绝对里程和站名（train_state.position 仍是相对坐标 0→runDistanceM）
            result.getSummary().setLineStartPosition(fromStation.km * 1000.0);
            result.getSummary().setLineTargetPosition(toStation.km * 1000.0);
            result.getSummary().setFromStationName(fromStation.name);
            result.getSummary().setToStationName(toStation.name);

            return ApiResponse.ok("ok", result);

        } catch (IllegalArgumentException e) {
            return ApiResponse.error("仿真请求参数非法: " + e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("仿真执行失败: " + e.getMessage());
        }
    }
}
