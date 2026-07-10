package com.bjtu.railtransit.energy;

import com.bjtu.railtransit.domain.model.TrainState;
import com.bjtu.railtransit.domain.model.StatusReport;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 虚拟列车网络系统 —— 模拟车载设备（VCU/TCU/BCU/HMI/ATO）的
 * 通信状态、设备健康监控和网络数据吞吐量。
 *
 * 参考列车通信网络（TCN）架构：
 *   MVB/ECN 总线上挂载 VCU(车辆控制)、TCU(牵引控制)、BCU(制动控制)、HMI(人机界面)
 */
@Service
public class TrainNetworkService {

    /** 列车网络设备类型 */
    public enum DeviceType { VCU, TCU, BCU, HMI, ATO, ATP }

    /** 单个网络设备状态 */
    public static class DeviceState {
        public String deviceId;
        public DeviceType type;
        public String health;       // ONLINE / DEGRADED / OFFLINE / FAULT
        public double lastSeenSeconds;
        public long bytesReceived;
        public long bytesSent;
        public double dataRateKBps;  // 数据速率 KB/s
    }

    /** 列车网络状态 */
    public static class TrainNetworkState {
        public String trainId;
        public List<DeviceState> devices = new ArrayList<>();
        public String topologyStatus;  // NORMAL / DEGRADED / FAULT
        public double totalThroughputKBps;
        public int onlineDevices;
        public int totalDevices;
    }

    private final Map<String, TrainNetworkState> networkStates = new ConcurrentHashMap<>();
    // 记录上次上报时间和字节数用于计算速率
    private final Map<String, Long> lastReportBytes = new ConcurrentHashMap<>();
    private final Map<String, Double> lastReportTime = new ConcurrentHashMap<>();

    private static final int ESTIMATED_REPORT_SIZE_BYTES = 512; // StatusReport JSON 估算大小

    /**
     * 每仿真步调用：根据列车状态更新网络设备状态。
     */
    public Map<String, TrainNetworkState> stepNetworkUpdate(
            java.util.Collection<TrainState> trains, double simTimeSeconds) {

        for (TrainState t : trains) {
            String tid = t.getTrainId();
            TrainNetworkState ns = networkStates.computeIfAbsent(tid, k -> createDefault(tid));
            ns.devices.clear();

            // ── VCU (车辆控制单元) ──
            DeviceState vcu = buildDevice(tid, "VCU", DeviceType.VCU,
                    !"FINISHED".equals(t.getStatus()), t.getStateSource(), simTimeSeconds);
            ns.devices.add(vcu);

            // ── TCU (牵引控制单元) ──
            boolean tcuHealthy = t.getTractionState() != null
                    && !"FAULT".equals(t.getTractionState().getHealth());
            DeviceState tcu = buildDevice(tid, "TCU", DeviceType.TCU,
                    tcuHealthy && !"DEPOT_WAITING".equals(t.getStatus()),
                    tcuHealthy ? "ONLINE" : "FAULT", simTimeSeconds);
            ns.devices.add(tcu);

            // ── BCU (制动控制单元) ──
            boolean bcuHealthy = t.getBrakeState() != null
                    && !"FAULT".equals(t.getBrakeState().getHealth());
            DeviceState bcu = buildDevice(tid, "BCU", DeviceType.BCU,
                    bcuHealthy && !"DEPOT_WAITING".equals(t.getStatus()),
                    bcuHealthy ? "ONLINE" : "FAULT", simTimeSeconds);
            ns.devices.add(bcu);

            // ── HMI (人机界面) ──
            String hmiSource = t.getStateSource();
            boolean hmiOnline = "ONBOARD_REPORTED".equals(hmiSource)
                    && (simTimeSeconds - t.getLastReportTimeSeconds()) < 3.0;
            DeviceState hmi = buildDevice(tid, "HMI", DeviceType.HMI,
                    hmiOnline, hmiOnline ? "ONLINE" : "OFFLINE", simTimeSeconds);
            ns.devices.add(hmi);

            // ── ATO (列车自动运行) ──
            DeviceState ato = buildDevice(tid, "ATO", DeviceType.ATO,
                    true, "ONLINE", simTimeSeconds);
            ns.devices.add(ato);

            // ── ATP (列车自动防护) ──
            DeviceState atp = buildDevice(tid, "ATP", DeviceType.ATP,
                    !t.isEmergencyBraking(), t.isEmergencyBraking() ? "FAULT" : "ONLINE", simTimeSeconds);
            ns.devices.add(atp);

            // 计算网络统计数据
            ns.totalDevices = ns.devices.size();
            ns.onlineDevices = (int) ns.devices.stream().filter(d -> "ONLINE".equals(d.health)).count();
            int faultCount = (int) ns.devices.stream().filter(d -> "FAULT".equals(d.health)).count();
            ns.topologyStatus = faultCount > 0 ? "DEGRADED"
                    : ns.onlineDevices == ns.totalDevices ? "NORMAL" : "DEGRADED";

            // 数据吞吐量估算
            double timeDelta = simTimeSeconds - lastReportTime.getOrDefault(tid, simTimeSeconds - 1.0);
            long bytesDelta = ESTIMATED_REPORT_SIZE_BYTES;
            if (timeDelta > 0) {
                ns.totalThroughputKBps = (bytesDelta / 1024.0) / Math.max(0.1, timeDelta);
            }
            lastReportTime.put(tid, simTimeSeconds);

            // 分配吞吐量到各设备
            for (DeviceState d : ns.devices) {
                d.dataRateKBps = ns.totalThroughputKBps / ns.totalDevices;
                d.bytesReceived += bytesDelta / ns.totalDevices;
            }
        }

        return Collections.unmodifiableMap(networkStates);
    }

    private DeviceState buildDevice(String trainId, String name, DeviceType type,
                                     boolean online, String health, double simTimeSeconds) {
        DeviceState ds = new DeviceState();
        ds.deviceId = trainId + "-" + name;
        ds.type = type;
        ds.health = online ? (health != null ? health : "ONLINE") : "OFFLINE";
        ds.lastSeenSeconds = online ? simTimeSeconds : -1;
        return ds;
    }

    private TrainNetworkState createDefault(String trainId) {
        TrainNetworkState ns = new TrainNetworkState();
        ns.trainId = trainId;
        return ns;
    }

    public TrainNetworkState getNetworkState(String trainId) {
        return networkStates.get(trainId);
    }

    public Map<String, TrainNetworkState> getAllNetworkStates() {
        return networkStates;
    }
}
