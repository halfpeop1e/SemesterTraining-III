package com.bjtu.railtransit.vehicle.controller;

import com.bjtu.railtransit.common.ApiResponse;
import com.bjtu.railtransit.dispatch.SidingDispatchService;
import com.bjtu.railtransit.dispatch.dto.SidingStatus;
import com.bjtu.railtransit.vehicle.dto.SidingEnterRequest;
import com.bjtu.railtransit.vehicle.dto.SimulationControlRequest;
import com.bjtu.railtransit.vehicle.dto.SimulationResult;
import com.bjtu.railtransit.vehicle.dto.SimulationRunRequest;
import com.bjtu.railtransit.vehicle.dto.StationStop;
import com.bjtu.railtransit.vehicle.dto.TrainState;
import com.bjtu.railtransit.vehicle.model.LineProfile;
import com.bjtu.railtransit.vehicle.model.ScenarioConfig;
import com.bjtu.railtransit.vehicle.service.DemoScenarioProvider;
import com.bjtu.railtransit.vehicle.service.LineProfileJsonLoader;
import com.bjtu.railtransit.vehicle.service.LineProfileJsonLoader.StationEntry;
import com.bjtu.railtransit.vehicle.service.VehicleSimulationService;
import com.bjtu.railtransit.vehicle.protocol704.Protocol704VehicleControlBridge;
import com.bjtu.railtransit.vehicle.protocol704.Protocol704CommandLifecycle;
import com.bjtu.railtransit.vehicle.protocol704.MappedControlCommand;
import com.bjtu.railtransit.vehicle.enums.DrivingMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 郭逸晨车载模块——车辆仿真接口。
 *
 * <p>POST /api/vehicle/simulation/run：一次性仿真（单区间或多站连续）。</p>
 * <p>POST /api/vehicle/simulation/control：驾驶员控制续算。</p>
 * <p>POST /api/vehicle/siding/enter：调度侧线撤离后续算车辆驶入侧线停车状态序列。</p>
 */
@RestController
@RequestMapping("/api/vehicle")
public class VehicleSimulationController {

    private static final double NEXT_STATION_REACHED_TOLERANCE_M = 0.5;
    private static final double TARGET_HINT_TOLERANCE_M = 0.5;

    private final VehicleSimulationService vehicleSimulationService;
    private final DemoScenarioProvider demoScenarioProvider;
    private final LineProfileJsonLoader lineProfileJsonLoader;
    private final SidingDispatchService sidingDispatchService;
    private final Protocol704VehicleControlBridge protocol704VehicleControlBridge;

    public VehicleSimulationController(VehicleSimulationService vehicleSimulationService,
                                        DemoScenarioProvider demoScenarioProvider,
                                        LineProfileJsonLoader lineProfileJsonLoader,
                                        SidingDispatchService sidingDispatchService) {
        this(vehicleSimulationService, demoScenarioProvider, lineProfileJsonLoader, sidingDispatchService, null);
    }

