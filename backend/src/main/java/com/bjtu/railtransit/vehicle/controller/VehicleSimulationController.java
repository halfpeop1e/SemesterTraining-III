package com.bjtu.railtransit.vehicle.controller;

import com.bjtu.railtransit.common.ApiResponse;
import com.bjtu.railtransit.vehicle.dto.SimulationResult;
import com.bjtu.railtransit.vehicle.service.VehicleSimulationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 郭逸晨车载模块 —— 车辆仿真接口（阶段 1A）。
 *
 * <p>本轮只提供一个一次性返回完整仿真结果的接口，使用后端内置 SI 单位演示配置。
 * 第一版请求体暂不生效，前端调用与按时间播放留给阶段 1B。</p>
 */
@RestController
@RequestMapping("/api/vehicle/simulation")
public class VehicleSimulationController {

    private final VehicleSimulationService vehicleSimulationService;

    public VehicleSimulationController(VehicleSimulationService vehicleSimulationService) {
        this.vehicleSimulationService = vehicleSimulationService;
    }

    /**
     * 运行一次车辆纵向运动仿真，使用后端内置演示配置，一次性返回完整结果。
     */
    @PostMapping("/run")
    public ApiResponse<SimulationResult> run() {
        SimulationResult result = vehicleSimulationService.runDemoSimulation();
        return ApiResponse.ok("ok", result);
    }
}
