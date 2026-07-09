package com.bjtu.railtransit.signal.web;

import com.bjtu.railtransit.common.ApiResponse;
import com.bjtu.railtransit.dispatch.CommandBus;
import com.bjtu.railtransit.dispatch.DispatchEngine;
import com.bjtu.railtransit.dispatch.SimulationService;
import com.bjtu.railtransit.dispatch.StatusFusion;
import com.bjtu.railtransit.domain.model.StatusReport;
import com.bjtu.railtransit.domain.model.TrainCommand;
import com.bjtu.railtransit.domain.model.TrainState;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.bjtu.railtransit.signal.domain.SignalAspect;
import com.bjtu.railtransit.signal.model.LineProfile;
import com.bjtu.railtransit.signal.model.Route;
import com.bjtu.railtransit.signal.model.Signal;
import com.bjtu.railtransit.signal.model.Switch;
import com.bjtu.railtransit.signal.model.SwitchState;
import com.bjtu.railtransit.signal.service.LineProfileLoader;
import com.bjtu.railtransit.signal.service.MovingAuthorityService;
import com.bjtu.railtransit.signal.service.MovementAuthorityRegistry;
import com.bjtu.railtransit.signal.service.SignalInterlockingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 移动授权 REST 接口。
 *  - `POST /api/signal/ma`，入参 MaRequest{lineProfile, trains[, routes, nowSec]}
 *    返回真实 ApiResponse{success, message, data}，data 为 Map<trainId, MovingAuthority>
 *  - `GET /api/signal/line`，返回换算好的 LineProfile（含里程索引），供前端画平面图
 *  - 解析/计算异常 → success=false + 错误信息，不抛 500
 */
@RestController
@RequestMapping("/api/signal")
public class SignalController {

    private final MovingAuthorityService maService;
    private final LineProfileLoader lineProfileLoader;
    private final MovementAuthorityRegistry registry;
    private final SignalInterlockingService interlocking;

    // 信号系统作为通信中介所需的依赖
    private final StatusFusion statusFusion;
    private final CommandBus commandBus;
    private final DispatchEngine dispatchEngine;
    private final SimulationService simulationService;

    @Autowired
    public SignalController(MovingAuthorityService maService, LineProfileLoader lineProfileLoader,
                            MovementAuthorityRegistry registry, SignalInterlockingService interlocking,
                            StatusFusion statusFusion, CommandBus commandBus,
                            DispatchEngine dispatchEngine, SimulationService simulationService) {
        this.maService = maService;
        this.lineProfileLoader = lineProfileLoader;
        this.registry = registry;
        this.interlocking = interlocking;
        this.statusFusion = statusFusion;
        this.commandBus = commandBus;
        this.dispatchEngine = dispatchEngine;
        this.simulationService = simulationService;
    }

    /** Backward-compatible constructor for the existing standalone verification harnesses. */
    public SignalController(MovingAuthorityService maService, LineProfileLoader lineProfileLoader) {
        this(maService, lineProfileLoader, new MovementAuthorityRegistry(), null, null, null, null, null);
    }

    @GetMapping("/events")
    public ApiResponse<Map<String, Object>[]> events() {
        return ApiResponse.ok("events", new Map[0]);
    }

    @GetMapping("/ma/latest")
    public ApiResponse<Map<String, MovingAuthority>> latestMa() {
        return ApiResponse.ok("authoritative MA", registry.snapshot());
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("onlineTrains", registry.snapshot().size());
        status.put("alertCount", registry.snapshot().values().stream()
                .filter(ma -> ma.getEvent() != null
                        && ma.getEvent() != com.bjtu.railtransit.signal.domain.SignalEvent.NONE)
                .count());
        status.put("health", registry.getGeneration() == 0 ? "OFFLINE" : "HEALTHY");
        status.put("simulationTime", registry.getLastCycleTimeSeconds());
        status.put("generation", registry.getGeneration());
        status.put("source", registry.getSource());
        return ApiResponse.ok("signal runtime", status);
    }

    @GetMapping("/line")
    public ApiResponse<LineProfile> getLine() {
        try {
            LineProfile lp = lineProfileLoader.loadFromClasspath("line-profile.json");
            return ApiResponse.ok("", lp);
        } catch (Exception e) {
            return new ApiResponse<>(false, "load line failed: " + e.getMessage(), null);
        }
    }

    @PostMapping("/ma")
    public ApiResponse<Map<String, MovingAuthority>> ma(@RequestBody MaRequest req) {
        try {
            if (req == null || req.getLineProfile() == null || req.getTrains() == null) {
                return new ApiResponse<>(false, "invalid request: lineProfile and trains are required", null);
            }
            Map<String, com.bjtu.railtransit.signal.model.Route> routes =
                    req.getRoutes() != null ? req.getRoutes() : Collections.emptyMap();
            Map<String, MovingAuthority> data;
            if (req.getNowSec() != null) {
                data = maService.compute(req.getLineProfile(), req.getTrains(), routes, req.getNowSec());
            } else {
                data = maService.compute(req.getLineProfile(), req.getTrains(), routes);
            }
            return ApiResponse.ok("", data);
        } catch (Exception e) {
            return new ApiResponse<>(false, "compute failed: " + e.getMessage(), null);
        }
    }

