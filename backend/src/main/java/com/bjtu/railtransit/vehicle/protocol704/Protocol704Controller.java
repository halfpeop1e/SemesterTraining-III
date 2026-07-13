package com.bjtu.railtransit.vehicle.protocol704;

import com.bjtu.railtransit.vehicle.dto.SimulationControlRequest;
import com.bjtu.railtransit.vehicle.enums.DrivingMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/vehicle/protocol704")
public class Protocol704Controller {

    @Autowired
    private Protocol704Service protocol704Service;

    @Autowired
    private Protocol704VehicleControlBridge protocol704VehicleControlBridge;

    @GetMapping("/status")
    public ResponseEntity<Protocol704Status> getStatus(
            @RequestParam(defaultValue = "T1") String trainId) {
        Protocol704Status status = protocol704Service.getStatus(trainId);
        return ResponseEntity.ok(status);
    }

    @PostMapping("/connect")
    public ResponseEntity<String> connect(
            @RequestParam(defaultValue = "T1") String trainId) {
        protocol704Service.start(trainId);
        return ResponseEntity.ok("Connecting to 704 for train " + trainId);
    }

    @PostMapping("/disconnect")
    public ResponseEntity<String> disconnect(
            @RequestParam(defaultValue = "T1") String trainId) {
        protocol704Service.stop(trainId);
        return ResponseEntity.ok("Disconnected from 704 for train " + trainId);
    }

    @PostMapping("/reset")
    public ResponseEntity<String> reset(
            @RequestParam(defaultValue = "T1") String trainId) {
        protocol704Service.reset(trainId);
        return ResponseEntity.ok("Reset completed for train " + trainId);
    }

    @PostMapping("/test-frame")
    public ResponseEntity<Protocol704Status> injectTestFrame(
            @RequestParam(defaultValue = "T1") String trainId,
            @RequestParam String type) {
        Protocol704Status status = protocol704Service.injectTestFrame(trainId, type);
        return ResponseEntity.ok(status);
    }

    /**
     * 只同步状态，不执行物理控制。
     *
     * <p>前端 ATO 播放在 frameIndex 推进时不会持续同步 Bridge，导致 Bridge.currentState
     * 停留在 registerSimulation 时的初始状态。点击 704 测试帧（尤其 EB）前先调用此接口，
     * 把当前 ATO 播放状态同步到 Bridge，使 EB 从列车真实当前位置开始制动，而不是回到
     * 初始发车位置。</p>
     *
     * <p>复用 SimulationControlRequest 作为请求体（不重复定义同义结构）。
     * 该接口只更新 Bridge.currentState/mode/lastUpdatedAt，不触发 runContinuation，
     * 不清除 emergencyLatched，不调用 reset API。</p>
     */
    @PostMapping("/sync-state")
    public ResponseEntity<Map<String, Object>> syncState(@RequestBody SimulationControlRequest request) {
        if (request == null || request.getTrainId() == null || request.getTrainId().isBlank()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("ok", false);
            err.put("message", "trainId 不能为空");
            return ResponseEntity.badRequest().body(err);
        }
        if (request.getCurrentState() == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("ok", false);
            err.put("message", "currentState 不能为空");
            return ResponseEntity.badRequest().body(err);
        }
        DrivingMode requestedMode = request.getCurrentMode();
        protocol704VehicleControlBridge.syncCurrentState(
                request.getTrainId(),
                request.getCurrentState(),
                requestedMode,
                request.getFromStationId() > 0 ? request.getFromStationId() : null,
                request.getToStationId() > 0 ? request.getToStationId() : null,
                request.isDepartureConfirmed() ? true : null
        );
        DrivingMode mode = protocol704VehicleControlBridge.getMode(request.getTrainId());
        if (mode == null) mode = DrivingMode.MANUAL;
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("trainId", request.getTrainId());
        resp.put("syncedPosition", request.getCurrentState().getPosition());
        resp.put("syncedAbsolutePosition", request.getCurrentState().getAbsolutePosition());
        resp.put("syncedMode", mode.name());
        return ResponseEntity.ok(resp);
    }
}
