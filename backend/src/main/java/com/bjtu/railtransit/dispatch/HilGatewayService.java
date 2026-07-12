package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.domain.model.TrainState;
import com.bjtu.railtransit.signal.domain.SignalAspect;
import com.bjtu.railtransit.vehicle.enums.DrivingMode;
import com.bjtu.railtransit.vehicle.protocol704.MappedControlCommand;
import com.bjtu.railtransit.vehicle.protocol704.Protocol704Service;
import com.bjtu.railtransit.vehicle.protocol704.Protocol704Status;
import com.bjtu.railtransit.vehicle.protocol704.Protocol704VehicleControlBridge;
import com.bjtu.railtransit.vehicle.protocol704.RealtimeVehicleState;
import com.bjtu.railtransit.vehicle.service.LineProfileJsonLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HIL 网关服务：统一管理信号屏 66B 和网络屏 572B 的 TCP 周期发送。
 *
 * <p>
 * 依赖 {@link Protocol704Service} 获取实时车辆状态
 * （速度/加速度/模式/位置等），编码后写入两个屏。
 *
 * <p>
 * 默认关闭，通过 {@code hil.signal-screen.enabled} /
 * {@code hil.network-screen.enabled} 启用。
 */
@Service
@EnableScheduling
public class HilGatewayService {

    private static final Logger log = LoggerFactory.getLogger(HilGatewayService.class);

    @Value("${hil.signal-screen.enabled:false}")
    private boolean signalScreenEnabled;

    @Value("${hil.signal-screen.host:192.168.100.122}")
    private String signalScreenHost;

    @Value("${hil.signal-screen.port:9999}")
    private int signalScreenPort;

    @Value("${hil.signal-screen.interval-ms:200}")
    private long signalScreenIntervalMs;

    @Value("${hil.network-screen.enabled:false}")
    private boolean networkScreenEnabled;

    @Value("${hil.network-screen.host:192.168.100.121}")
    private String networkScreenHost;

    @Value("${hil.network-screen.port:8888}")
    private int networkScreenPort;

    @Value("${hil.network-screen.interval-ms:200}")
    private long networkScreenIntervalMs;

    @Value("${hil.train-id:T1}")
    private String trainId;

    private final Protocol704Service protocol704Service;
    private final Protocol704VehicleControlBridge bridge;
    private final LineProfileJsonLoader lineProfileJsonLoader;
    private final RedisDataBus redisDataBus;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Socket signalScreenSocket;
    private OutputStream signalScreenOut;
    private long lastSignalScreenWrite;

    private Socket networkScreenSocket;
    private OutputStream networkScreenOut;
    private long lastNetworkScreenWrite;

    public HilGatewayService(Protocol704Service protocol704Service,
            Protocol704VehicleControlBridge bridge,
            LineProfileJsonLoader lineProfileJsonLoader,
            RedisDataBus redisDataBus) {
        this.protocol704Service = protocol704Service;
        this.bridge = bridge;
        this.lineProfileJsonLoader = lineProfileJsonLoader;
        this.redisDataBus = redisDataBus;
    }

    @PostConstruct
    public void init() {
        if (signalScreenEnabled || networkScreenEnabled) {
            running.set(true);
            log.info("HIL gateway started: signalScreen={}, networkScreen={}, trainId={}",
                    signalScreenEnabled, networkScreenEnabled, trainId);
        }
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        closeQuietly(signalScreenSocket);
        closeQuietly(networkScreenSocket);
    }

