package com.bjtu.railtransit.vehicle.service;

import com.bjtu.railtransit.common.SimulationWebSocketHandler;
import com.bjtu.railtransit.domain.model.OnboardFrame;
import com.bjtu.railtransit.vehicle.dto.SimulationResult;
import com.bjtu.railtransit.vehicle.dto.TrainState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 车载端 WebSocket 流式仿真 —— 后端按时间推进轨迹并向 WS 推送每帧。
 * 20Hz（50ms 间隔）替代旧的前端 setInterval + 客户端回放。
 */
@Service
public class SimulationStreamingService {

    private static final Logger log = LoggerFactory.getLogger(SimulationStreamingService.class);
    private static final long STREAM_INTERVAL_MS = 50; // 20Hz

    private final SimulationWebSocketHandler wsHandler;

    /** trainId -> active stream */
    private final Map<String, StreamSession> sessions = new ConcurrentHashMap<>();

    public SimulationStreamingService(SimulationWebSocketHandler wsHandler) {
        this.wsHandler = wsHandler;
    }

    /**
     * 启动流式回放。
     *
     * @param trainId          列车 ID
     * @param result           预计算结果（states / summary / stationStops）
     * @param speedMultiplier  播放倍速（1x / 2x / 4x 等）
     */
    public void startStreaming(String trainId, SimulationResult result, double speedMultiplier) {
        if (result == null || result.getStates() == null || result.getStates().isEmpty()) {
            wsHandler.sendToTrain(trainId, OnboardFrame.error(trainId, "仿真结果为空"));
            return;
        }
        List<TrainState> states = result.getStates();
        long now = System.currentTimeMillis();
        double stepSeconds = (double) STREAM_INTERVAL_MS / 1000.0 * speedMultiplier;

        StreamSession prev = sessions.put(trainId, new StreamSession(states, result, now, stepSeconds, speedMultiplier));
        if (prev != null) {
            log.info("Streaming restarted for train {}", trainId);
        } else {
            log.info("Streaming started for train {}: {} frames, dx={}s, {}x",
                    trainId, states.size(), stepSeconds, speedMultiplier);
        }
    }

    /**
     * 停止流式回放。
     */
    public void stopStreaming(String trainId) {
        sessions.remove(trainId);
        log.info("Streaming stopped for train {}", trainId);
    }

    /**
     * 每 50ms — 推进流然后通过 WS 推送当前帧。
     */
    @Scheduled(fixedRate = STREAM_INTERVAL_MS)
    public void tickAll() {
        if (sessions.isEmpty()) return;
        long now = System.currentTimeMillis();

        for (var entry : sessions.entrySet()) {
            String trainId = entry.getKey();
            StreamSession s = entry.getValue();
            if (s.finished) continue;

            double elapsed = (now - s.startTimeMs) / 1000.0;
            double simTime = elapsed * s.speedMultiplier;
            int frameIdx = findFrameIndex(s.states, simTime);

            if (frameIdx >= s.states.size() - 1) {
                // 结束
                s.finished = true;
                OnboardFrame finished = OnboardFrame.finished(trainId, s.result);
                wsHandler.sendToTrain(trainId, finished);
                log.info("Streaming finished for train {}", trainId);
                continue;
            }

            TrainState frame = s.states.get(Math.max(frameIdx, 0));
            // 微插值：帧间线性（20Hz 下已经足够平滑，不需要额外插值）
            OnboardFrame f = OnboardFrame.fromTrainState(frame);
            wsHandler.sendToTrain(trainId, f);
        }
    }

    private int findFrameIndex(List<TrainState> states, double targetTime) {
        int lo = 0, hi = states.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (states.get(mid).getTime() <= targetTime) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    // ── 内部类 ──

    private static class StreamSession {
        final List<TrainState> states;
        final SimulationResult result;
        final long startTimeMs;
        final double stepSeconds;
        final double speedMultiplier;
        boolean finished;

        StreamSession(List<TrainState> states, SimulationResult result,
                      long startTimeMs, double stepSeconds, double speedMultiplier) {
            this.states = states;
            this.result = result;
            this.startTimeMs = startTimeMs;
            this.stepSeconds = stepSeconds;
            this.speedMultiplier = speedMultiplier;
        }
    }
}
