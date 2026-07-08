package com.bjtu.railtransit.vehicle.controller;

import com.bjtu.railtransit.vehicle.service.DemoScenarioProvider;
import com.bjtu.railtransit.vehicle.service.LineProfileJsonLoader;
import com.bjtu.railtransit.vehicle.service.VehicleSimulationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段1A + 阶段4B 接口层测试：验证 {@code POST /api/vehicle/simulation/run}
 * 在空请求体（默认 1→2）和指定站点时均能返回正确结构。
 *
 * <p>使用 {@code standaloneSetup} 直接装配真实的 controller + service + loader，
 * 不依赖 Spring 容器扫描，所有 bean 均为真实对象（非 mock）。</p>
 */
class VehicleSimulationControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DemoScenarioProvider demoScenarioProvider = new DemoScenarioProvider();
        VehicleSimulationService service = new VehicleSimulationService(demoScenarioProvider);
        LineProfileJsonLoader loader = new LineProfileJsonLoader();
        VehicleSimulationController controller =
                new VehicleSimulationController(service, demoScenarioProvider, loader);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /** 无请求体时默认 1→2，仿真应正常跑通，返回四段结构完整响应。 */
    @Test
    void runWithEmptyBodyDefaultsToStation1And2() throws Exception {
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
                .andExpect(jsonPath("$.data.summary.fromStationName").value("郭公庄"))
                .andExpect(jsonPath("$.data.summary.toStationName").value("丰台科技园"))
                .andExpect(jsonPath("$.data.stopResult").exists())
                .andExpect(jsonPath("$.data.stopResult.stopWindowState").value("in_window"))
                .andExpect(jsonPath("$.data.stopResult.brakeTriggerPosition").exists())
                .andExpect(jsonPath("$.data.stopResult.predictedStopPosition").exists())
                .andExpect(jsonPath("$.data.safetyEvents").isArray());
    }

    /** 显式传 fromStationId=1, toStationId=2，应与默认结果一致。 */
    @Test
    void runWithStation1To2ReturnsCorrectTargetStop() throws Exception {
        // 郭公庄 km=0.313, 丰台科技园 km=1.661 -> runDistance = (1.661-0.313)*1000 = 1348m
        mockMvc.perform(post("/api/vehicle/simulation/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromStationId\":1,\"toStationId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.summary.lineStartPosition").value(313.0))
                .andExpect(jsonPath("$.data.summary.lineTargetPosition").value(1661.0))
                .andExpect(jsonPath("$.data.summary.fromStationName").value("郭公庄"))
                .andExpect(jsonPath("$.data.summary.toStationName").value("丰台科技园"))
                .andExpect(jsonPath("$.data.stopResult.targetStopPosition").value(1348.0));
    }

    /** 非法站点 id 应返回 success=false，message 包含错误说明。 */
    @Test
    void runWithInvalidStationIdReturnsError() throws Exception {
        mockMvc.perform(post("/api/vehicle/simulation/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromStationId\":1,\"toStationId\":99}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("99")));
    }

    /** toStationId <= fromStationId 应返回 success=false。 */
    @Test
    void runWithReversedStationIdsReturnsError() throws Exception {
        mockMvc.perform(post("/api/vehicle/simulation/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromStationId\":3,\"toStationId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("大于")));
    }

    /** toStationId == fromStationId 应返回 success=false。 */
    @Test
    void runWithSameStationIdsReturnsError() throws Exception {
        mockMvc.perform(post("/api/vehicle/simulation/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromStationId\":2,\"toStationId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
    }

    /** 跨越多个站点的区间（如 2→5）应能正常跑通，targetStopPosition 约等于两站km差*1000。 */
    @Test
    void runWithMultipleStationGapSucceeds() throws Exception {
        // 丰台科技园 km=1.661, 丰台东大街 km=5.014 -> runDistance≈3353m
        mockMvc.perform(post("/api/vehicle/simulation/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromStationId\":2,\"toStationId\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.summary.fromStationName").value("丰台科技园"))
                .andExpect(jsonPath("$.data.summary.toStationName").value("丰台东大街"))
                .andExpect(jsonPath("$.data.stopResult.targetStopPosition").exists());
    }
}