    /**
     * 定时任务：每 200ms（默认）尝试连接并写出。
     * 连接失败时静默重试，不阻塞后续周期。
     */
    @Scheduled(fixedDelayString = "${hil.signal-screen.interval-ms:200}")
    public void writeSignalScreen() {
        if (!signalScreenEnabled || !running.get())
            return;

        Protocol704Status status = protocol704Service.getStatus(trainId);
        RealtimeVehicleState state = status.getRealtimeVehicleState();

        // Redis 降级: PLC 未连接时从 Redis 数据总线读取列车状态
        if (state == null && redisDataBus.isConnected()) {
            TrainState ts = redisDataBus.getTrainState(trainId);
            if (ts != null) {
                // 从 TrainState 构造 RealtimeVehicleState
                state = new RealtimeVehicleState();
                state.setTrainId(ts.getTrainId());
                state.setVelocityMs(ts.getSpeed() / 3.6);
                state.setAccelerationMs2(ts.getAcceleration() / 3.6);
                state.setMode("SIM");
            }
        }
        if (state == null)
            return;

        Map<String, Object> ctx = bridge.getContextInfo(trainId);
        MappedControlCommand lastCmd = status.getLastMappedCommand();

        // Resolve station IDs from simulation context
        int currStationId = ctx != null ? (int) ctx.getOrDefault("fromStationId", 0) : 0;
        int nextStationId = ctx != null ? (int) ctx.getOrDefault("toStationId", 0) : 0;
        int endStationId = nextStationId;

        // Speed limit from LineProfile (20 m/s ≈ 72 km/h, assumed value)
        double speedLimitKmh = LineProfileJsonLoader.ASSUMED_SPEED_LIMIT_MPS * 3.6;

        // Direction from last PLC command: directionHandle 1=FORWARD→上行(0),
        // 2=REVERSE→下行(1)
        boolean isDown = lastCmd != null && lastCmd.getDirectionHandle() == 2;

        // Signal aspect: green if moving, red otherwise
        SignalAspect aspect = state.getVelocityMs() > 0.1 ? SignalAspect.GREEN : SignalAspect.RED;

        try {
            ensureSignalScreenConnected();

            byte[] frame = SignalScreen66BEncoder.encode(
                    state.getVelocityMs(),
                    state.getAccelerationMs2(),
                    speedLimitKmh,
                    parseMode(state.getMode()),
                    currStationId, nextStationId, endStationId,
                    isDown,
                    aspect,
                    "traction".equals(state.getLastCommand()),
                    "brake".equals(state.getLastCommand()) || "emergency_brake".equals(state.getLastCommand()),
                    "emergency_brake".equals(state.getLastCommand()));

            signalScreenOut.write(frame);
            signalScreenOut.flush();
            lastSignalScreenWrite = System.currentTimeMillis();
        } catch (Exception e) {
            closeQuietly(signalScreenSocket);
            signalScreenSocket = null;
            signalScreenOut = null;
            log.debug("Signal screen write failed (will retry): {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${hil.network-screen.interval-ms:200}")
    public void writeNetworkScreen() {
        if (!networkScreenEnabled || !running.get())
            return;

        Protocol704Status status = protocol704Service.getStatus(trainId);
        RealtimeVehicleState state = status.getRealtimeVehicleState();

        // Redis 降级: PLC 未连接时从 Redis 数据总线读取列车状态
        if (state == null && redisDataBus.isConnected()) {
            TrainState ts = redisDataBus.getTrainState(trainId);
            if (ts != null) {
                state = new RealtimeVehicleState();
                state.setTrainId(ts.getTrainId());
                state.setVelocityMs(ts.getSpeed() / 3.6);
                state.setAccelerationMs2(ts.getAcceleration() / 3.6);
                state.setMode("SIM");
            }
        }
        if (state == null)
            return;

        Map<String, Object> ctx = bridge.getContextInfo(trainId);
        MappedControlCommand lastCmd = status.getLastMappedCommand();

        int currStationId = ctx != null ? (int) ctx.getOrDefault("fromStationId", 0) : 0;
        int nextStationId = ctx != null ? (int) ctx.getOrDefault("toStationId", 0) : 0;
        int endStationId = nextStationId;

        double nextStationDistM = 1000.0;
        if (ctx != null) {
            double position = ((Number) ctx.getOrDefault("position", 0.0)).doubleValue();
            double targetPos = resolveTargetStopPosition(currStationId, nextStationId);
            nextStationDistM = Math.max(0, targetPos - position);
        }

        double speedLimitKmh = LineProfileJsonLoader.ASSUMED_SPEED_LIMIT_MPS * 3.6;
        boolean isDown = lastCmd != null && lastCmd.getDirectionHandle() == 2;

        double tractionForce = "traction".equals(state.getLastCommand()) ? 50.0 : 0.0;
        double brakeForce = "brake".equals(state.getLastCommand()) ? 50.0
                : "emergency_brake".equals(state.getLastCommand()) ? 100.0 : 0.0;

        try {
            ensureNetworkScreenConnected();

            byte[] frame = NetworkScreen572BEncoder.encode(
                    state.getVelocityMs(),
                    state.getAccelerationMs2(),
                    parseMode(state.getMode()),
                    isDown,
                    currStationId, nextStationId, endStationId,
                    nextStationDistM,
                    speedLimitKmh,
                    tractionForce,
                    brakeForce,
                    1);

            networkScreenOut.write(frame);
            networkScreenOut.flush();
            lastNetworkScreenWrite = System.currentTimeMillis();
        } catch (Exception e) {
            closeQuietly(networkScreenSocket);
            networkScreenSocket = null;
            networkScreenOut = null;
            log.debug("Network screen write failed (will retry): {}", e.getMessage());
        }
    }

    private void ensureSignalScreenConnected() throws IOException {
        if (signalScreenSocket != null && signalScreenSocket.isConnected()
                && !signalScreenSocket.isClosed()) {
            return;
        }
        signalScreenSocket = new Socket();
        signalScreenSocket.setTcpNoDelay(true);
        signalScreenSocket.connect(new InetSocketAddress(signalScreenHost, signalScreenPort), 3000);
        signalScreenOut = signalScreenSocket.getOutputStream();
        log.info("Signal screen connected: {}:{}", signalScreenHost, signalScreenPort);
    }

    private void ensureNetworkScreenConnected() throws IOException {
        if (networkScreenSocket != null && networkScreenSocket.isConnected()
                && !networkScreenSocket.isClosed()) {
            return;
        }
        networkScreenSocket = new Socket();
        networkScreenSocket.setTcpNoDelay(true);
        networkScreenSocket.connect(new InetSocketAddress(networkScreenHost, networkScreenPort), 3000);
        networkScreenOut = networkScreenSocket.getOutputStream();
        log.info("Network screen connected: {}:{}", networkScreenHost, networkScreenPort);
    }

    private static DrivingMode parseMode(String modeStr) {
        if (modeStr == null)
            return DrivingMode.MANUAL;
        return switch (modeStr.toUpperCase()) {
            case "ATO" -> DrivingMode.ATO;
            case "EMERGENCY" -> DrivingMode.EMERGENCY;
            default -> DrivingMode.MANUAL;
        };
    }

    /**
     * 根据起止站 ID 解析目标停车位置（累积米）。
     * 通过 LineProfileJsonLoader 查找站点公里标差值。
     */
    private double resolveTargetStopPosition(int fromStationId, int toStationId) {
        if (fromStationId <= 0 || toStationId <= 0)
            return 1000.0;
        try {
            LineProfileJsonLoader.StationEntry[] pair = lineProfileJsonLoader.findStationPair(fromStationId,
                    toStationId);
            return (pair[1].km - pair[0].km) * 1000.0;
        } catch (Exception e) {
            return 1000.0;
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    // -- exposed for monitoring --
    public boolean isSignalScreenConnected() {
        return signalScreenSocket != null && signalScreenSocket.isConnected()
                && !signalScreenSocket.isClosed();
    }

    public boolean isNetworkScreenConnected() {
        return networkScreenSocket != null && networkScreenSocket.isConnected()
                && !networkScreenSocket.isClosed();
    }

    public long getLastSignalScreenWrite() {
        return lastSignalScreenWrite;
    }

    public long getLastNetworkScreenWrite() {
        return lastNetworkScreenWrite;
    }
}
