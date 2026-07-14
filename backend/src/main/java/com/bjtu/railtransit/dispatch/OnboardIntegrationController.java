package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.common.ApiResponse;
import com.bjtu.railtransit.domain.model.*;
import com.bjtu.railtransit.signal.service.SignalPlcDepartureService;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * 调度侧集成控制器 —— 仅保留调度特有端点。
 *
 * <p>架构原则：信号系统是车载唯一事实源。车载快照/状态上报/命令确认/事件上报
 * 统一走 {@code SignalController}。</p>
 * <p>本控制器只保留调度工作站、调度命令管理（下发+确认）、车载监控等调度特有接口。</p>
 */
@RestController
@RequestMapping("/api")
public class OnboardIntegrationController {
    private final CommandBus commands;
    private final StatusFusion fusion;
    private final SimulationService simulation;
    private final SignalPlcDepartureService signalPlcDepartureService;

    public OnboardIntegrationController(CommandBus commands, StatusFusion fusion,
            SimulationService simulation,
            SignalPlcDepartureService signalPlcDepartureService) {
        this.commands = commands;
        this.fusion = fusion;
        this.simulation = simulation;
        this.signalPlcDepartureService = signalPlcDepartureService;
    }

    @GetMapping("/dispatch/commands/{trainId}")
    public ApiResponse<List<TrainCommand>> commands(@PathVariable String trainId) {
        return ApiResponse.ok("commands", commands.forTrain(trainId));
    }

    @PostMapping("/dispatch/commands")
    public ApiResponse<TrainCommand> issue(@RequestBody Map<String, Object> body) {
        TrainCommand issued = commands.issue(
                String.valueOf(body.get("trainId")), String.valueOf(body.get("commandType")),
                number(body.get("targetValue")), String.valueOf(body.getOrDefault("reason", "Dispatcher request")),
                (int) number(body.getOrDefault("priority", 50)), String.valueOf(body.getOrDefault("source", "MANUAL")),
                simulation.getSimulationTimeSeconds());
        if ("DEPART".equalsIgnoreCase(issued.getCommandType())) {
            signalPlcDepartureService.onDepartureAuthorized(issued.getTrainId());
        }
        return ApiResponse.ok("command issued", issued);
    }

    @PostMapping("/dispatch/commands/confirm")
    public ApiResponse<TrainCommand> confirm(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok("confirmation recorded", commands.confirm(String.valueOf(body.get("commandId")),
                !Boolean.FALSE.equals(body.get("approved")), simulation.getSimulationTimeSeconds()));
    }

    @GetMapping("/onboard/monitoring")
    public ApiResponse<List<Map<String, Object>>> monitoring() {
        return ApiResponse.ok("onboard monitoring", fusion.monitoring());
    }

    @GetMapping("/dispatch/workstation")
    public ApiResponse<Map<String, Object>> workstation() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("automaticOperation", simulation.isRunning());
        data.put("commands", commands.all());
        data.put("pendingManualConfirmations", commands.pendingConfirmations());
        data.put("onboardReports", fusion.reports());
        data.put("onboardMonitoring", fusion.monitoring());
        boolean communicationHealthy = fusion.monitoring().stream()
                .anyMatch(item -> Boolean.TRUE.equals(item.get("online")));
        data.put("communicationStatus", Map.of("mode", "HTTP_ONBOARD", "healthy", communicationHealthy));
        data.put("protocolAdapterStatus",
                Map.of("signal", "READY", "vehicle", "READY", "driverDesk", "READY", "vision", "READY"));
        return ApiResponse.ok("dispatcher workstation", data);
    }

    private double number(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0;
    }
}
