package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.vehicle.dto.SidingEnterRequest;
import com.bjtu.railtransit.vehicle.dto.TrainState;
import com.bjtu.railtransit.vehicle.enums.SimulationPhase;
import com.bjtu.railtransit.vehicle.service.DemoScenarioProvider;
import com.bjtu.railtransit.vehicle.service.LineProfileJsonLoader;
import com.bjtu.railtransit.vehicle.service.VehicleSimulationService;
import com.bjtu.railtransit.dispatch.dto.SidingEntryRequest;
import com.bjtu.railtransit.dispatch.dto.SidingReleaseRequest;
import com.bjtu.railtransit.dispatch.dto.SidingStatus;
import com.bjtu.railtransit.vehicle.controller.VehicleSimulationController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 侧线调度 + 车载驶入编排的 HTTP/MockMvc 级测试（对应任务 2 / 3）。
 *
 * <p>护栏要求：不只测 Service，必须覆盖 HTTP/MockMvc 路径。本类装配
 * {@link SidingDispatchController}（dispatch 层四个端点）与
 * {@link VehicleSimulationController} 的 /siding/enter 编排端点，验证：</p>
 * <ol>
 *   <li>任务 2：侧线查询/请求/释放 JSON 字段名为 {@code status}（非 state）。</li>
 *   <li>任务 3-1：/siding/enter 成功路径 AVAILABLE → RESERVED → OCCUPIED。</li>
 *   <li>任务 3-2：预留成功后驶入仿真失败 → 回滚 AVAILABLE，不误释放既有占用。</li>
 *   <li>任务 3-3：预留成功后确认占用失败 → 回滚 AVAILABLE。</li>
 *   <li>任务 3-4：回滚后另一列车可重新申请同站。</li>
 * </ol>
 *
 * <p>失败注入：当前端控制器的入参前置校验已覆盖 currentState/trainId 非空，
 * 正常 HTTP 入参无法让 enterSiding 抛异常；故用 Mockito {@code spy} 包装真实 service，
 * 定向 stub 出 {@code enterSiding} / {@code confirmOccupied} 异常，验证控制器编排的补偿路径
 * （回滚释放），而非 service 自身逻辑。这是 MockMvc 层验证编排补偿的标准做法。</p>
 */
class SidingDispatchControllerTest {

    private MockMvc dispatchMvc;
    private SidingDispatchService sidingService;
    private VehicleSimulationService vehicleService;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        LineProfileJsonLoader loader = new LineProfileJsonLoader();
        sidingService = new SidingDispatchService(loader);
        sidingService.init();

        dispatchMvc = MockMvcBuilders.standaloneSetup(new SidingDispatchController(sidingService)).build();

