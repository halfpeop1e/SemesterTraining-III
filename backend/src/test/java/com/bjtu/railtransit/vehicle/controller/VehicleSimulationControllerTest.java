package com.bjtu.railtransit.vehicle.controller;

import com.bjtu.railtransit.dispatch.MultiParticleSimulationService;
import com.bjtu.railtransit.dispatch.SidingDispatchService;
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
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
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
    private VehicleSimulationService service;
    private DemoScenarioProvider demoScenarioProvider;

    @BeforeEach
    void setUp() {
        demoScenarioProvider = new DemoScenarioProvider();
        MultiParticleSimulationService multiParticleService = new MultiParticleSimulationService();
        service = new VehicleSimulationService(demoScenarioProvider, multiParticleService);
        LineProfileJsonLoader loader = new LineProfileJsonLoader();
        SidingDispatchService sidingDispatchService = new SidingDispatchService(loader);
        sidingDispatchService.init();
        VehicleSimulationController controller =
                new VehicleSimulationController(service, demoScenarioProvider, loader, sidingDispatchService);
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

    @Test
    void control_rejectsClientTargetThatConflictsWithServerResolvedNextStation() throws Exception {
        String body = """
                {"fromStationId":1,"toStationId":4,
                 "currentState":{"time":30.0,"position":300.0,"velocity":8.0,
                   "acceleration":0.0,"phase":"coast","trainId":"T1","absolutePosition":613.0},
                 "currentMode":"manual",
                 "controlCommand":{"command":"resume_ato","targetDecel":0.0,"levelPercent":0.0},
                 "totalTargetPosition":999999.0,"nextStationId":2,"nextStationName":"丰台科技园"}
                """;

        mockMvc.perform(post("/api/vehicle/simulation/control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("目标")));
    }

    /**
     * 任务 1（B3）HTTP 级回归：/control 多站续算后 stationStops 必须包含后续未到站。
     *
     * <p>1→4 多站仿真，取站1→站2运动帧 resume_ato（nextStationId=2, toStationId=4），
     * 控制器 expandContinuationStationStops 应把单元素扩展为 [站2, 站3, 站4] 三条，
     * 其中站2 有 actualPosition（已停），站3/站4 actualPosition=-1（哨兵未到）。
     * 修复前：返回单元素，前端整体覆盖后第二次续算找不到站3。</p>
     */
    @Test
    void control_multiStationContinuation_returnsExpandedStationStops() throws Exception {
        // 1. 先跑 1→4 多站，拿到 stationStops 与一个站1→站2区间运动帧
        com.bjtu.railtransit.vehicle.service.LineProfileJsonLoader loader =
                new com.bjtu.railtransit.vehicle.service.LineProfileJsonLoader();
        java.util.List<com.bjtu.railtransit.vehicle.service.LineProfileJsonLoader.StationEntry> stations =
                loader.listStations();
        java.util.List<com.bjtu.railtransit.vehicle.service.LineProfileJsonLoader.StationEntry> seg =
                new java.util.ArrayList<>();
        for (com.bjtu.railtransit.vehicle.service.LineProfileJsonLoader.StationEntry s : stations) {
            if (s.id >= 1 && s.id <= 4) seg.add(s);
        }
        com.bjtu.railtransit.vehicle.dto.SimulationResult multi =
                service.runMultiStation(seg, 30.0, demoScenarioProvider);
        com.bjtu.railtransit.vehicle.dto.StationStop station2 = multi.getStationStops().get(0);
        double station2Target = station2.getTargetPosition();

        com.bjtu.railtransit.vehicle.dto.TrainState midState = null;
        for (com.bjtu.railtransit.vehicle.dto.TrainState s : multi.getStates()) {
            if (s.getPosition() > 50.0 && s.getPosition() < station2Target * 0.5
                    && s.getVelocity() > 1.0
                    && s.getPhase() != com.bjtu.railtransit.vehicle.enums.SimulationPhase.DWELL) {
                midState = s;
                break;
            }
        }
        org.junit.jupiter.api.Assertions.assertNotNull(midState);

        // 2. 构造 /control 请求体（手工拼 JSON，避免依赖 DTO 序列化细节）
        String body = String.format(
                "{\"fromStationId\":1,\"toStationId\":4,"
                        + "\"currentState\":{\"time\":%.3f,\"position\":%.3f,\"velocity\":%.3f,"
                        + "\"acceleration\":0.0,\"phase\":\"traction\",\"trainId\":\"T1\","
                        + "\"absolutePosition\":%.3f},"
                        + "\"currentMode\":\"manual\","
                        + "\"controlCommand\":{\"command\":\"resume_ato\",\"targetDecel\":0.0,\"levelPercent\":0.0},"
                        + "\"totalTargetPosition\":%.3f,\"nextStationId\":2,\"nextStationName\":\"丰台科技园\"}",
                midState.getTime(), midState.getPosition(), midState.getVelocity(),
                midState.getAbsolutePosition(), station2Target);

        // 3. 断言：stationStops 被扩展为 3 条（站2/站3/站4）
        mockMvc.perform(post("/api/vehicle/simulation/control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.stationStops", hasSize(3)))
                .andExpect(jsonPath("$.data.stationStops[0].stationId").value(2))
                .andExpect(jsonPath("$.data.stationStops[1].stationId").value(3))
                .andExpect(jsonPath("$.data.stationStops[2].stationId").value(4))
                // 站2（本次目标）有真实 actualPosition；站3/4 未到达哨兵 -1
                .andExpect(jsonPath("$.data.stationStops[0].actualPosition").value(greaterThanOrEqualTo(0.0)))
                .andExpect(jsonPath("$.data.stationStops[1].actualPosition").value(-1.0))
                .andExpect(jsonPath("$.data.stationStops[2].actualPosition").value(-1.0));
    }

    private String terminalTurnbackBody(String command) {
        return "{\"fromStationId\":12,\"toStationId\":13,"
                + "\"currentState\":{\"time\":120.0,\"position\":1095.0,\"velocity\":0.0,"
                + "\"acceleration\":0.0,\"phase\":\"dwell\",\"trainId\":\"T1\","
                + "\"absolutePosition\":16049.0,\"cars\":[{\"carIndex\":0,"
                + "\"carType\":\"Tc\",\"motored\":true,\"occupiedMass\":42000.0,"
                + "\"passengerLoadRatio\":0.5,\"positionMeters\":16049.0,"
                + "\"speedKmh\":0.0,\"couplerForceKN\":0.0}]},"
                + "\"currentMode\":\"ato\",\"controlCommand\":{\"command\":\""
                + command + "\",\"targetDecel\":0.0,\"levelPercent\":0.0}}";
    }

    private void registerSession(String sessionId) throws Exception {
        mockMvc.perform(post("/api/vehicle/simulation/run")
                        .header("X-Run-Session-Id", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromStationId\":12,\"toStationId\":13}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void blankSessionHeaderIsRejectedButMissingHeaderRemainsCompatible() throws Exception {
        mockMvc.perform(post("/api/vehicle/simulation/run")
                        .header("X-Run-Session-Id", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromStationId\":1,\"toStationId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("SESSION_ID_MISSING")));

        mockMvc.perform(post("/api/vehicle/simulation/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromStationId\":1,\"toStationId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void runWithSessionHeaderRegistersSessionForTurnback() throws Exception {
        registerSession("r06-http-session");

        mockMvc.perform(post("/api/vehicle/simulation/turnback")
                        .header("X-Run-Session-Id", "r06-http-session")
                        .header("X-Run-Frame-Id", "0")
                        .header("X-Doors-Safe", "true")
                        .header("X-Movement-Authority", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(terminalTurnbackBody("turnback_request")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.turnbackState").value("READY_REVERSE"))
                .andExpect(jsonPath("$.data.reverseDeparture")
                        .value("BLOCKED_MISSING_AUTHORITATIVE_AUTHORITY"));
    }

    @Test
    void legacyRunWithoutSessionRemainsCompatibleButTurnbackFailsClosed() throws Exception {
        mockMvc.perform(post("/api/vehicle/simulation/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromStationId\":1,\"toStationId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(post("/api/vehicle/simulation/turnback")
                        .header("X-Run-Session-Id", "not-registered")
                        .header("X-Run-Frame-Id", "0")
                        .header("X-Doors-Safe", "true")
                        .header("X-Movement-Authority", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(terminalTurnbackBody("turnback_request")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("SESSION_NOT_FOUND")));
    }

    @Test
    void turnbackHeaderAndCommandValidationIsFailClosed() throws Exception {
        registerSession("r06-header-session");

        mockMvc.perform(post("/api/vehicle/simulation/turnback")
                        .header("X-Run-Session-Id", "r06-header-session")
                        .header("X-Doors-Safe", "true")
                        .header("X-Movement-Authority", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(terminalTurnbackBody("turnback_request")))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("FRAME_ID_MISSING")));

        mockMvc.perform(post("/api/vehicle/simulation/turnback")
                        .header("X-Run-Session-Id", "r06-header-session")
                        .header("X-Run-Frame-Id", "0")
                        .header("X-Doors-Safe", "false")
                        .header("X-Movement-Authority", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(terminalTurnbackBody("turnback_request")))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("DOORS_NOT_CONFIRMED_SAFE")));

        mockMvc.perform(post("/api/vehicle/simulation/turnback")
                        .header("X-Run-Session-Id", "r06-header-session")
                        .header("X-Run-Frame-Id", "0")
                        .header("X-Doors-Safe", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(terminalTurnbackBody("turnback_request")))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("LOCAL_TURNBACK_PERMIT_MISSING")));

        mockMvc.perform(post("/api/vehicle/simulation/turnback")
                        .header("X-Run-Session-Id", "r06-header-session")
                        .header("X-Run-Frame-Id", "1")
                        .header("X-Doors-Safe", "true")
                        .header("X-Movement-Authority", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(terminalTurnbackBody("coast")))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("COMMAND_INVALID")));
    }

    @Test
    void turnbackWithLocalAuthorityHintNeverStartsReverseDeparture() throws Exception {
        registerSession("r06-reverse-block-session");

        mockMvc.perform(post("/api/vehicle/simulation/turnback")
                        .header("X-Run-Session-Id", "r06-reverse-block-session")
                        .header("X-Run-Frame-Id", "0")
                        .header("X-Doors-Safe", "true")
                        .header("X-Movement-Authority", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(terminalTurnbackBody("turnback_request")))
                .andExpect(jsonPath("$.data.authoritySource").value("LOCAL_SIMULATION_HINT"))
                .andExpect(jsonPath("$.data.turnbackState").value("READY_REVERSE"))
                .andExpect(jsonPath("$.data.lifecycle").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("REVERSE_DEPARTING"))))
                .andExpect(jsonPath("$.data.reverseDeparture")
                        .value("BLOCKED_MISSING_AUTHORITATIVE_AUTHORITY"));
    }
}
