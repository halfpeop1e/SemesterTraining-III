package com.bjtu.railtransit.vehicle.protocol704;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
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

    private final List<Integer> ports = new ArrayList<>();
    private final Map<String, Map<Integer, PortClient>> portClients = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> runningFlags = new ConcurrentHashMap<>();
    private final Map<String, Protocol704Status> statusMap = new ConcurrentHashMap<>();
    private final Map<String, RealtimeVehicleState> realtimeStates = new ConcurrentHashMap<>();

    private ExecutorService executor;
    private ScheduledExecutorService scheduler;
    private final Object initLock = new Object();

    @PostConstruct
    public void init() {
        parsePorts();
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "704-protocol-worker");
            t.setDaemon(true);
            return t;
        });
        scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "704-realtime-tick");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::tickRealtimeStates, 100, 100, TimeUnit.MILLISECONDS);
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
        if (scheduler != null) scheduler.shutdownNow();
        if (executor != null) executor.shutdownNow();
    }

    public void start(String trainId) {
        synchronized (initLock) {
            AtomicBoolean running = runningFlags.computeIfAbsent(trainId, k -> new AtomicBoolean(false));
            if (running.get()) return;
            running.set(true);

            Protocol704Status status = statusMap.computeIfAbsent(trainId, k -> new Protocol704Status());
            status.setTrainId(trainId);
            status.setHost(defaultHost);
            status.setPorts(new ArrayList<>(ports));
            status.setConnected(false);
            status.setStartTime(System.currentTimeMillis());
            status.setRecentLogs(Collections.synchronizedList(new LinkedList<>()));

            Map<Integer, PortConnectionStatus> portStatusMap = new ConcurrentHashMap<>();
            for (int port : ports) {
                PortConnectionStatus ps = new PortConnectionStatus();
                ps.setPort(port);
                ps.setConnected(false);
                portStatusMap.put(port, ps);
            }
            status.setPortStatuses(portStatusMap);

            realtimeStates.put(trainId, new RealtimeVehicleState());
            status.setRealtimeVehicleState(realtimeStates.get(trainId));

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
    }

    public Protocol704Status getStatus(String trainId) {
        return statusMap.computeIfAbsent(trainId, k -> {
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
            s.setRealtimeVehicleState(realtimeStates.computeIfAbsent(k, key -> new RealtimeVehicleState()));
            return s;
        });
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
            s.setRealtimeVehicleState(realtimeStates.computeIfAbsent(k, key -> new RealtimeVehicleState()));
            return s;
        });

        // build test frame
        byte[] frame = buildTestFrame(type);
        String hex = bytesToHex(frame);

        // parse frame
        Parsed704Frame parsed = Protocol704FrameParser.parseFrame(frame);

        // update status
        status.setLastRawHex(hex);
        status.setLastFrameLength(frame.length);
        status.setLastParsedFrame(parsed);

        if (parsed.getMappedCommand() != null) {
            MappedControlCommand mapped = parsed.getMappedCommand();
            status.setLastMappedCommand(mapped);

            RealtimeVehicleState st = realtimeStates.get(trainId);
            if (st != null) {
                st.setLastCommand(mapped.getCommand());
                st.setMode("MANUAL");
                st.setNote("test frame");
                st.setLastUpdateTime(System.currentTimeMillis());
            }
        }

        // build log entry
        Protocol704LogEntry entry = new Protocol704LogEntry();
        entry.setTrainId(trainId);
        entry.setPort(0); // test frame has no port
        entry.setDirection("test_frame");
        entry.setTimestamp(System.currentTimeMillis());
        entry.setRawHex(hex);
        entry.setFrameLength(frame.length);
        entry.setParsedFields(parsed.getFields());
        entry.setVerified(!parsed.isHasUnverifiedFields());
        entry.setMappedCommand(parsed.getMappedCommand());
        entry.setSource("TEST_FRAME");
        entry.setNote("test frame/" + type + " - does not represent real 704 connection");

        addLog(status, entry);

        return status;
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

    private void tickRealtimeStates() {
        try {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, RealtimeVehicleState> e : realtimeStates.entrySet()) {
                RealtimeVehicleState st = e.getValue();
                long dtMs = now - st.getLastUpdateTime();
                if (dtMs < 50) continue;
                double dt = dtMs / 1000.0;
                st.setLastUpdateTime(now);

                String cmd = st.getLastCommand();
                double accel = 0.0;
                if ("traction".equals(cmd)) {
                    accel = 1.0;
                } else if ("brake".equals(cmd)) {
                    accel = -1.2;
                } else if ("emergency_brake".equals(cmd)) {
                    accel = -2.5;
                } else {
                    accel = -0.05;
                }

                double newV = st.getVelocityMs() + accel * dt;
                if (newV < 0) newV = 0;
                double maxV = 22.0;
                if (newV > maxV) newV = maxV;

                double avgV = 0.5 * (st.getVelocityMs() + newV);
                double newPos = st.getPositionM() + avgV * dt;

                st.setVelocityMs(newV);
                st.setPositionM(newPos);
                st.setAccelerationMs2(accel);
            }
        } catch (Exception ex) {
            log.warn("realtime tick error", ex);
        }
    }

    private class PortClient implements Runnable {
        private final String trainId;
        private final String host;
        private final int port;
        private final Protocol704Status status;
        private Socket socket;
        private InputStream in;
        private volatile boolean closed = false;
        private long lastReceiveTime = 0;
        private long lastFrameLen = 0;
        private long connectStart = 0;
        private long frameCount = 0;
        private long lastFrameTimestamp = 0;

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
                    ps.setConnecting(false);
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
                        frameCount++;
                        ps.setLastReceiveTime(now);
                        ps.setLastFrameLength(n);
                        ps.setBytesReceived(ps.getBytesReceived() + n);
                        ps.setFrameCount(frameCount);

                        if (lastFrameTimestamp > 0) {
                            long interval = now - lastFrameTimestamp;
                            ps.setLastFrameIntervalMs(interval);
                        }
                        lastFrameTimestamp = now;

                        byte[] frame = Arrays.copyOf(buf, n);
                        handleFrame(frame);
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
                    try { if (socket != null) socket.close(); } catch (Exception ignored) {}
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

        private void handleFrame(byte[] frame) {
            // Mark port as connected only after receiving actual data, not just TCP handshake
            if (!ps.isConnected()) {
                ps.setConnected(true);
                updateOverallConnected();
            }

            String hex = bytesToHex(frame);
            Protocol704LogEntry entry = new Protocol704LogEntry();
            entry.setTrainId(trainId);
            entry.setPort(port);
            entry.setDirection("inbound");
            entry.setSource("HARDWARE");
            entry.setTimestamp(System.currentTimeMillis());
            entry.setRawHex(hex);
            entry.setFrameLength(frame.length);

            Parsed704Frame parsed = Protocol704FrameParser.parseFrame(frame);
            entry.setParsedFields(parsed.getFields());
            entry.setVerified(!parsed.isHasUnverifiedFields());
            entry.setNote(parsed.getNote());

            status.setLastRawHex(hex);
            status.setLastFrameLength(frame.length);
            status.setLastParsedFrame(parsed);

            if (parsed.getMappedCommand() != null) {
                MappedControlCommand mapped = parsed.getMappedCommand();
                status.setLastMappedCommand(mapped);

                RealtimeVehicleState st = realtimeStates.get(trainId);
                if (st != null) {
                    st.setLastCommand(mapped.getCommand());
                    st.setMode("MANUAL");
                    st.setNote(mapped.isVerified() ? "verified" : "DEMO_fallback_not_verified");
                }

                entry.setMappedCommand(mapped);
            }

            addLog(status, entry);
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