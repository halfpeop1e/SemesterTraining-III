package com.bjtu.railtransit.vehicle.protocol704;

import com.bjtu.railtransit.signal.service.SignalPlcDepartureService;
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
    private static final long INITIAL_RETRY_DELAY_MS = 1_000L;
    private static final long MAX_RETRY_DELAY_MS = 30_000L;
    private static final int WARN_AFTER_CONSECUTIVE_FAILURES = 5;

    @Value("${vehicle.protocol704.host:192.168.100.123}")
    private String defaultHost;

    @Value("${vehicle.protocol704.ports:8001,8002,8003}")
    private String portsConfig;

    @Value("${vehicle.protocol704.control-port:8001}")
    private int controlPort = 8001;

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
    private final SignalPlcDepartureService signalPlcDepartureService;

    private ExecutorService executor;
    private ScheduledExecutorService scheduler;
    private final Object initLock = new Object();

    Protocol704Service() {
        this.vehicleControlBridge = null;
        this.signalPlcDepartureService = null;
    }

    @Autowired
    public Protocol704Service(Protocol704VehicleControlBridge vehicleControlBridge,
                              SignalPlcDepartureService signalPlcDepartureService) {
        this.vehicleControlBridge = vehicleControlBridge;
        this.signalPlcDepartureService = signalPlcDepartureService;
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
            status.setConnectionNote("PLC input only; screen and PLC lamp output are owned by LabHilGateway");
            status.setOutputEnabled(false);
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

            log.info("704 protocol service started for train {}, host={}, plcPorts={} (input only)",
                    trainId, defaultHost, ports);
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

    private boolean isControlPort(int port) {
        // Unit/integration configurations often expose one ephemeral TCP port.
        // In that case the only configured port is necessarily the control port.
        return (ports.size() == 1 && ports.contains(port)) || port == controlPort;
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
            s.setConnectionNote("PLC input only; screen and PLC lamp output are owned by LabHilGateway");
            s.setOutputEnabled(false);
            return s;
        });
        refreshSimulationReadiness(trainId, status);
        return status;
    }

    public Protocol704Status injectTestFrame(String trainId, String type) {
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

        byte[] frame = buildTestFrame(type);
        String hex = bytesToHex(frame);

        processCompleteFrame(trainId, status, 0, frame, "LOCAL_TEST", "TEST_FRAME", "test frame/local parser test/" + type);

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

        bb.putInt(0, 0xAA55AA55);
        bb.putShort(4, (short) 46);
        bb.putShort(6, (short) 22);

        bb.putShort(8, (short) 2026);
        bb.putShort(10, (short) 7);
        bb.putShort(12, (short) 10);
        bb.putShort(14, (short) 12);
        bb.putShort(16, (short) 0);
        bb.putShort(18, (short) 0);

        bb.putShort(20, (short) 0);
        bb.putShort(22, (short) 0);

        frame[24] = 0x20;
        frame[25] = 0x00;
        bb.putShort(26, (short) 0);
        frame[28] = 0x00;
        frame[29] = 0x00;
        bb.putShort(30, (short) 0);
        bb.putShort(32, (short) 0);
        frame[34] = 0x00;
        frame[35] = 0x02;

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
            case "ato_start":
                // The desk hardware interlocks its two green buttons and emits
                // only this one PLC boolean: byte34 bit7.
                frame[34] = (byte) 0x80;
                bb.putShort(38, (short) 0x0000);
                bb.putShort(40, (short) 0);
                bb.putShort(42, (short) 0);
                break;
            case "traction":
                bb.putShort(38, (short) 0x0001);
                bb.putShort(40, (short) 50);
                bb.putShort(42, (short) 0);
                break;
            case "brake":
                bb.putShort(38, (short) 0x0002);
                bb.putShort(40, (short) 0);
                bb.putShort(42, (short) 60);
                break;
            case "emergency_brake":
                frame[28] = 0x01;
                bb.putShort(38, (short) 0x0000);
                bb.putShort(40, (short) 0);
                bb.putShort(42, (short) 0);
                break;
            case "coast":
            default:
                bb.putShort(38, (short) 0x0000);
                bb.putShort(40, (short) 0);
                bb.putShort(42, (short) 0);
                break;
        }

        bb.putShort(44, (short) 0);
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
        private final String connectionId;
        private Socket socket;
        private InputStream in;
        private OutputStream out;
        private volatile boolean closed = false;
        private volatile Thread workerThread;
        private int consecutiveFailures;
        private long retryDelayMs = INITIAL_RETRY_DELAY_MS;
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
            this.connectionId = "plc-" + trainId + "-" + port + "-" + UUID.randomUUID();
        }

        void close() {
            closed = true;
            Thread worker = workerThread;
            if (worker != null) worker.interrupt();
            if (vehicleControlBridge != null) {
                vehicleControlBridge.notifyPlcDisconnected(trainId, port, connectionId);
            }
            try { if (out != null) out.close(); } catch (IOException ignored) {}
            out = null;
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
            workerThread = Thread.currentThread();
            PortConnectionStatus ps = status.getPortStatuses().get(port);
            while (!closed) {
                AtomicBoolean running = runningFlags.get(trainId);
                if (running == null || !running.get()) break;
                long retryDelayAfterFailure = 0L;

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
                    consecutiveFailures = 0;
                    retryDelayMs = INITIAL_RETRY_DELAY_MS;
                    // local-v1, not validated with real PLC
                    log.debug("704 TCP socket opened train={} port={} connectionId={}, waiting for first frame (local-v1 output enabled, not validated with real PLC)", trainId, port, connectionId);

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
                            boolean controlFrame = isControlPort(port);
                            processCompleteFrame(trainId, status, port, frame,
                                    Protocol704VehicleControlBridge.SOURCE_PLC,
                                    Protocol704VehicleControlBridge.SOURCE_PLC,
                                    connectionId,
                                    controlFrame ? "inbound control frame" : "inbound mirror frame");
                            refreshOutputPreview();
                        }
                    }
                } catch (Exception e) {
                    ps.setConnected(false);
                    ps.setConnecting(false);
                    ps.setLastError(e.getClass().getSimpleName() + ": " + e.getMessage());
                    ps.setLastDisconnectTime(System.currentTimeMillis());
                    if (vehicleControlBridge != null) {
                        vehicleControlBridge.notifyPlcDisconnected(trainId, port, connectionId);
                    }
                    consecutiveFailures++;
                    retryDelayAfterFailure = retryDelayMs;
                    retryDelayMs = Math.min(MAX_RETRY_DELAY_MS, retryDelayMs * 2);
                    if (consecutiveFailures % WARN_AFTER_CONSECUTIVE_FAILURES == 0) {
                        log.warn("704 port {} connection failed {} consecutive times; retrying in {}ms: {}",
                                port, consecutiveFailures, retryDelayAfterFailure, e.getMessage());
                    } else {
                        log.debug("704 port {} disconnected; retrying in {}ms: {}",
                                port, retryDelayAfterFailure, e.getMessage());
                    }
                } finally {
                    try { if (out != null) out.close(); } catch (Exception ignored) {}
                    out = null;
                    try { if (in != null) in.close(); } catch (Exception ignored) {}
                    try { if (out != null) out.close(); } catch (Exception ignored) {}
                    out = null;
                    try { if (socket != null) socket.close(); } catch (Exception ignored) {}
                    accumulator.reset();
                    updateOverallConnected();
                }

                if (!closed) {
                    sleepBeforeRetry(retryDelayAfterFailure > 0 ? retryDelayAfterFailure : INITIAL_RETRY_DELAY_MS);
                }
            }
        }

        private void sleepBeforeRetry(long delayMs) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        private void refreshOutputPreview() {
            RealtimeVehicleState state = status.getRealtimeVehicleState();
            if (state == null) return;
            // This connection is read-only. LabHilGateway is the only component
            // permitted to write a reviewed PLC frame through writeOutbound().
            Protocol704OutputService.updateHmiPreview(state, status);
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
                                      String bridgeSource, String logSource, String notePrefix) {
        processCompleteFrame(trainId, status, port, frame, bridgeSource, logSource, null, notePrefix);
    }

    private void processCompleteFrame(String trainId, Protocol704Status status, int port, byte[] frame,
                                      String bridgeSource, String logSource, String connectionId, String notePrefix) {
            String hex = bytesToHex(frame);
            Protocol704LogEntry entry = new Protocol704LogEntry();
            entry.setTrainId(trainId);
            entry.setPort(port);
            entry.setDirection("TEST_FRAME".equals(logSource) ? "test_frame" : "inbound");
            entry.setSource(logSource);
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
                boolean controlsSimulation = "TEST_FRAME".equals(logSource) || isControlPort(port);
                if (vehicleControlBridge != null && controlsSimulation) {
                    // Door/key bits are part of the same cyclic PLC image as
                    // the motion command. Apply them first so ATO_START sees
                    // the current physical interlocks.
                    vehicleControlBridge.updateCabInputs(trainId, parsed.getFields());
                    // A physical ATO button frame is the final motion request,
                    // not a cached permission token. Re-check the live signal
                    // interlock after applying this frame's cab inputs so a
                    // revoked/shortened MA cannot be crossed in the gap before
                    // the next signal cycle clears the bridge flag.
                    if (Protocol704VehicleControlBridge.SOURCE_PLC.equals(bridgeSource)
                            && "ATO_START".equals(mapped.getCommand())
                            && (signalPlcDepartureService == null
                            || !signalPlcDepartureService.isAtoReady(trainId))) {
                        vehicleControlBridge.syncDepartureAuth(trainId, false);
                    }
                    Protocol704CommandLifecycle lifecycle;
                    if (Protocol704VehicleControlBridge.SOURCE_LOCAL_TEST.equals(bridgeSource)) {
                        lifecycle = vehicleControlBridge.executeFromTestFrame(trainId, mapped);
                    } else {
                        String cid = connectionId != null ? connectionId : ("port-" + port + "-" + UUID.randomUUID());
                        lifecycle = vehicleControlBridge.execute(trainId, mapped, bridgeSource, cid, port);
                    }
                    status.setLastCommandLifecycle(lifecycle);
                    updateRealtimeStateFromLifecycle(trainId, lifecycle);
                    refreshSimulationReadiness(trainId, status);
                } else if (!controlsSimulation) {
                    entry.setNote(entry.getNote() + "; mirror-only: port " + port
                            + " is observed but does not control the simulation (control port=" + controlPort + ")");
                }
            }
            addLog(status, entry);
    }

    private void updateRealtimeStateFromLifecycle(String trainId, Protocol704CommandLifecycle lifecycle) {
        RealtimeVehicleState state = realtimeStates.get(trainId);
        if (state == null || lifecycle == null) return;
        if (lifecycle.getParsedCommand() != null) {
            state.setLastCommand(lifecycle.getParsedCommand());
        }
        if (lifecycle.getResultMode() != null) {
            state.setMode(lifecycle.getResultMode());
        }
        if (lifecycle.getExecutedState() != null) {
            state.setPositionM(lifecycle.getExecutedState().getPosition());
            state.setVelocityMs(lifecycle.getExecutedState().getVelocity());
            state.setAccelerationMs2(lifecycle.getExecutedState().getAcceleration());
            state.setPhase(lifecycle.getExecutedState().getPhase().name());
        }
        if (vehicleControlBridge != null) {
            Protocol704VehicleControlBridge.ControlStateSnapshot snapshot = vehicleControlBridge.snapshot(trainId);
            if (snapshot != null && snapshot.state() != null) {
                state.setPositionM(snapshot.state().getPosition());
                state.setVelocityMs(snapshot.state().getVelocity());
                state.setAccelerationMs2(snapshot.state().getAcceleration());
                state.setPhase(snapshot.state().getPhase().name());
                state.setMode(snapshot.mode());
                state.setDoorsClosed(snapshot.doorsClosed());
                state.setDepartureState(departureState(trainId, snapshot));
                state.setAtoReady(isAtoReady(trainId));
            }
        }
        state.setNote(lifecycle.getStatus());
        state.setEmergencyLatched(lifecycle.isEmergencyLatchedAfter()
                || (vehicleControlBridge != null && vehicleControlBridge.isEmergencyLatched(trainId)));
        state.setLastUpdateTime(System.currentTimeMillis());

        // local-v1 terminal turnaround (not validated with real hardware):
        // When velocity is ~0 and reversing flag is set, flip direction and clear reversing.
        if (lifecycle.getExecutedState() != null && state.isReversing()) {
            double v = lifecycle.getExecutedState().getVelocity();
            boolean stopped = v <= 0.1;
            if (stopped) {
                String newDir = "UP".equals(state.getDirection()) ? "DOWN" : "UP";
                state.setDirection(newDir);
                state.setReversing(false);
                if (vehicleControlBridge != null) {
                    vehicleControlBridge.completeTurnback(trainId);
                }
                log.info("704 local-v1 turnback complete for train={}, new direction={}", trainId, newDir);
            }
        }
    }

    private void refreshSimulationReadiness(String trainId, Protocol704Status status) {
        refreshRealtimeStateFromBridge(trainId, status);
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

    /** Makes scheduled ATO progress visible through the existing status API. */
    private void refreshRealtimeStateFromBridge(String trainId, Protocol704Status status) {
        if (vehicleControlBridge == null || status == null) return;
        RealtimeVehicleState state = realtimeStates.get(trainId);
        Protocol704VehicleControlBridge.ControlStateSnapshot snapshot = vehicleControlBridge.snapshot(trainId);
        if (state == null || snapshot == null || snapshot.state() == null) return;
        state.setPositionM(snapshot.state().getPosition());
        state.setVelocityMs(snapshot.state().getVelocity());
        state.setAccelerationMs2(snapshot.state().getAcceleration());
        state.setPhase(snapshot.state().getPhase().name());
        state.setMode(snapshot.mode());
        state.setLastCommand(snapshot.lastCommand());
        state.setDoorsClosed(snapshot.doorsClosed());
        state.setDepartureState(departureState(trainId, snapshot));
        state.setAtoReady(isAtoReady(trainId));
        state.setEmergencyLatched(snapshot.emergencyLatched());
        state.setNote(vehicleControlBridge.atoWorkflowState(trainId));
        state.setLastUpdateTime(snapshot.lastUpdatedAt());
    }

    private boolean isAtoReady(String trainId) {
        return signalPlcDepartureService != null && signalPlcDepartureService.isAtoReady(trainId);
    }

    private String departureState(String trainId,
                                  Protocol704VehicleControlBridge.ControlStateSnapshot snapshot) {
        if (snapshot.emergencyLatched()) return "ATP_EMERGENCY_BRAKE";
        if (snapshot.atoRunning()) return "RUNNING";
        // The desk-facing summary must use the same complete interlock as the
        // ready lamp. Route authorization alone is insufficient: the key,
        // doors and MA must also be valid before advertising ATO_READY.
        if (isAtoReady(trainId)) return "ATO_READY";
        return snapshot.doorState();
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

    /**
     * Lightweight persistent TCP client for HMI (网络屏) or MMI (信号屏) output.
     * local-v1, not validated with real hardware.
     */
    private class HmiMmiClient implements Runnable {
        private final String trainId;
        private final String host;
        private final int port;
        private final Protocol704Status status;
        private final String label;
        private Socket socket;
        private OutputStream out;
        private volatile boolean closed;
        private volatile Thread workerThread;
        private int consecutiveFailures;
        private long retryDelayMs = INITIAL_RETRY_DELAY_MS;

        HmiMmiClient(String trainId, String host, int port, Protocol704Status status, String label) {
            this.trainId = trainId;
            this.host = host;
            this.port = port;
            this.status = status;
            this.label = label;
        }

        boolean isConnected() {
            return socket != null && socket.isConnected() && !socket.isClosed() && out != null;
        }

        OutputStream getOutputStream() {
            return out;
        }

        void close() {
            closed = true;
            Thread worker = workerThread;
            if (worker != null) worker.interrupt();
            try { if (out != null) out.close(); } catch (IOException ignored) {}
            out = null;
            try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        }

        @Override
        public void run() {
            workerThread = Thread.currentThread();
            status.getPortStatuses().computeIfAbsent(port, p -> {
                PortConnectionStatus ps = new PortConnectionStatus();
                ps.setPort(p);
                ps.setConnected(false);
                return ps;
            });

            while (!closed) {
                AtomicBoolean running = runningFlags.get(trainId);
                if (running == null || !running.get()) break;
                long retryDelayAfterFailure = 0L;

                PortConnectionStatus ps = status.getPortStatuses().get(port);
                if (ps != null) {
                    ps.setConnecting(true);
                    ps.setLastError(null);
                    ps.setConnected(false);
                }

                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
                    s.setSoTimeout(readTimeoutMs);
                    s.setTcpNoDelay(true);
                    this.socket = s;
                    this.out = s.getOutputStream();
                    if (ps != null) {
                        ps.setConnecting(false);
                        ps.setConnected(true);
                        ps.setLastConnectSuccessTime(System.currentTimeMillis());
                    }
                    consecutiveFailures = 0;
                    retryDelayMs = INITIAL_RETRY_DELAY_MS;
                    log.debug("704 {} client connected train={} {}:{}", label, trainId, host, port);

                    if ("HMI".equals(label)) {
                        InputStream in = s.getInputStream();
                        byte[] buf = new byte[128];
                        while (!closed && running.get()) {
                            int n = in.read(buf);
                            if (n < 0) throw new IOException(label + " connection closed by remote");
                            if (n >= 26) {
                                log.debug("704 {} received {}B, train={}", label, n, trainId);
                                byte[] frame = Arrays.copyOf(buf, n);
                                HmiTractionCutRequest cutReq = Protocol704HmiInputParser.parseTractionCut(frame);
                                if (cutReq != null) {
                                    RealtimeVehicleState rs = realtimeStates.get(trainId);
                                    if (rs != null) {
                                        cutReq.setTrainId(trainId);
                                        rs.setTractionCutMask(cutReq.getCarCutMask());
                                        rs.setLastTractionCutTime(System.currentTimeMillis());
                                        log.info("704 HMI traction cut received for train={}: mask={}", trainId,
                                                Arrays.toString(cutReq.getCarCutMask()));
                                    }
                                }
                            }
                        }
                    } else {
                        while (!closed && running.get()) {
                            try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                        }
                    }
                } catch (Exception e) {
                    if (ps != null) {
                        ps.setConnected(false);
                        ps.setConnecting(false);
                        ps.setLastError(label + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
                        ps.setLastDisconnectTime(System.currentTimeMillis());
                    }
                    consecutiveFailures++;
                    retryDelayAfterFailure = retryDelayMs;
                    retryDelayMs = Math.min(MAX_RETRY_DELAY_MS, retryDelayMs * 2);
                    if (consecutiveFailures % WARN_AFTER_CONSECUTIVE_FAILURES == 0) {
                        log.warn("704 {} connection failed {} consecutive times; retrying in {}ms train={} {}:{}: {}",
                                label, consecutiveFailures, retryDelayAfterFailure, trainId, host, port, e.getMessage());
                    } else {
                        log.debug("704 {} {}:{} disconnected; retrying in {}ms: {}",
                                label, host, port, retryDelayAfterFailure, e.getMessage());
                    }
                } finally {
                    try { if (out != null) out.close(); } catch (Exception ignored) {}
                    out = null;
                    try { if (socket != null) socket.close(); } catch (Exception ignored) {}
                }

                if (!closed) {
                    sleepBeforeRetry(retryDelayAfterFailure > 0 ? retryDelayAfterFailure : INITIAL_RETRY_DELAY_MS);
                }
            }
        }

        private void sleepBeforeRetry(long delayMs) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
