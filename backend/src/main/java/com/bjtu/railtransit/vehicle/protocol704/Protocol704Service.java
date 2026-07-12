package com.bjtu.railtransit.vehicle.protocol704;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class Protocol704Service {

    private static final Logger log = LoggerFactory.getLogger(Protocol704Service.class);

    @Value("${vehicle.protocol704.host:192.168.100.123}")
    private String defaultHost;

    @Value("${vehicle.protocol704.ports:8001,8002,8003}")
    private String portsConfig;

    @Value("${vehicle.protocol704.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${vehicle.protocol704.read-timeout-ms:1000}")
    private int readTimeoutMs;

    @Value("${vehicle.protocol704.auto-start:false}")
    private boolean autoStart;

    @Value("${vehicle.protocol704.fail-safe-on-stale:true}")
    private boolean failSafeOnStale = true;

    @Value("${vehicle.protocol704.stale-input-ms:1500}")
    private long staleInputMs = 1500;

    private final List<Integer> ports = new ArrayList<>();
    private final Map<String, Map<Integer, PortClient>> portClients = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> runningFlags = new ConcurrentHashMap<>();
    private final Map<String, Protocol704Status> statusMap = new ConcurrentHashMap<>();
    private final Map<String, RealtimeVehicleState> realtimeStates = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> staleFailSafeFlags = new ConcurrentHashMap<>();
    private final Protocol704VehicleControlBridge vehicleControlBridge;

    private ExecutorService executor;
    private ScheduledExecutorService scheduler;
    private final Object initLock = new Object();

    /** Kept for parser-only unit tests; production injection always supplies the bridge. */
    Protocol704Service() {
        this.vehicleControlBridge = null;
    }

    @Autowired
    public Protocol704Service(Protocol704VehicleControlBridge vehicleControlBridge) {
        this.vehicleControlBridge = vehicleControlBridge;
    }

    @PostConstruct
    public void init() {
        parsePorts();
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "704-protocol-worker");
            t.setDaemon(true);
            return t;
        });
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "704-input-watchdog");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::checkStaleInputs, 250, 250, TimeUnit.MILLISECONDS);
        if (autoStart) {
            start("T1");
        }
    }

    private void parsePorts() {
        ports.clear();
        if (portsConfig != null && !portsConfig.trim().isEmpty()) {
            for (String p : portsConfig.split(",")) {
                try {
                    int port = Integer.parseInt(p.trim());
                    if (port > 0 && port < 65536) ports.add(port);
                } catch (NumberFormatException ignored) {}
            }
        }
        if (ports.isEmpty()) {
            ports.add(8001);
            ports.add(8002);
            ports.add(8003);
        }
    }

    @PreDestroy
    public void shutdown() {
        for (String trainId : new ArrayList<>(runningFlags.keySet())) {
            stop(trainId);
        }
        if (executor != null) executor.shutdownNow();
        if (scheduler != null) scheduler.shutdownNow();
    }

    public void start(String trainId) {
        synchronized (initLock) {
            AtomicBoolean running = runningFlags.computeIfAbsent(trainId, k -> new AtomicBoolean(false));
            if (running.get()) return;
            running.set(true);
            staleFailSafeFlags.computeIfAbsent(trainId, k -> new AtomicBoolean(false)).set(false);

            Protocol704Status status = statusMap.computeIfAbsent(trainId, k -> new Protocol704Status());
            status.setTrainId(trainId);
            status.setHost(defaultHost);
            status.setPorts(new ArrayList<>(ports));
            status.setConnected(false);
            status.setStartTime(System.currentTimeMillis());
            status.setRecentLogs(Collections.synchronizedList(new LinkedList<>()));
            status.setConnectionNote("custom PLC local-v1; not IEC 60870-5-104");
            refreshSimulationReadiness(trainId, status);

            Map<Integer, PortConnectionStatus> portStatusMap = new ConcurrentHashMap<>();
            for (int port : ports) {
                PortConnectionStatus ps = new PortConnectionStatus();
                ps.setPort(port);
                ps.setConnected(false);
                portStatusMap.put(port, ps);
            }
            status.setPortStatuses(portStatusMap);

            RealtimeVehicleState realtimeState = createRealtimeState(trainId);
            realtimeStates.put(trainId, realtimeState);
            status.setRealtimeVehicleState(realtimeState);

            Map<Integer, PortClient> clients = new ConcurrentHashMap<>();
            for (int port : ports) {
                PortClient client = new PortClient(trainId, defaultHost, port, status);
                clients.put(port, client);
                executor.submit(client);
            }
            portClients.put(trainId, clients);
            log.info("704 protocol service started for train {}, host={}, ports={}", trainId, defaultHost, ports);
        }
    }

    public void stop(String trainId) {
        AtomicBoolean running = runningFlags.get(trainId);
        if (running == null) return;
        running.set(false);
        Map<Integer, PortClient> clients = portClients.remove(trainId);
        if (clients != null) {
            for (PortClient c : clients.values()) c.close();
        }
        Protocol704Status status = statusMap.get(trainId);
        if (status != null) status.setConnected(false);
        log.info("704 protocol service stopped for train {}", trainId);
    }

    public void reset(String trainId) {
        stop(trainId);
        statusMap.remove(trainId);
        realtimeStates.remove(trainId);
        staleFailSafeFlags.remove(trainId);
        if (vehicleControlBridge != null) vehicleControlBridge.unregisterSimulation(trainId);
    }

    /** Write through the already-owned PLC sockets; never opens a second client per port. */
    public int writeOutbound(String trainId, byte[] frame) {
        if (frame == null || frame.length == 0) return 0;
        Map<Integer, PortClient> clients = portClients.get(trainId);
        if (clients == null) return 0;
        int sent = 0;
        for (PortClient client : clients.values()) {
            if (client.write(frame)) sent++;
        }
        return sent;
    }

    public Protocol704Status getStatus(String trainId) {
        Protocol704Status status = statusMap.computeIfAbsent(trainId, k -> {
            Protocol704Status s = new Protocol704Status();
            s.setTrainId(k);
            s.setHost(defaultHost);
            s.setPorts(new ArrayList<>(ports));
            Map<Integer, PortConnectionStatus> pss = new ConcurrentHashMap<>();
            for (int p : ports) {
                PortConnectionStatus ps = new PortConnectionStatus();
                ps.setPort(p);
                ps.setConnected(false);
                pss.put(p, ps);
            }
            s.setPortStatuses(pss);
            s.setRecentLogs(Collections.synchronizedList(new LinkedList<>()));
            s.setRealtimeVehicleState(realtimeStates.computeIfAbsent(k, this::createRealtimeState));
            s.setConnectionNote("custom PLC local-v1; not IEC 60870-5-104");
            return s;
        });
        refreshSimulationReadiness(trainId, status);
        return status;
    }

    public Protocol704Status injectTestFrame(String trainId, String type) {
        // ensure status exists
        Protocol704Status status = statusMap.computeIfAbsent(trainId, k -> {
            Protocol704Status s = new Protocol704Status();
            s.setTrainId(k);
            s.setHost(defaultHost);
            s.setPorts(new ArrayList<>(ports));
            Map<Integer, PortConnectionStatus> pss = new ConcurrentHashMap<>();
            for (int p : ports) {
                PortConnectionStatus ps = new PortConnectionStatus();
                ps.setPort(p);
                ps.setConnected(false);
                pss.put(p, ps);
            }
            s.setPortStatuses(pss);
            s.setRecentLogs(Collections.synchronizedList(new LinkedList<>()));
            s.setRealtimeVehicleState(realtimeStates.computeIfAbsent(k, this::createRealtimeState));
            s.setConnectionNote("custom PLC local-v1; not IEC 60870-5-104");
            return s;
        });
        refreshSimulationReadiness(trainId, status);

        // Local parser/bridge test only: this does not open or write a TCP socket.
        byte[] frame = buildTestFrame(type);
        String hex = bytesToHex(frame);

        processCompleteFrame(trainId, status, 0, frame, "TEST_FRAME", "test frame/local parser test/" + type);

        return status;
    }

    private RealtimeVehicleState createRealtimeState(String trainId) {
        RealtimeVehicleState state = new RealtimeVehicleState();
        state.setTrainId(trainId);
        return state;
    }

    private byte[] buildTestFrame(String type) {
        byte[] frame = new byte[46];
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(frame).order(java.nio.ByteOrder.LITTLE_ENDIAN);

        // frame header
        bb.putInt(0, 0xAA55AA55);
        // totalLen=46, dataLen=22
        bb.putShort(4, (short) 46);
        bb.putShort(6, (short) 22);

        // timestamp (placeholder)
        bb.putShort(8, (short) 2026);
        bb.putShort(10, (short) 7);
        bb.putShort(12, (short) 10);
        bb.putShort(14, (short) 12);
        bb.putShort(16, (short) 0);
        bb.putShort(18, (short) 0);

        // verify type/code
        bb.putShort(20, (short) 0);
        bb.putShort(22, (short) 0);

        // data area
        frame[24] = 0x20; // doors_closed_ok
        frame[25] = 0x00;
        bb.putShort(26, (short) 0); // speed
        frame[28] = 0x00; // byte28
        frame[29] = 0x00;
        bb.putShort(30, (short) 0);
        bb.putShort(32, (short) 0);
        frame[34] = 0x00;
        frame[35] = 0x02; // key_switch_on

        // direction handle = forward
        bb.putShort(36, (short) 1);

        switch (type) {
            case "set_manual":
                // byte34 bit3 = mode_downgrade_confirm → SET_MANUAL
                frame[34] = (byte) 0x08;
                bb.putShort(38, (short) 0x0000);
                bb.putShort(40, (short) 0);
                bb.putShort(42, (short) 0);
                break;
            case "depart_confirm":
                // byte34 bit4 = confirm_btn → DEPART_CONFIRM
                frame[34] = (byte) 0x10;
                bb.putShort(38, (short) 0x0000);
                bb.putShort(40, (short) 0);
                bb.putShort(42, (short) 0);
                break;
            case "resume_ato":
                // byte34 bit2 = mode_upgrade_confirm → RESUME_ATO
                frame[34] = (byte) 0x04;
                bb.putShort(38, (short) 0x0000);
                bb.putShort(40, (short) 0);
                bb.putShort(42, (short) 0);
                break;
            case "traction":
                bb.putShort(38, (short) 0x0001); // masterHandle=1
                bb.putShort(40, (short) 50);     // tractionLevel=50
                bb.putShort(42, (short) 0);      // brakeLevel=0
                break;
            case "brake":
                bb.putShort(38, (short) 0x0002); // masterHandle=2
                bb.putShort(40, (short) 0);      // tractionLevel=0
                bb.putShort(42, (short) 60);     // brakeLevel=60
                break;
            case "emergency_brake":
                frame[28] = 0x01;                // EB button bit0
                bb.putShort(38, (short) 0x0000); // masterHandle=0
                bb.putShort(40, (short) 0);
                bb.putShort(42, (short) 0);
                break;
            case "coast":
            default:
                bb.putShort(38, (short) 0x0000); // masterHandle=0
                bb.putShort(40, (short) 0);
                bb.putShort(42, (short) 0);
                break;
        }

        bb.putShort(44, (short) 0); // padding
        return frame;
    }

    private void addLog(Protocol704Status status, Protocol704LogEntry entry) {
        List<Protocol704LogEntry> logs = status.getRecentLogs();
        if (logs == null) return;
        synchronized (logs) {
            logs.add(entry);
            while (logs.size() > 200) logs.remove(0);
        }
    }

    private class PortClient implements Runnable {
        private final String trainId;
        private final String host;
        private final int port;
        private final Protocol704Status status;
        private Socket socket;
        private InputStream in;
        private OutputStream out;
        private volatile boolean closed = false;
        private long lastReceiveTime = 0;
        private long lastFrameLen = 0;
        private long connectStart = 0;
        private long frameCount = 0;
        private long lastFrameTimestamp = 0;
        private final Protocol704FrameAccumulator accumulator = new Protocol704FrameAccumulator();

        PortClient(String trainId, String host, int port, Protocol704Status status) {
            this.trainId = trainId;
            this.host = host;
            this.port = port;
            this.status = status;
        }

        void close() {
            closed = true;
            try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        }

        boolean write(byte[] frame) {
            OutputStream target = out;
            PortConnectionStatus ps = status.getPortStatuses().get(port);
            if (target == null || ps == null || !ps.isConnected()) return false;
            synchronized (this) {
                try {
                    target.write(frame);
                    target.flush();
                    long now = System.currentTimeMillis();
                    ps.setBytesSent(ps.getBytesSent() + frame.length);
                    ps.setLastSendTime(now);
                    return true;
                } catch (IOException e) {
                    ps.setLastError("write: " + e.getMessage());
                    return false;
                }
            }
        }

        @Override
        public void run() {
            PortConnectionStatus ps = status.getPortStatuses().get(port);
            while (!closed) {
                AtomicBoolean running = runningFlags.get(trainId);
                if (running == null || !running.get()) break;

                ps.setConnecting(true);
                ps.setLastError(null);
                ps.setConnected(false);
                connectStart = System.currentTimeMillis();
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
                    s.setSoTimeout(readTimeoutMs);
                    s.setTcpNoDelay(true);
                    this.socket = s;
                    this.in = s.getInputStream();
                    this.out = s.getOutputStream();
                    ps.setConnecting(false);
                    ps.setConnected(true);
                    updateOverallConnected();
                    ps.setLastConnectSuccessTime(System.currentTimeMillis());
                    log.info("704 TCP socket opened train={} port={}, waiting for first frame", trainId, port);

                    byte[] buf = new byte[4096];
                    while (!closed && running.get()) {
                        int n = in.read(buf);
                        if (n < 0) {
                            throw new IOException("connection closed by remote");
                        }
                        if (n == 0) continue;
                        long now = System.currentTimeMillis();
                        lastReceiveTime = now;
                        lastFrameLen = n;
                        ps.setLastReceiveTime(now);
                        ps.setLastFrameLength(n);
                        ps.setBytesReceived(ps.getBytesReceived() + n);

                        if (lastFrameTimestamp > 0) {
                            long interval = now - lastFrameTimestamp;
                            ps.setLastFrameIntervalMs(interval);
                        }
                        lastFrameTimestamp = now;

                        for (byte[] frame : accumulator.append(Arrays.copyOf(buf, n))) {
                            frameCount++;
                            ps.setFrameCount(frameCount);
                            processCompleteFrame(trainId, status, port, frame, "HARDWARE", "inbound local-v1 frame");
                        }
                    }
                } catch (Exception e) {
                    ps.setConnected(false);
                    ps.setConnecting(false);
                    ps.setLastError(e.getClass().getSimpleName() + ": " + e.getMessage());
                    ps.setLastDisconnectTime(System.currentTimeMillis());
                    log.debug("704 port {} disconnected: {}", port, e.getMessage());
                } finally {
                    updateOverallConnected();
                    try { if (in != null) in.close(); } catch (Exception ignored) {}
                    try { if (out != null) out.close(); } catch (Exception ignored) {}
                    out = null;
                    try { if (socket != null) socket.close(); } catch (Exception ignored) {}
                    accumulator.reset();
                }

                if (!closed) {
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        }

        private void updateOverallConnected() {
            boolean any = false;
            for (PortConnectionStatus p : status.getPortStatuses().values()) {
                if (p.isConnected()) any = true;
            }
            status.setConnected(any);
        }
    }

    private void processCompleteFrame(String trainId, Protocol704Status status, int port, byte[] frame,
                                      String source, String notePrefix) {
            String hex = bytesToHex(frame);
            Protocol704LogEntry entry = new Protocol704LogEntry();
            entry.setTrainId(trainId);
            entry.setPort(port);
            entry.setDirection("TEST_FRAME".equals(source) ? "test_frame" : "inbound");
            entry.setSource(source);
            entry.setTimestamp(System.currentTimeMillis());
            entry.setRawHex(hex);
            entry.setFrameLength(frame.length);

            Parsed704Frame parsed = Protocol704FrameParser.parseFrame(frame);
            entry.setParsedFields(parsed.getFields());
            boolean validFrame = Boolean.TRUE.equals(parsed.getFields().get("header_valid"))
                    && Integer.valueOf(46).equals(parsed.getFields().get("total_len_field"))
                    && Integer.valueOf(22).equals(parsed.getFields().get("data_len_field"));
            entry.setVerified(validFrame);
            entry.setNote(notePrefix + "; " + parsed.getNote());

            status.setLastRawHex(hex);
            status.setLastFrameLength(frame.length);
            status.setLastParsedFrame(parsed);

            if (validFrame && parsed.getMappedCommand() != null) {
                MappedControlCommand mapped = parsed.getMappedCommand();
                status.setLastMappedCommand(mapped);
                entry.setMappedCommand(mapped);
                status.setReceivedValidFrame(true);
                status.setLastValidFrameTime(System.currentTimeMillis());
                status.setStaleInputFailSafeTriggered(false);
                staleFailSafeFlags.computeIfAbsent(trainId, k -> new AtomicBoolean(false)).set(false);
                if (vehicleControlBridge != null) {
                    Protocol704CommandLifecycle lifecycle = vehicleControlBridge.execute(trainId, mapped);
                    status.setLastCommandLifecycle(lifecycle);
                    updateRealtimeStateFromLifecycle(trainId, lifecycle);
                    refreshSimulationReadiness(trainId, status);
                }
            }
            addLog(status, entry);
    }

    private void updateRealtimeStateFromLifecycle(String trainId, Protocol704CommandLifecycle lifecycle) {
        RealtimeVehicleState state = realtimeStates.get(trainId);
        if (state == null || lifecycle == null || lifecycle.getExecutedState() == null) return;
        state.setLastCommand(lifecycle.getParsedCommand());
        state.setMode(lifecycle.getResultMode());
        state.setPositionM(lifecycle.getExecutedState().getPosition());
        state.setVelocityMs(lifecycle.getExecutedState().getVelocity());
        state.setAccelerationMs2(lifecycle.getExecutedState().getAcceleration());
        state.setNote(lifecycle.getStatus());
        state.setLastUpdateTime(System.currentTimeMillis());
    }

    private void refreshSimulationReadiness(String trainId, Protocol704Status status) {
        if (vehicleControlBridge == null) {
            status.setSimulationReady(false);
            status.setSimulationReadiness("CONTROL_BRIDGE_UNAVAILABLE");
            status.setSimulationContextUpdatedAt(0);
            status.setActiveBinding("PLC socket is not bound to a simulation control bridge");
            return;
        }
        Protocol704VehicleControlBridge.ControlReadiness readiness = vehicleControlBridge.readiness(trainId);
        status.setSimulationReady(readiness.ready());
        status.setSimulationReadiness(readiness.reason());
        status.setSimulationContextUpdatedAt(readiness.lastUpdatedAt());
        status.setActiveBinding(readiness.ready()
                ? "PLC desk -> simulated train " + trainId + " (ready)"
                : "PLC desk -> train " + trainId + " not ready: " + readiness.reason());
    }

    private void checkStaleInputs() {
        if (!failSafeOnStale || vehicleControlBridge == null || staleInputMs <= 0) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Protocol704Status> item : statusMap.entrySet()) {
            String trainId = item.getKey();
            Protocol704Status status = item.getValue();
            AtomicBoolean running = runningFlags.get(trainId);
            if (running == null || !running.get() || !status.isReceivedValidFrame()) continue;
            if (now - status.getLastValidFrameTime() <= staleInputMs) continue;
            AtomicBoolean fired = staleFailSafeFlags.computeIfAbsent(trainId, k -> new AtomicBoolean(false));
            if (!fired.compareAndSet(false, true)) continue;

            MappedControlCommand emergency = new MappedControlCommand();
            emergency.setCommand("emergency_brake");
            emergency.setLevelPercent(100);
            emergency.setTargetDecel(2.5);
            emergency.setDirection("ZERO");
            emergency.setNote("FAIL_SAFE: PLC input stale for more than " + staleInputMs + "ms");
            Protocol704CommandLifecycle lifecycle = vehicleControlBridge.execute(trainId, emergency);
            status.setLastMappedCommand(emergency);
            status.setLastCommandLifecycle(lifecycle);
            status.setStaleInputFailSafeTriggered(true);
            status.setStaleInputFailSafeTime(now);
            updateRealtimeStateFromLifecycle(trainId, lifecycle);
            log.warn("PLC input stale; fail-safe emergency brake train={} ageMs={}",
                    trainId, now - status.getLastValidFrameTime());
        }
    }

    static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", bytes[i] & 0xFF));
        }
        return sb.toString();
    }
}