        DemoScenarioProvider demo = new DemoScenarioProvider();
        com.bjtu.railtransit.dispatch.MultiParticleSimulationService mp =
                new com.bjtu.railtransit.dispatch.MultiParticleSimulationService();
        vehicleService = new VehicleSimulationService(demo, mp);
    }

    /** 用指定 vehicleService 与 sidingService 构造 /siding/enter 所在控制器的 MockMvc。 */
    private MockMvc buildVehicleMockMvc(VehicleSimulationService vs, SidingDispatchService ss) {
        LineProfileJsonLoader loader = new LineProfileJsonLoader();
        DemoScenarioProvider demo = new DemoScenarioProvider();
        VehicleSimulationController controller =
                new VehicleSimulationController(vs, demo, loader, ss);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private TrainState movingState() {
        return new TrainState(100.0, 500.0, 8.0, 0.0, SimulationPhase.COAST, "T1");
    }

    private String enterBody(String trainId, int stationId, TrainState state) throws Exception {
        return om.writeValueAsString(new SidingEnterRequest(trainId, stationId, state));
    }

    // ===== 任务 2：侧线字段契约 status（非 state） =====

    /** /api/dispatch/siding/all 返回每站含 status 字段，且不含 state。 */
    @Test
    void getAllSidings_returnsStatusFieldNotState() throws Exception {
        dispatchMvc.perform(get("/api/dispatch/siding/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data[0].state").doesNotExist());
    }

    /** /api/dispatch/siding/{id} 返回 status 字段。 */
    @Test
    void getSidingByStationId_returnsStatusField() throws Exception {
        dispatchMvc.perform(get("/api/dispatch/siding/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.state").doesNotExist());
    }

    /** request 成功 → status=RESERVED，无 state 字段。 */
    @Test
    void requestSiding_returnsReservedStatusField() throws Exception {
        dispatchMvc.perform(post("/api/dispatch/siding/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new SidingEntryRequest("T1", 5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("RESERVED"))
                .andExpect(jsonPath("$.data.state").doesNotExist())
                .andExpect(jsonPath("$.data.occupiedTrainId").value("T1"));
    }

    /** 重复请求同站 → success=false，原 RESERVED 不被破坏，字段仍为 status。 */
    @Test
    void duplicateRequest_returnsErrorAndKeepsReserved() throws Exception {
        dispatchMvc.perform(post("/api/dispatch/siding/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new SidingEntryRequest("T1", 3))))
                .andExpect(jsonPath("$.data.status").value("RESERVED"));

        dispatchMvc.perform(post("/api/dispatch/siding/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new SidingEntryRequest("T2", 3))))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("RESERVED")));

        dispatchMvc.perform(get("/api/dispatch/siding/3"))
                .andExpect(jsonPath("$.data.status").value("RESERVED"))
                .andExpect(jsonPath("$.data.occupiedTrainId").value("T1"));
    }

    /** 释放后 status 回 AVAILABLE。 */
    @Test
    void releaseSiding_restoresAvailableStatusField() throws Exception {
        dispatchMvc.perform(post("/api/dispatch/siding/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new SidingEntryRequest("T9", 7))));

        dispatchMvc.perform(post("/api/dispatch/siding/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new SidingReleaseRequest("T9"))))
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.occupiedTrainId").doesNotExist());
    }

    // ===== 任务 3：/siding/enter 编排 + 失败补偿 =====

    /** 任务 3-1 成功路径：AVAILABLE → RESERVED → OCCUPIED。 */
    @Test
    void sidingEnter_success_transitionsToOccupied() throws Exception {
        MockMvc mvc = buildVehicleMockMvc(vehicleService, sidingService);
        mvc.perform(post("/api/vehicle/siding/enter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enterBody("T1", 5, movingState())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());

        assertEquals("OCCUPIED", sidingService.getSidingStatus(5).getStatus());
        assertEquals("T1", sidingService.getSidingStatus(5).getOccupiedTrainId());
    }

    /**
     * 浏览器验收顺序：调度先显式预约到 RESERVED，随后同一列车调用车载驶入接口。
     * 车载接口应复用既有预留并推进到 OCCUPIED，不能因重复预约而拒绝自己。
     */
    @Test
    void sidingEnter_existingReservationBySameTrain_transitionsToOccupied() throws Exception {
        sidingService.requestSidingEntry("T1", 5);

        MockMvc mvc = buildVehicleMockMvc(vehicleService, sidingService);
        mvc.perform(post("/api/vehicle/siding/enter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enterBody("T1", 5, movingState())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());

        assertEquals("OCCUPIED", sidingService.getSidingStatus(5).getStatus());
        assertEquals("T1", sidingService.getSidingStatus(5).getOccupiedTrainId());
    }

    /**
     * 任务 3-2 预留成功 + 驶入仿真失败 → 回滚 AVAILABLE。
     * 用 spy stub enterSiding 抛异常，模拟续算失败。验证回滚释放本次预留。
     */
    @Test
    void sidingEnter_enterSimulationFails_rollsBackToAvailable() throws Exception {
        VehicleSimulationService spied = spy(vehicleService);
        // enterSiding 在编排第二步抛异常（模拟车辆续算失败）
        doThrow(new IllegalStateException("驶入仿真失败"))
                .when(spied).enterSiding(anyString(), anyInt(), any());

        MockMvc mvc = buildVehicleMockMvc(spied, sidingService);
        mvc.perform(post("/api/vehicle/siding/enter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enterBody("T1", 5, movingState())))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("驶入仿真失败")));

        // 回滚后站5必须回到 AVAILABLE，occupiedTrainId 清空
        SidingStatus after = sidingService.getSidingStatus(5);
        assertEquals("AVAILABLE", after.getStatus(), "驶入失败后侧线应回滚为 AVAILABLE");
        assertEquals(null, after.getOccupiedTrainId(), "回滚后 occupiedTrainId 应清空");
    }

    /**
     * 任务 3-3 预留成功 + 驶入仿真成功 + 确认占用失败 → 回滚 AVAILABLE。
     * 用 spy stub SidingDispatchService.confirmOccupied 抛异常。
     */
    @Test
    void sidingEnter_confirmOccupiedFails_rollsBackToAvailable() throws Exception {
        SidingDispatchService spiedSiding = spy(sidingService);
        // requestSidingEntry / enterSiding 正常，仅 confirmOccupied 抛异常
        doThrow(new IllegalStateException("确认占用失败"))
                .when(spiedSiding).confirmOccupied(anyString(), anyInt());

        MockMvc mvc = buildVehicleMockMvc(vehicleService, spiedSiding);
        mvc.perform(post("/api/vehicle/siding/enter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enterBody("T1", 6, movingState())))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("确认占用失败")));

        // 回滚后站6必须回到 AVAILABLE
        SidingStatus after = spiedSiding.getSidingStatus(6);
        assertEquals("AVAILABLE", after.getStatus(), "确认失败后侧线应回滚为 AVAILABLE");
        assertEquals(null, after.getOccupiedTrainId());
    }

    /**
     * 任务 3-4 回滚后另一列车可重新申请同站。
     * 接任务 3-2：站7回滚后，T2 发起 /siding/enter 应成功并占用。
     */
    @Test
    void sidingEnter_afterRollback_anotherTrainCanReclaim() throws Exception {
        // 第一轮：T1 驶入仿真失败 → 回滚（spy 仅 stub enterSiding，仅本轮 controller 使用 spied service）
        VehicleSimulationService spied = spy(vehicleService);
        doThrow(new IllegalStateException("驶入仿真失败"))
                .when(spied).enterSiding(anyString(), anyInt(), any());

        MockMvc mvc = buildVehicleMockMvc(spied, sidingService);
        mvc.perform(post("/api/vehicle/siding/enter")
                .contentType(MediaType.APPLICATION_JSON)
                .content(enterBody("T1", 7, movingState())))
                .andExpect(jsonPath("$.success").value(false));
        assertEquals("AVAILABLE", sidingService.getSidingStatus(7).getStatus());

        // 第二轮：不再 stub，T2 正常驶入站7应成功
        MockMvc mvc2 = buildVehicleMockMvc(vehicleService, sidingService);
        mvc2.perform(post("/api/vehicle/siding/enter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enterBody("T2", 7, movingState())))
                .andExpect(jsonPath("$.success").value(true));

        SidingStatus after = sidingService.getSidingStatus(7);
        assertEquals("OCCUPIED", after.getStatus(), "回滚后另一列车应能重新申请并占用");
        assertEquals("T2", after.getOccupiedTrainId());
    }

    /** 任务 3 不误释放既有占用：站8已被 OTHER 占用时，T1 申请失败，OTHER 占用不变。 */
    @Test
    void sidingEnter_stationAlreadyOccupied_doesNotReleaseExistingOccupation() throws Exception {
        sidingService.requestSidingEntry("OTHER", 8);
        sidingService.confirmOccupied("OTHER", 8);

        MockMvc mvc = buildVehicleMockMvc(vehicleService, sidingService);
        mvc.perform(post("/api/vehicle/siding/enter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enterBody("T1", 8, movingState())))
                .andExpect(jsonPath("$.success").value(false));

        // OTHER 的占用不被 T1 的失败请求破坏
        SidingStatus after = sidingService.getSidingStatus(8);
        assertEquals("OCCUPIED", after.getStatus());
        assertEquals("OTHER", after.getOccupiedTrainId());
        // 关键：T1 没有成功预留，不应残留任何侧线占用
        for (int sid = 1; sid <= 13; sid++) {
            assertNotEquals("T1", sidingService.getSidingStatus(sid).getOccupiedTrainId(),
                    "T1 未成功预留，不应残留占用任何侧线");
        }
    }

    // ===== 任务 3 精确回滚（P1）：同列车 T1 占 A，申请 B 失败 → A 不变，B 回 AVAILABLE =====

    /**
     * P1 核心场景：T1 已 OCCUPIED 站 3，再申请站 4，站4 驶入仿真失败。
     * 回滚必须精确释放站4（本次预留），<b>不得误释放站3（T1 既有占用）</b>。
     * 修复前根因：releaseSiding(trainId) 遍历释放第一条匹配 → 误释放站3、站4 残留 RESERVED。
     */
    @Test
    void sidingEnter_sameTrainFailsAtAnotherStation_onlyRollsBackFailedStation() throws Exception {
        // 前置：T1 已驶入并占用站 3（既有占用，不应被本次失败影响）
        sidingService.requestSidingEntry("T1", 3);
        sidingService.confirmOccupied("T1", 3);
        assertEquals("OCCUPIED", sidingService.getSidingStatus(3).getStatus());

        // 用 spy stub enterSiding 在站4 抛异常（模拟车辆驶入站4 续算失败）
        VehicleSimulationService spied = spy(vehicleService);
        doThrow(new IllegalStateException("站4 驶入仿真失败"))
                .when(spied).enterSiding(anyString(), anyInt(), any());

        MockMvc mvc = buildVehicleMockMvc(spied, sidingService);
        mvc.perform(post("/api/vehicle/siding/enter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enterBody("T1", 4, movingState())))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("站4 驶入仿真失败")));

        // 关键断言1：站3（T1 既有占用）必须保持 OCCUPIED，不被误释放
        SidingStatus station3 = sidingService.getSidingStatus(3);
        assertEquals("OCCUPIED", station3.getStatus(), "站3 为 T1 既有占用，失败回滚不得释放它");
        assertEquals("T1", station3.getOccupiedTrainId(), "站3 占用列车仍应为 T1");

        // 关键断言2：站4（本次预留后失败）必须回到 AVAILABLE，occupiedTrainId 清空
        SidingStatus station4 = sidingService.getSidingStatus(4);
        assertEquals("AVAILABLE", station4.getStatus(), "站4 回滚后应回 AVAILABLE");
        assertEquals(null, station4.getOccupiedTrainId(), "站4 回滚后 occupiedTrainId 应清空");
    }

    /**
     * P1 变体：T1 已 OCCUPIED 站 3，再申请站 4，预留成功但 confirmOccupied 失败。
     * 回滚仍只释放站4，站3 既有占用不动。
     */
    @Test
    void sidingEnter_sameTrainConfirmFailsAtAnotherStation_onlyRollsBackFailedStation() throws Exception {
        sidingService.requestSidingEntry("T1", 3);
        sidingService.confirmOccupied("T1", 3);

        // 用 spy stub SidingDispatchService.confirmOccupied 在站4 抛异常
        SidingDispatchService spiedSiding = spy(sidingService);
        doThrow(new IllegalStateException("站4 确认占用失败"))
                .when(spiedSiding).confirmOccupied(anyString(), anyInt());

        MockMvc mvc = buildVehicleMockMvc(vehicleService, spiedSiding);
        mvc.perform(post("/api/vehicle/siding/enter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enterBody("T1", 4, movingState())))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("站4 确认占用失败")));

        // 站3 既有占用不动
        assertEquals("OCCUPIED", spiedSiding.getSidingStatus(3).getStatus());
        assertEquals("T1", spiedSiding.getSidingStatus(3).getOccupiedTrainId());
        // 站4（本次预留后确认失败）回 AVAILABLE
        assertEquals("AVAILABLE", spiedSiding.getSidingStatus(4).getStatus());
        assertEquals(null, spiedSiding.getSidingStatus(4).getOccupiedTrainId());
    }

    /**
     * P1：预留阶段本身失败（站5 已被 OTHER 预留）→ reservedByThisRequest=false，
     * 不得释放任何既有侧线。验证即便 T1 已占用站3，本次申请站5失败也不会动站3。
     */
    @Test
    void sidingEnter_reservationFails_doesNotReleaseAnyExistingSiding() throws Exception {
        // T1 既有占用站3
        sidingService.requestSidingEntry("T1", 3);
        sidingService.confirmOccupied("T1", 3);
        // 站5 已被 OTHER 预留（RESERVED）→ requestSidingEntry 会抛 IllegalStateException
        sidingService.requestSidingEntry("OTHER", 5);

        MockMvc mvc = buildVehicleMockMvc(vehicleService, sidingService);
        mvc.perform(post("/api/vehicle/siding/enter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enterBody("T1", 5, movingState())))
                .andExpect(jsonPath("$.success").value(false));

        // 站3（T1 既有占用）保持不变
        assertEquals("OCCUPIED", sidingService.getSidingStatus(3).getStatus());
        assertEquals("T1", sidingService.getSidingStatus(3).getOccupiedTrainId());
        // 站5（OTHER 预留）保持不变
        assertEquals("RESERVED", sidingService.getSidingStatus(5).getStatus());
        assertEquals("OTHER", sidingService.getSidingStatus(5).getOccupiedTrainId());
        // T1 在站5 没有残留任何占用
        assertNotEquals("T1", sidingService.getSidingStatus(5).getOccupiedTrainId());
    }

    /**
     * P1 反例（验收阻断点）：站点已被同一列车预留（reservedByThisRequest=false），
     * 车载接口复用该预留、驶入仿真成功，随后 confirmOccupied 抛异常。
     * 回滚守卫必须保持为 false，<b>不得释放本请求并未创建的既有预留</b>——
     * 站5 应保持 RESERVED、占用列车仍为 T1。
     *
     * <p>修复前根因：confirmOccupied 的内层 catch 无条件调用 releaseSiding(trainId, stationId)，
     * 会把本请求并未创建的既有 RESERVED 误释放为 AVAILABLE，违反"仅回滚本请求预留"规则。</p>
     */
    @Test
    void sidingEnter_existingReservationConfirmFails_keepsExistingReservation() throws Exception {
        // 前置：调度已为 T1 在站5 预留（既有预留，非本请求创建）
        sidingService.requestSidingEntry("T1", 5);
        assertEquals("RESERVED", sidingService.getSidingStatus(5).getStatus());

        // 用 spy 仅 stub confirmOccupied 抛异常（requestSidingEntry 复用既有预留、enterSiding 正常）
        SidingDispatchService spiedSiding = spy(sidingService);
        doThrow(new IllegalStateException("确认占用失败"))
                .when(spiedSiding).confirmOccupied(anyString(), anyInt());

        MockMvc mvc = buildVehicleMockMvc(vehicleService, spiedSiding);
        mvc.perform(post("/api/vehicle/siding/enter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enterBody("T1", 5, movingState())))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("确认占用失败")));

        // 关键断言：既有预留不得被释放——站5 应保持 RESERVED，占用列车仍为 T1
        SidingStatus after = spiedSiding.getSidingStatus(5);
        assertEquals("RESERVED", after.getStatus(),
                "既有预留未被本请求创建，确认失败时不得回滚释放，应保持 RESERVED");
        assertEquals("T1", after.getOccupiedTrainId(),
                "既有预留的占用列车不得被清空");
    }

    /**
     * P1 反向保护：精确释放方法的契约——释放非本列车占用/预留的侧线应抛异常且不改状态。
     * 直接验证 releaseSiding(trainId, stationId) 在占用者不符时不修改目标侧线。
     */
    @Test
    void releaseSidingByStation_preciselyReleasesOnlyMatchingOccupant() {
        // T1 占站3，T2 占站6
        sidingService.requestSidingEntry("T1", 3);
        sidingService.confirmOccupied("T1", 3);
        sidingService.requestSidingEntry("T2", 6);
        sidingService.confirmOccupied("T2", 6);

        // 精确释放 T1@站6 → 站6 占用者是 T2，应抛异常
        assertThrows(IllegalStateException.class,
                () -> sidingService.releaseSiding("T1", 6),
                "T1 无权释放 T2 占用的站6");

        // 释放失败后状态不变：站3 仍 T1 OCCUPIED，站6 仍 T2 OCCUPIED
        assertEquals("OCCUPIED", sidingService.getSidingStatus(3).getStatus());
        assertEquals("T1", sidingService.getSidingStatus(3).getOccupiedTrainId());
        assertEquals("OCCUPIED", sidingService.getSidingStatus(6).getStatus());
        assertEquals("T2", sidingService.getSidingStatus(6).getOccupiedTrainId());

        // 精确释放 T1@站3 → 成功，只动站3，站6 不受影响
        SidingStatus released = sidingService.releaseSiding("T1", 3);
        assertEquals("AVAILABLE", released.getStatus());
        assertEquals("AVAILABLE", sidingService.getSidingStatus(3).getStatus());
        assertEquals("OCCUPIED", sidingService.getSidingStatus(6).getStatus());
        assertEquals("T2", sidingService.getSidingStatus(6).getOccupiedTrainId());

        // 旧重载（按 trainId 释放）仍兼容可用：释放 T2
        SidingStatus released2 = sidingService.releaseSiding("T2");
        assertEquals("AVAILABLE", released2.getStatus());
        assertEquals("AVAILABLE", sidingService.getSidingStatus(6).getStatus());
    }
}
