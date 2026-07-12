package com.bjtu.railtransit.hil;

import com.bjtu.railtransit.vehicle.protocol704.Protocol704Service;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class LabHilGateway {
    private final HilSnapshotProvider snapshots;
    private final Protocol704Service plcService;

    @Value("${hil.enabled:false}") private boolean enabled;
    @Value("${hil.train-id:T1}") private String trainId;
    @Value("${hil.connect-timeout-ms:1000}") private int connectTimeoutMs;
    @Value("${hil.signal-screen.enabled:false}") private boolean signalEnabled;
    @Value("${hil.signal-screen.host:192.168.100.122}") private String signalHost;
    @Value("${hil.signal-screen.port:9999}") private int signalPort;
    @Value("${hil.network-screen.enabled:false}") private boolean networkEnabled;
    @Value("${hil.network-screen.host:192.168.100.121}") private String networkHost;
    @Value("${hil.network-screen.port:8888}") private int networkPort;
    @Value("${hil.plc-output.enabled:false}") private boolean plcOutputEnabled;
    @Value("${hil.plc-output.frame-format:documented-26}") private String plcOutputFrameFormat;

    private final TcpWriter signalWriter = new TcpWriter("signal-screen");
    private final TcpWriter networkWriter = new TcpWriter("network-screen");
    private final HilChannelStatus plcStatus = new HilChannelStatus("plc-output", "existing 704 sockets");

    public LabHilGateway(HilSnapshotProvider snapshots, Protocol704Service plcService) {
        this.snapshots = snapshots;
        this.plcService = plcService;
    }

    /** Teacher screens are display channels; 200 ms is adequate and matches the verified demo. */
    @Scheduled(fixedRateString = "${hil.screen-period-ms:200}")
    public void publishScreens() {
        if (!enabled) return;
        HilVehicleSnapshot snapshot = snapshots.snapshot(trainId);
        if (signalEnabled) signalWriter.send(signalHost, signalPort,
                TeacherDeviceFrameCodec.signalScreen(snapshot), connectTimeoutMs);
        else signalWriter.disable();
        if (networkEnabled) networkWriter.send(networkHost, networkPort,
                TeacherDeviceFrameCodec.networkScreen(snapshot), connectTimeoutMs);
        else networkWriter.disable();
    }

    /** PLC output is an on-change/on-demand contract. Send at 500 ms only when explicitly enabled. */
    @Scheduled(fixedRateString = "${hil.plc-output.period-ms:500}")
    public void publishPlcOutput() {
        plcStatus.setEnabled(enabled && plcOutputEnabled);
        if (!enabled || !plcOutputEnabled) {
            plcStatus.setConnected(false);
            return;
        }
        byte[] frame;
        try {
            frame = TeacherDeviceFrameCodec.plcOutput(snapshots.snapshot(trainId),
                    TeacherDeviceFrameCodec.PlcOutputFrameFormat.fromConfig(plcOutputFrameFormat));
        } catch (IllegalArgumentException e) {
            plcStatus.setConnected(false);
            plcStatus.setLastError(e.getMessage());
            return;
        }
        int sockets = plcService.writeOutbound(trainId, frame);
        plcStatus.setConnected(sockets > 0);
        if (sockets > 0) {
            plcStatus.incrementFramesSent();
            plcStatus.addBytesSent((long) sockets * frame.length);
            plcStatus.setLastSendTime(System.currentTimeMillis());
            plcStatus.setLastError(null);
        } else {
            plcStatus.setLastError("no connected 704 PLC socket for train " + trainId);
        }
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("trainId", trainId);
        result.put("signalScreen", signalWriter.status(signalHost, signalPort, enabled && signalEnabled));
        result.put("networkScreen", networkWriter.status(networkHost, networkPort, enabled && networkEnabled));
        result.put("plcOutput", plcStatus);
        result.put("vision", Map.of(
                "enabled", false,
                "reason", "UDP port and edge/signal ordering must be confirmed on the .124 laboratory host"));
        return result;
    }

    @PreDestroy
    public void shutdown() {
        signalWriter.close();
        networkWriter.close();
    }

    private static final class TcpWriter {
        private final String name;
        private Socket socket;
        private OutputStream out;
        private long framesSent;
        private long bytesSent;
        private long lastSendTime;
        private String lastError;
        private long nextReconnectAt;

        TcpWriter(String name) { this.name = name; }

        synchronized void send(String host, int port, byte[] frame, int timeoutMs) {
            try {
                ensureConnected(host, port, timeoutMs);
                if (out == null) return;
                out.write(frame);
                out.flush();
                framesSent++;
                bytesSent += frame.length;
                lastSendTime = System.currentTimeMillis();
                lastError = null;
            } catch (IOException e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                close();
                nextReconnectAt = System.currentTimeMillis() + 2000;
            }
        }

        private void ensureConnected(String host, int port, int timeoutMs) throws IOException {
            if (socket != null && socket.isConnected() && !socket.isClosed()) return;
            if (System.currentTimeMillis() < nextReconnectAt) return;
            Socket next = new Socket();
            next.connect(new InetSocketAddress(host, port), timeoutMs);
            next.setTcpNoDelay(true);
            socket = next;
            out = next.getOutputStream();
        }

        synchronized HilChannelStatus status(String host, int port, boolean enabled) {
            HilChannelStatus status = new HilChannelStatus(name, host + ":" + port);
            status.setEnabled(enabled);
            status.setConnected(enabled && socket != null && socket.isConnected() && !socket.isClosed());
            for (long i = 0; i < framesSent; i++) status.incrementFramesSent();
            status.addBytesSent(bytesSent);
            status.setLastSendTime(lastSendTime);
            status.setLastError(lastError);
            return status;
        }

        synchronized void disable() { close(); }

        synchronized void close() {
            try { if (out != null) out.close(); } catch (IOException ignored) {}
            try { if (socket != null) socket.close(); } catch (IOException ignored) {}
            out = null;
            socket = null;
        }
    }
}
