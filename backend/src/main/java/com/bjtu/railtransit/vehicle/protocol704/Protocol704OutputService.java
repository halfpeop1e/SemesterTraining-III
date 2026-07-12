package com.bjtu.railtransit.vehicle.protocol704;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;

/**
 * local-v1 PLC output send helper; not validated with real PLC.
 */
public final class Protocol704OutputService {

    private static final Logger log = LoggerFactory.getLogger(Protocol704OutputService.class);

    private Protocol704OutputService() {
    }

    public static byte[] sendPlcOutput(OutputStream out, RealtimeVehicleState state,
                                       PortConnectionStatus portStatus, Protocol704Status status) {
        byte[] frame = Protocol704OutputEncoder.encodeOutputFrame(state, LocalDateTime.now());
        String hex = bytesToHex(frame);
        if (out == null) {
            if (portStatus != null) {
                portStatus.setOutputErrorCount(portStatus.getOutputErrorCount() + 1);
                portStatus.setLastOutputError("output stream is null");
            }
            return frame;
        }
        try {
            out.write(frame);
            out.flush();
            if (portStatus != null) {
                portStatus.setOutputFrameCount(portStatus.getOutputFrameCount() + 1);
                portStatus.setLastOutputTime(System.currentTimeMillis());
                portStatus.setLastOutputHex(hex);
                portStatus.setLastOutputError(null);
            }
            if (status != null) {
                status.setLastOutputFrame(hex);
            }
            log.debug("704 local-v1 output frame sent, not validated with real PLC, len={}", frame.length);
        } catch (IOException e) {
            if (portStatus != null) {
                portStatus.setOutputErrorCount(portStatus.getOutputErrorCount() + 1);
                portStatus.setLastOutputError(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            log.warn("704 local-v1 output send failed (not validated with real PLC): {}", e.getMessage());
        } catch (Exception e) {
            if (portStatus != null) {
                portStatus.setOutputErrorCount(portStatus.getOutputErrorCount() + 1);
                portStatus.setLastOutputError(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            log.warn("704 local-v1 output unexpected error: {}", e.getMessage());
        }
        return frame;
    }

    public static void updateHmiPreview(RealtimeVehicleState state, Protocol704Status status) {
        if (status == null) {
            return;
        }
        byte[] hmi = Protocol704HmiEncoder.encodeHmiFrame(state, LocalDateTime.now());
        int previewLen = Math.min(64, hmi.length);
        byte[] preview = new byte[previewLen];
        System.arraycopy(hmi, 0, preview, 0, previewLen);
        status.setLastOutputHmi(bytesToHex(preview));
    }

    /**
     * Send HMI 572B frame via TCP OutputStream. Caller manages the socket lifecycle.
     *
     * @return the encoded HMI frame bytes (even if send fails)
     */
    public static byte[] sendHmiOutput(OutputStream out, RealtimeVehicleState state,
                                       PortConnectionStatus hmiStatus, Protocol704Status status) {
        byte[] frame = Protocol704HmiEncoder.encodeHmiFrame(state, LocalDateTime.now());
        return sendFrame(out, frame, hmiStatus, status, "HMI");
    }

    /**
     * Send MMI 66B frame via TCP OutputStream. Caller manages the socket lifecycle.
     *
     * @return the encoded MMI frame bytes (even if send fails)
     */
    public static byte[] sendMmiOutput(OutputStream out, RealtimeVehicleState state,
                                       PortConnectionStatus mmiStatus, Protocol704Status status) {
        byte[] frame = Protocol704MmiEncoder.encodeMmiFrame(state, LocalDateTime.now());
        return sendFrame(out, frame, mmiStatus, status, "MMI");
    }

    /** Generic frame send with error handling. */
    private static byte[] sendFrame(OutputStream out, byte[] frame,
                                    PortConnectionStatus portStatus, Protocol704Status status,
                                    String label) {
        if (out == null) {
            if (portStatus != null) {
                portStatus.setOutputErrorCount(portStatus.getOutputErrorCount() + 1);
                portStatus.setLastOutputError(label + " output stream is null");
            }
            return frame;
        }
        try {
            out.write(frame);
            out.flush();
            if (portStatus != null) {
                portStatus.setOutputFrameCount(portStatus.getOutputFrameCount() + 1);
                portStatus.setLastOutputTime(System.currentTimeMillis());
                portStatus.setLastOutputHex(bytesToHex(frame));
                portStatus.setLastOutputError(null);
            }
            log.debug("704 local-v1 {} frame sent, len={}", label, frame.length);
        } catch (IOException e) {
            if (portStatus != null) {
                portStatus.setOutputErrorCount(portStatus.getOutputErrorCount() + 1);
                portStatus.setLastOutputError(label + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            log.warn("704 local-v1 {} send failed: {}", label, e.getMessage());
        }
        return frame;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", bytes[i] & 0xFF));
        }
        return sb.toString();
    }
}