    // ═══ 联锁控制 API ═══

    @PostMapping("/switch/{switchId}/operate")
    public ApiResponse<Switch> operateSwitch(@PathVariable String switchId, @RequestParam String state) {
        try {
            SwitchState target = "REVERSE".equalsIgnoreCase(state) ? SwitchState.REVERSE : SwitchState.NORMAL;
            Switch sw = interlocking.operateSwitch(switchId, target);
            return ApiResponse.ok("switch " + switchId + " → " + target, sw);
        } catch (Exception e) {
            return new ApiResponse<>(false, e.getMessage(), null);
        }
    }

    @GetMapping("/switch/all")
    public ApiResponse<java.util.List<Switch>> allSwitches() {
        return ApiResponse.ok("switches", interlocking.getAllSwitches());
    }

    @PostMapping("/route/build")
    public ApiResponse<Route> buildRoute(@RequestParam int routeId) {
        try {
            Route route = interlocking.buildRoute(routeId);
            return ApiResponse.ok("route " + routeId + " built", route);
        } catch (Exception e) {
            return new ApiResponse<>(false, e.getMessage(), null);
        }
    }

    @PostMapping("/route/{routeId}/cancel")
    public ApiResponse<Route> cancelRoute(@PathVariable int routeId) {
        try {
            Route route = interlocking.cancelRoute(routeId);
            return ApiResponse.ok("route " + routeId + " cancelled", route);
        } catch (Exception e) {
            return new ApiResponse<>(false, e.getMessage(), null);
        }
    }

    @GetMapping("/route/built")
    public ApiResponse<java.util.List<Route>> builtRoutes() {
        return ApiResponse.ok("built routes", interlocking.getBuiltRoutes());
    }

    @PostMapping("/signal/{signalId}/set")
    public ApiResponse<Signal> setSignal(@PathVariable int signalId, @RequestParam String aspect) {
        try {
            SignalAspect sa = SignalAspect.valueOf(aspect.toUpperCase());
            Signal sig = interlocking.setSignalAspect(String.valueOf(signalId), sa);
            return ApiResponse.ok("signal " + signalId + " → " + aspect, sig);
        } catch (Exception e) {
            return new ApiResponse<>(false, e.getMessage(), null);
        }
    }

    @GetMapping("/signal/aspects")
    public ApiResponse<Map<String, SignalAspect>> signalAspects() {
        return ApiResponse.ok("signal aspects", interlocking.getAllSignalAspects());
    }

    // ═══ 信号系统中转：HMI ↔ 中控通信 ═══

    /**
     * 接收HMI车载上报的状态，通过信号系统中转给中控。
     * HMI → 信号系统 → StatusFusion（中控）
     */
    @PostMapping("/report/status")
    public ApiResponse<String> reportStatus(@RequestBody StatusReport report) {
        if (statusFusion != null) {
            statusFusion.accept(report);
        }
        return ApiResponse.ok("signal system received status", report.getTrainId());
    }

    /**
     * HMI查询快照，信号系统汇总MA、指令、时刻表后返回。
     * HMI ← 信号系统（MA来自MovementAuthorityRegistry，指令来自CommandBus，时刻表来自DispatchEngine）
     */
    @GetMapping("/train/{trainId}/snapshot")
    public ApiResponse<Map<String, Object>> trainSnapshot(@PathVariable String trainId) {
        TrainState train = simulationService != null ? simulationService.findTrain(trainId) : null;
        MovingAuthority ma = registry.get(trainId);
        List<TrainCommand> trainCommands = commandBus != null ? commandBus.forTrain(trainId) : Collections.emptyList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("train", train);
        data.put("commands", trainCommands);
        data.put("currentTimeSeconds", simulationService != null ? simulationService.getSimulationTimeSeconds() : 0);
        data.put("movementAuthorityMeters", ma == null ? 0 : ma.getEndOfAuthorityM());
        data.put("speedLimitKmh", ma == null ? 0 : ma.getMaxSpeedKmh());
        data.put("movementAuthority", ma);
        data.put("communicationStatus", statusFusion != null && statusFusion.communicationStale(trainId) ? "STALE" : "ONLINE");
        data.put("safetyStatus", train != null && train.isEmergencyBraking() ? "EMERGENCY" : "NORMAL");
        data.put("signalSource", "SIGNAL_SYSTEM");

        // 该车辆的时刻表
        List<Map<String, Object>> timetableList = new ArrayList<>();
        if (dispatchEngine != null && dispatchEngine.getTimetable() != null) {
            Map<Integer, DispatchEngine.TimetableEntry> trainTimetable = dispatchEngine.getTimetable().get(trainId);
            if (trainTimetable != null) {
                for (DispatchEngine.TimetableEntry te : trainTimetable.values()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("stationId", te.stationId);
                    item.put("stationName", te.stationName);
                    item.put("stationIndex", te.stationIndex);
                    item.put("stationKm", te.stationKm);
                    item.put("plannedArrival", te.plannedArrival);
                    item.put("plannedDeparture", te.plannedDeparture);
                    item.put("plannedDwell", te.plannedDwell);
                    timetableList.add(item);
                }
            }
        }
        data.put("timetable", timetableList);

        return ApiResponse.ok("signal system snapshot", data);
    }
}
