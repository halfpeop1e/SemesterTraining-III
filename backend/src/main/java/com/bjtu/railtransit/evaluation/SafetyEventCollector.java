package com.bjtu.railtransit.evaluation;

import com.bjtu.railtransit.domain.model.SafetyEvent;
import com.bjtu.railtransit.domain.model.SimulationLog;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 安全事件收集器
 * 检测超速、紧急制动、制动不足、降级模式等事件
 */
@Service
public class SafetyEventCollector {

    /**
     * 收集安全事件
     * @param logs 仿真日志
     * @param speedLimits SegID → 限速(m/s)，用于超速检测
     */
    public List<SafetyEvent> collect(List<SimulationLog> logs, Map<Integer, Double> speedLimits) {
        List<SafetyEvent> events = new ArrayList<>();

        if (logs == null || logs.isEmpty()) return events;

        // 按列车分组并排序
        Map<Integer, List<SimulationLog>> grouped = new LinkedHashMap<>();
        for (SimulationLog log : logs) {
            grouped.computeIfAbsent(log.getTrainId(), k -> new ArrayList<>()).add(log);
        }

        for (Map.Entry<Integer, List<SimulationLog>> entry : grouped.entrySet()) {
            List<SimulationLog> trainLogs = entry.getValue();
            trainLogs.sort(Comparator.comparingLong(SimulationLog::getTimestamp));

            for (SimulationLog log : trainLogs) {
                int trainId = log.getTrainId();

                // 1. 紧急制动检测
                if (log.isEmergencyBrake()) {
                    SafetyEvent event = new SafetyEvent();
                    event.setTrainId(trainId);
                    event.setTimestamp(log.getTimestamp());
                    event.setEventType("eb_triggered");
                    event.setDescription("列车触发紧急制动");
                    event.setSpeedAtEvent(log.getSpeed());
                    event.setPositionAtEvent(log.getPosition());
                    events.add(event);
                }

                // 2. 超速检测
                if (speedLimits != null) {
                    Double limit = speedLimits.get(log.getCurrentSegId());
                    if (limit != null && log.getSpeed() > limit * 1.05) { // 超过限速5%
                        SafetyEvent event = new SafetyEvent();
                        event.setTrainId(trainId);
                        event.setTimestamp(log.getTimestamp());
                        event.setEventType("over_speed");
                        event.setDescription(String.format("超速: %.1f m/s > 限速 %.1f m/s (Seg %d)",
                                log.getSpeed(), limit, log.getCurrentSegId()));
                        event.setSpeedAtEvent(log.getSpeed());
                        event.setPositionAtEvent(log.getPosition());
                        events.add(event);
                    }
                }

                // 3. 制动不足检测：需要制动但可用制动数为0
                if ("brake".equals(log.getTractiveBrakeCmd()) && log.getAvailableBrakeCount() == 0) {
                    SafetyEvent event = new SafetyEvent();
                    event.setTrainId(trainId);
                    event.setTimestamp(log.getTimestamp());
                    event.setEventType("brake_insufficient");
                    event.setDescription("制动不可用: 可用制动数量为0");
                    event.setSpeedAtEvent(log.getSpeed());
                    event.setPositionAtEvent(log.getPosition());
                    events.add(event);
                }

                // 4. 降级模式
                if (log.getDrivingMode() != null &&
                        ("EUM".equals(log.getDrivingMode()) || "RM".equals(log.getDrivingMode()))) {
                    // 仅在首次进入降级模式时记录一次
                    SafetyEvent event = new SafetyEvent();
                    event.setTrainId(trainId);
                    event.setTimestamp(log.getTimestamp());
                    event.setEventType("degraded_mode");
                    event.setDescription("进入降级模式: " + log.getDrivingMode());
                    event.setSpeedAtEvent(log.getSpeed());
                    event.setPositionAtEvent(log.getPosition());
                    events.add(event);
                }
            }
        }

        events.sort(Comparator.comparingLong(SafetyEvent::getTimestamp));
        return events;
    }
}
