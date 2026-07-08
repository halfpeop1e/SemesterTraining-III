package com.bjtu.railtransit.vehicle.controller;

import com.bjtu.railtransit.vehicle.service.DemoScenarioProvider;
import com.bjtu.railtransit.vehicle.service.VehicleSimulationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段 1A 接口层测试：验证 {@code POST /api/vehicle/simulation/run} 能被真实调用，
 * 并返回四段字段齐全的 {@code ApiResponse<SimulationResult>} 结构。
 *
 * <p>使用 {@code standaloneSetup} 直接装配真实的 controller + service + 内置演示配置，
 * 不依赖 Spring 容器扫描，controller 和 service 均为真实对象（非 mock）。</p>
 */
class VehicleSimulationControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        VehicleSimulationService service = new VehicleSimulationService(new DemoScenarioProvider());
        VehicleSimulationController controller = new VehicleSimulationController(service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void runReturnsApiResponseWithFourSections() throws Exception {
        mockMvc.perform(post("/api/vehicle/simulation/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.states").isArray())
                .andExpect(jsonPath("$.data.states", not(empty())))
                .andExpect(jsonPath("$.data.states.length()", greaterThan(50)))
                .andExpect(jsonPath("$.data.summary").exists())
                .andExpect(jsonPath("$.data.summary.maxVelocity").exists())
                .andExpect(jsonPath("$.data.summary.totalTime").exists())
                .andExpect(jsonPath("$.data.summary.finalPosition").exists())
                .andExpect(jsonPath("$.data.summary.speedLimit").value(20.0))
                .andExpect(jsonPath("$.data.summary.dtPerFrame").value(0.5))
                .andExpect(jsonPath("$.data.stopResult").exists())
                .andExpect(jsonPath("$.data.stopResult.targetStopPosition").value(1200.0))
                // 阶段2引入真实阻力/坡度后，演示配置下停站误差约0.57m（冲过目标点），
                // stopWindowState 应为 overshoot，不再是阶段1理想模型下的 in_window。
                .andExpect(jsonPath("$.data.stopResult.stopWindowState").value("in_window"))
                .andExpect(jsonPath("$.data.stopResult.brakeTriggerPosition").exists())
                .andExpect(jsonPath("$.data.stopResult.predictedStopPosition").exists())
                .andExpect(jsonPath("$.data.safetyEvents").isArray());
    }
}