    @Autowired
    public VehicleSimulationController(VehicleSimulationService vehicleSimulationService,
                                        DemoScenarioProvider demoScenarioProvider,
                                        LineProfileJsonLoader lineProfileJsonLoader,
                                        SidingDispatchService sidingDispatchService,
                                        Protocol704VehicleControlBridge protocol704VehicleControlBridge) {
        this.vehicleSimulationService = vehicleSimulationService;
        this.demoScenarioProvider = demoScenarioProvider;
        this.lineProfileJsonLoader = lineProfileJsonLoader;
        this.sidingDispatchService = sidingDispatchService;
        this.protocol704VehicleControlBridge = protocol704VehicleControlBridge;
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
    @PostMapping("/simulation/run")
    public ApiResponse<SimulationResult> run(
            @RequestBody(required = false) SimulationRunRequest request,
            @RequestHeader(value = "X-Run-Session-Id", required = false) String sessionId) {

        int fromId = request != null ? request.resolvedFromId() : 1;
        int toId = request != null ? request.resolvedToId() : 2;
        Double dwellTime = request != null ? request.getDwellTimeSeconds() : null;

        try {
            if (sessionId != null && sessionId.trim().isEmpty()) {
                throw new IllegalArgumentException("SESSION_ID_MISSING");
            }
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
                result.getSummary().setDepartureState("READY_TO_DEPART");
                registerSessionIfPresent(sessionId, fromId, toId);
                registerProtocol704Context(request, fromId, toId, result, DrivingMode.ATO);
                return ApiResponse.ok("ok", result);
            } else {
                // 单区间仿真（原有路径）
                LineProfile lineProfile = lineProfileJsonLoader.buildLineProfile(fromId, toId);
                ScenarioConfig scenario = demoScenarioProvider.buildScenario(lineProfile);

                StationEntry[] pair = lineProfileJsonLoader.findStationPair(fromId, toId);
                StationEntry fromStation = pair[0];
                StationEntry toStation = pair[1];

                SimulationResult result = vehicleSimulationService.run(scenario);
                result.getSummary().setDepartureState("READY_TO_DEPART");

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

                registerSessionIfPresent(sessionId, fromId, toId);
                registerProtocol704Context(request, fromId, toId, result, DrivingMode.ATO);
                return ApiResponse.ok("ok", result);
            }

        } catch (IllegalArgumentException e) {
            return ApiResponse.error("仿真请求参数非法: " + e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("仿真执行失败: " + e.getMessage());
        }
    }

    @PostMapping("/simulation/turnback")
    public ApiResponse<java.util.Map<String, Object>> turnback(
            @RequestBody SimulationControlRequest request,
            @RequestHeader(value = "X-Run-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-Run-Frame-Id", required = false) String frameId,
            @RequestHeader(value = "X-Doors-Safe", required = false) String doorsSafe,
            @RequestHeader(value = "X-Movement-Authority", required = false) String movementAuthority) {
        try {
            if (sessionId == null || sessionId.trim().isEmpty()) {
                throw new IllegalArgumentException("SESSION_ID_MISSING");
            }
            if (frameId == null || frameId.trim().isEmpty()) {
                throw new IllegalArgumentException("FRAME_ID_MISSING");
            }
            long parsedFrame;
            try {
                parsedFrame = Long.parseLong(frameId);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("FRAME_ID_INVALID");
            }
            if (parsedFrame < 0L) {
                throw new IllegalArgumentException("FRAME_ID_INVALID");
            }
            if (!"true".equalsIgnoreCase(doorsSafe)) {
                throw new IllegalArgumentException("DOORS_NOT_CONFIRMED_SAFE");
            }
            if (!"true".equalsIgnoreCase(movementAuthority)) {
                throw new IllegalArgumentException("LOCAL_TURNBACK_PERMIT_MISSING");
            }
            return ApiResponse.ok("ok", vehicleSimulationService.prepareLocalTurnback(
                    sessionId, parsedFrame, true, true, request));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("折返准备请求参数非法: " + e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("折返准备执行失败: " + e.getMessage());
        }
    }

    private void registerSessionIfPresent(String sessionId, int fromId, int toId) {
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            vehicleSimulationService.registerRunSession(sessionId, fromId, toId);
        }
    }

    private void registerProtocol704Context(SimulationRunRequest request, int fromId, int toId,
                                            SimulationResult result, DrivingMode mode) {
        if (protocol704VehicleControlBridge != null && request != null
                && request.getTrainId() != null && !request.getTrainId().isBlank()) {
            protocol704VehicleControlBridge.registerSimulation(request.getTrainId(), fromId, toId, result, mode, false);
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
    @PostMapping("/simulation/control")
    public ApiResponse<SimulationResult> control(
            @RequestBody SimulationControlRequest request) {

        if (request == null || request.getCurrentState() == null) {
            return ApiResponse.error("控制请求不能为空");
        }

        int fromId = request.getFromStationId() > 0 ? request.getFromStationId() : 1;
        int toId = request.getToStationId() > 0 ? request.getToStationId() : 2;

        if (protocol704VehicleControlBridge != null && request.getTrainId() != null
                && protocol704VehicleControlBridge.isPlcControlOwner(request.getTrainId())
                && request.getControlCommand() != null
                && !"emergency_brake".equals(request.getControlCommand().getCommand())) {
            return ApiResponse.error("PLC_704_LOCAL_V1 已拥有人工控制权；网页普通手柄已拒绝");
        }

        try {
            StationEntry[] routeBounds = lineProfileJsonLoader.findStationPair(fromId, toId);
            StationEntry fromStation = routeBounds[0];
            StationEntry nextStation = resolveNextStation(request, fromStation, toId);
            double authoritativeTarget = cumulativeTarget(fromStation, nextStation);
            validateClientTargetHints(request, nextStation, authoritativeTarget);

            // 物理控制目标始终由服务端根据路线与当前位置解析，客户端目标字段只作兼容校验。
            LineProfile lineProfile = lineProfileJsonLoader.buildLineProfile(fromId, nextStation.id);
            ScenarioConfig scenario = demoScenarioProvider.buildScenario(lineProfile);

            SimulationResult result = vehicleSimulationService.runContinuation(
                    request, scenario, authoritativeTarget);

            result.getSummary().setLineStartPosition(fromStation.km * 1000.0);
            result.getSummary().setLineTargetPosition(nextStation.km * 1000.0);
            result.getSummary().setFromStationName(fromStation.name);
            result.getSummary().setToStationName(nextStation.name);

            setAuthoritativeStationStops(result, request, nextStation, authoritativeTarget);
            expandContinuationStationStops(result, fromId, toId, nextStation.id);
            if (protocol704VehicleControlBridge != null) {
                protocol704VehicleControlBridge.recordWebControl(request.getTrainId(), request, result);
            }

            return ApiResponse.ok("ok", result);

        } catch (IllegalArgumentException e) {
            return ApiResponse.error("控制请求参数非法: " + e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("控制续算执行失败: " + e.getMessage());
        }
    }

    /** Vehicle-side confirmation after dispatch has authorized DEPART. */
    @PostMapping("/simulation/depart-confirm")
    public ApiResponse<Protocol704CommandLifecycle> departConfirm(@RequestBody Map<String, String> body) {
        String trainId = body == null ? null : body.get("trainId");
        if (protocol704VehicleControlBridge == null || trainId == null || trainId.isBlank()) {
            return ApiResponse.error("车载发车确认参数非法");
        }
        MappedControlCommand command = new MappedControlCommand();
        command.setCommand("DEPART_CONFIRM");
        Protocol704CommandLifecycle lifecycle = protocol704VehicleControlBridge.execute(trainId, command);
        if (!"EXECUTED".equals(lifecycle.getStatus())) {
            return ApiResponse.error("车载发车确认失败: " + lifecycle.getRejectionReason());
        }
        return ApiResponse.ok("车载已确认发车", lifecycle);
    }

    /**
     * 调度侧线撤离后续算：车辆从正线驶入侧线停车。
     *
     * <p>流程：
     * <ol>
     *   <li>调用 dispatch 层 {@code requestSidingEntry} 为列车预留侧线（AVAILABLE→RESERVED）。</li>
     *   <li>基于请求中的当前列车状态，调用 {@code enterSiding} 续算驶入侧线的状态序列。</li>
     *   <li>调用 dispatch 层 {@code confirmOccupied} 确认车辆已停入侧线（RESERVED→OCCUPIED）。</li>
     *   <li>返回状态序列供前端播放。</li>
     * </ol>
     * 若预留/确认失败，直接返回 {@code success=false}，不生成状态序列。</p>
     *
     * <p>请求体中 currentState.position 应为全程累计里程，返回 states 沿用同一坐标系。</p>
     */
    @PostMapping("/siding/enter")
    public ApiResponse<List<TrainState>> enterSiding(@RequestBody SidingEnterRequest request) {

        if (request == null || request.getTrainId() == null || request.getTrainId().isEmpty()) {
            return ApiResponse.error("trainId 不能为空");
        }
        if (request.getCurrentState() == null) {
            return ApiResponse.error("currentState 不能为空");
        }

        String trainId = request.getTrainId();
        int stationId = request.getStationId();

        // 预留成功标志：仅当本请求真正把侧线从 AVAILABLE 翻到 RESERVED 时置 true，
        // 后续失败才允许回滚释放。避免误释放别的列车已占用/预留的侧线。
        boolean reservedByThisRequest = false;
        try {
            // 1. dispatch 层预留侧线（AVAILABLE → RESERVED）
            //    若侧线非空闲（已被占用/预留），requestSidingEntry 抛 IllegalStateException，
            //    reservedByThisRequest 仍为 false → 不释放（保护既有占用）。
            //    调度若已为同一列车显式预留，则复用该 RESERVED 状态继续驶入；它不是
            //    本请求创建的预留，因此后续失败也不能由本请求回滚释放。
            SidingStatus existing = sidingDispatchService.getSidingStatus(stationId);
            boolean reservationAlreadyHeldByTrain =
                    "RESERVED".equals(existing.getStatus())
                            && trainId.equals(existing.getOccupiedTrainId());
            if (!reservationAlreadyHeldByTrain) {
                sidingDispatchService.requestSidingEntry(trainId, stationId);
                reservedByThisRequest = true;
            }

            // 2. 续算驶入侧线状态序列
            List<TrainState> sidingStates = vehicleSimulationService.enterSiding(
                    trainId, stationId, request.getCurrentState());

            // 3. 确认车辆已停入侧线（RESERVED → OCCUPIED）。
            //    若 confirmOccupied 失败，异常向外层 catch 传播，由外层统一按
            //    reservedByThisRequest 守卫回滚——仅当本请求确实创建了预留才释放。
            //    这样当列车复用既有预留、确认却失败时，不会误释放本请求并未创建的预留。
            sidingDispatchService.confirmOccupied(trainId, stationId);

            return ApiResponse.ok("侧线驶入仿真完成", sidingStates);

        } catch (IllegalArgumentException e) {
            rollbackReservationIfHeld(trainId, stationId, reservedByThisRequest);
            return ApiResponse.error("侧线驶入请求参数非法: " + e.getMessage());
        } catch (IllegalStateException e) {
            rollbackReservationIfHeld(trainId, stationId, reservedByThisRequest);
            return ApiResponse.error("侧线驶入失败: " + e.getMessage());
        } catch (Exception e) {
            rollbackReservationIfHeld(trainId, stationId, reservedByThisRequest);
            return ApiResponse.error("侧线驶入执行失败: " + e.getMessage());
        }
    }

    /**
     * 回滚本次请求申请的侧线预留：仅当 {@code reservedByThisRequest} 为 true 时释放，
     * 释放失败（如列车已不在占用列表中）静默忽略，不影响错误返回。
     *
     * <p>必须传 {@code stationId}：精确释放本次请求刚刚预留的那一条侧线（trainId+stationId 双绑定），
     * 不得遍历所有侧线释放"第一条匹配"，避免误释放该列车已占用的其它侧线。</p>
     */
    private void rollbackReservationIfHeld(String trainId, int stationId, boolean reservedByThisRequest) {
        if (!reservedByThisRequest) {
            return;
        }
        rollbackReservationIfHeld(trainId, stationId);
    }

    /**
     * 精确释放该列车在 {@code stationId} 上的侧线占用（兼容 RESERVED/OCCUPIED 两态）。
     * 仅供 {@link #rollbackReservationIfHeld(String, int, boolean)} 守卫通过后调用。
     * 释放失败静默忽略，用于失败补偿路径，不抛异常掩盖原始错误。
     */
    private void rollbackReservationIfHeld(String trainId, int stationId) {
        try {
            sidingDispatchService.releaseSiding(trainId, stationId);
        } catch (RuntimeException ignore) {
            // 释放失败不掩盖原始错误：侧线状态以 dispatch 服务为准，运维可后续核对
        }
    }

    private StationEntry resolveNextStation(SimulationControlRequest request, StationEntry fromStation, int toId) {
        double currentAbsolutePosition = request.getCurrentState().getAbsolutePosition() != null
                ? request.getCurrentState().getAbsolutePosition()
                : fromStation.km * 1000.0 + request.getCurrentState().getPosition();
        for (StationEntry station : lineProfileJsonLoader.listStations()) {
            if (station.id > fromStation.id && station.id <= toId
                    && station.km * 1000.0 > currentAbsolutePosition + NEXT_STATION_REACHED_TOLERANCE_M) {
                return station;
            }
        }
        throw new IllegalArgumentException("当前位置之后不存在路线内的下一有效站");
    }

    private static double cumulativeTarget(StationEntry fromStation, StationEntry targetStation) {
        return targetStation.km * 1000.0 - fromStation.km * 1000.0;
    }

    private void validateClientTargetHints(SimulationControlRequest request, StationEntry nextStation,
                                           double authoritativeTarget) {
        if (request.getNextStationId() != null && request.getNextStationId() != nextStation.id) {
            throw new IllegalArgumentException("客户端下一站与服务端路线解析不一致");
        }
        if (request.getNextStationName() != null && !request.getNextStationName().equals(nextStation.name)) {
            throw new IllegalArgumentException("客户端下一站名称与服务端路线解析不一致");
        }
        if (request.getTotalTargetPosition() > 0.0
                && Math.abs(request.getTotalTargetPosition() - authoritativeTarget) > TARGET_HINT_TOLERANCE_M) {
            throw new IllegalArgumentException("客户端目标里程与服务端下一站目标不一致");
        }
    }

    private void setAuthoritativeStationStops(SimulationResult result, SimulationControlRequest request,
                                              StationEntry nextStation, double authoritativeTarget) {
        if (result.getStationStops() == null || result.getStationStops().isEmpty()) {
            double actualPosition = result.getStates().get(result.getStates().size() - 1).getPosition();
            double stopError = actualPosition - authoritativeTarget;
            boolean inWindow = Math.abs(stopError) <= 0.5;
            result.setStationStops(java.util.Collections.singletonList(new StationStop(
                    nextStation.id, nextStation.name, request.getCurrentState().getPosition(), authoritativeTarget,
                    actualPosition, stopError, inWindow,
                    result.getStates().get(result.getStates().size() - 1).getTime(), 0.0)));
        }
    }

    /**
     * 多站续算 stationStops 补全：服务端已解析本次目标站后，
     * 扩展为 [nextStationId .. toId] 的完整后续站列表。
     */
    private void expandContinuationStationStops(SimulationResult result, int fromId, int toId, int nextId) {
        if (toId <= nextId) {
            return; // 目标站已是最终站，无需补全
        }
        List<com.bjtu.railtransit.vehicle.dto.StationStop> existing = result.getStationStops();
        if (existing == null || existing.size() != 1) {
            return; // 只在单元素返回（续算目标站）时补全；多元素保持不变
        }
        com.bjtu.railtransit.vehicle.dto.StationStop thisStop = existing.get(0);

        // 基于 configs/line-profile.json 权威公里标构造后续站列表
        java.util.List<StationEntry> all;
        StationEntry fromStation;
        try {
            all = lineProfileJsonLoader.listStations();
            StationEntry[] pair = lineProfileJsonLoader.findStationPair(fromId, toId);
            fromStation = pair[0];
        } catch (Exception e) {
            return; // 站点查询失败时保留原单元素，不破坏既有返回
        }
        double fromAbsM = fromStation.km * 1000.0;

        java.util.List<com.bjtu.railtransit.vehicle.dto.StationStop> expanded = new java.util.ArrayList<>();
        expanded.add(thisStop); // 首元素：本次目标站，保留 runContinuation 的真实 actualPosition/stopError/inWindow

        for (StationEntry s : all) {
            if (s.id <= nextId || s.id > toId) {
                continue;
            }
            double targetCumM = (s.km * 1000.0) - fromAbsM;
            // 尚未到达：actualPosition=-1 作为显式哨兵，区别于"已到站误差为 0"
            expanded.add(new com.bjtu.railtransit.vehicle.dto.StationStop(
                    s.id, s.name,
                    Double.NaN, targetCumM,
                    -1.0, 0.0, false,
                    Double.NaN, Double.NaN));
        }
        result.setStationStops(expanded);
    }
}
