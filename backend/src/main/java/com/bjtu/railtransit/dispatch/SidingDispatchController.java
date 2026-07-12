package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.common.ApiResponse;
import com.bjtu.railtransit.dispatch.dto.SidingEntryRequest;
import com.bjtu.railtransit.dispatch.dto.SidingReleaseRequest;
import com.bjtu.railtransit.dispatch.dto.SidingStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 侧线调度 REST 接口（dispatch 层）。
 *
 * <p>与 {@link DispatchController} 的 {@code /api/dispatch/*} 路由风格一致，统一挂在
 * {@code /api/dispatch/siding} 下。所有接口返回 {@link ApiResponse} 统一格式。</p>
 *
 * <ul>
 *   <li>POST /api/dispatch/siding/request — 调度发起侧线撤离指令（AVAILABLE→RESERVED）</li>
 *   <li>GET  /api/dispatch/siding/{stationId} — 查询某站侧线状态</li>
 *   <li>POST /api/dispatch/siding/release — 故障车离线，释放侧线（OCCUPIED/RESERVED→AVAILABLE）</li>
 *   <li>GET  /api/dispatch/siding/all — 查询全部 13 站侧线状态</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/dispatch/siding")
public class SidingDispatchController {

    private final SidingDispatchService sidingDispatchService;

    public SidingDispatchController(SidingDispatchService sidingDispatchService) {
        this.sidingDispatchService = sidingDispatchService;
    }

    /**
     * 调度发起侧线撤离指令：为指定列车在某站预留侧线。
     *
     * <p>成功返回 RESERVED 状态；侧线非空闲时 {@code success=false}。</p>
     */
    @PostMapping("/request")
    public ApiResponse<SidingStatus> requestEntry(@RequestBody SidingEntryRequest request) {
        if (request == null || request.getTrainId() == null || request.getTrainId().isEmpty()) {
            return ApiResponse.error("trainId 不能为空");
        }
        try {
            SidingStatus status = sidingDispatchService.requestSidingEntry(
                    request.getTrainId(), request.getStationId());
            return ApiResponse.ok("侧线撤离请求成功", status);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("侧线撤离请求参数非法: " + e.getMessage());
        } catch (IllegalStateException e) {
            return ApiResponse.error("侧线撤离请求失败: " + e.getMessage());
        }
    }

    /**
     * 查询某站侧线状态。
     */
    @GetMapping("/{stationId}")
    public ApiResponse<SidingStatus> getStatus(@PathVariable int stationId) {
        try {
            SidingStatus status = sidingDispatchService.getSidingStatus(stationId);
            return ApiResponse.ok("ok", status);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("查询侧线状态失败: " + e.getMessage());
        }
    }

    /**
     * 故障车离线，释放其占用的侧线。
     */
    @PostMapping("/release")
    public ApiResponse<SidingStatus> releaseSiding(@RequestBody SidingReleaseRequest request) {
        if (request == null || request.getTrainId() == null || request.getTrainId().isEmpty()) {
            return ApiResponse.error("trainId 不能为空");
        }
        try {
            SidingStatus status = sidingDispatchService.releaseSiding(request.getTrainId());
            return ApiResponse.ok("侧线释放成功", status);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.error("侧线释放失败: " + e.getMessage());
        }
    }

    /**
     * 查询所有 13 站侧线状态。
     */
    @GetMapping("/all")
    public ApiResponse<List<SidingStatus>> getAllStatuses() {
        return ApiResponse.ok("ok", sidingDispatchService.getAllSidingStatuses());
    }
}
