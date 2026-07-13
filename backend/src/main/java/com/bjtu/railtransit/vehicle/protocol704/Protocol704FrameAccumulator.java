package com.bjtu.railtransit.vehicle.protocol704;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * local-v1 TCP stream reassembly for the only accepted 46-byte PLC frame.
 * This class owns transport framing only; validation and business handling stay downstream.
 */
final class Protocol704FrameAccumulator {

    static final int FRAME_LENGTH = 46;
    private static final byte[] MAGIC = {(byte) 0x55, (byte) 0xAA, (byte) 0x55, (byte) 0xAA};
    private byte[] pending = new byte[0];
    private Protocol704InputState lastInputState = Protocol704InputState.CONNECTED_NO_BYTES;
    private String inputDiagnostic = "TCP connected; no PLC input bytes received";
    private String lastInputHeader;
    private Integer lastInputTotalLength;
    private Integer lastInputDataLength;
    private long emittedFrameCount;

    List<byte[]> append(byte[] incoming) {
        if (incoming == null || incoming.length == 0) {
            return List.of();
        }
        byte[] combined = Arrays.copyOf(pending, pending.length + incoming.length);
        System.arraycopy(incoming, 0, combined, pending.length, incoming.length);
        List<byte[]> frames = new ArrayList<>();
        Protocol704InputState diagnosticState = null;
        int offset = 0;
        while (combined.length - offset >= MAGIC.length) {
            int header = findHeader(combined, offset);
            if (header < 0) {
                int keep = matchingHeaderSuffixLength(combined, offset);
                offset = combined.length - keep;
                if (diagnosticState == null) {
                    boolean partialHeader = keep > 0;
                    diagnosticState = partialHeader
                            ? Protocol704InputState.PARTIAL_FRAME
                            : Protocol704InputState.HEADER_MISMATCH;
                    inputDiagnostic = partialHeader
                            ? "received partial PLC frame header; waiting for complete frame"
                            : "PLC frame header mismatch; expected 55 AA 55 AA";
                    lastInputHeader = hexPrefix(combined, offset, Math.min(4, combined.length - offset));
                }
                break;
            }
            if (combined.length - header < FRAME_LENGTH) {
                offset = header;
                diagnosticState = Protocol704InputState.PARTIAL_FRAME;
                inputDiagnostic = "received " + (combined.length - header)
                        + "B of a complete PLC frame; waiting for " + FRAME_LENGTH + "B";
                lastInputHeader = hexPrefix(combined, header, Math.min(4, combined.length - header));
                break;
            }
            int totalLength = unsignedLe16(combined, header + 4);
            int dataLength = unsignedLe16(combined, header + 6);
            lastInputHeader = hexPrefix(combined, header, 4);
            lastInputTotalLength = totalLength;
            lastInputDataLength = dataLength;
            if (totalLength != FRAME_LENGTH || dataLength != 22) {
                diagnosticState = totalLength != FRAME_LENGTH
                        ? Protocol704InputState.TOTAL_LENGTH_MISMATCH
                        : Protocol704InputState.DATA_LENGTH_MISMATCH;
                inputDiagnostic = totalLength != FRAME_LENGTH
                        ? "PLC total length mismatch: received " + totalLength + ", expected " + FRAME_LENGTH
                        : "PLC data length mismatch: received " + dataLength + ", expected 22";
                offset = header + 1;
                continue;
            }
            frames.add(Arrays.copyOfRange(combined, header, header + FRAME_LENGTH));
            emittedFrameCount++;
            diagnosticState = emittedFrameCount == 1
                    ? Protocol704InputState.FIRST_FRAME_RECEIVED
                    : Protocol704InputState.FRAME_RECEIVED;
            inputDiagnostic = diagnosticState == Protocol704InputState.FIRST_FRAME_RECEIVED
                    ? "successfully received and parsed first PLC frame"
                    : "successfully received and parsed PLC frame";
            offset = header + FRAME_LENGTH;
        }
        pending = Arrays.copyOfRange(combined, offset, combined.length);
        if (diagnosticState == null && pending.length > 0) {
            boolean partialHeader = pending.length <= MAGIC.length - 1
                    && matchesHeaderPrefix(pending);
            lastInputState = partialHeader
                    ? Protocol704InputState.PARTIAL_FRAME
                    : Protocol704InputState.HEADER_MISMATCH;
            inputDiagnostic = partialHeader
                    ? "received partial PLC frame header; waiting for complete frame"
                    : "PLC frame header mismatch; expected 55 AA 55 AA";
            lastInputHeader = hexPrefix(pending, 0, Math.min(4, pending.length));
        }
        if (diagnosticState != null) lastInputState = diagnosticState;
        return frames;
    }

    void reset() {
        pending = new byte[0];
        lastInputState = Protocol704InputState.CONNECTED_NO_BYTES;
        inputDiagnostic = "TCP connected; no PLC input bytes received";
        lastInputHeader = null;
        lastInputTotalLength = null;
        lastInputDataLength = null;
        emittedFrameCount = 0;
    }

    Protocol704InputState getLastInputState() { return lastInputState; }
    String getInputDiagnostic() { return inputDiagnostic; }
    String getLastInputHeader() { return lastInputHeader; }
    Integer getLastInputTotalLength() { return lastInputTotalLength; }
    Integer getLastInputDataLength() { return lastInputDataLength; }
    int pendingBytes() { return pending.length; }

    private static int findHeader(byte[] data, int start) {
        for (int i = start; i <= data.length - MAGIC.length; i++) {
            if (data[i] == MAGIC[0] && data[i + 1] == MAGIC[1]
                    && data[i + 2] == MAGIC[2] && data[i + 3] == MAGIC[3]) {
                return i;
            }
        }
        return -1;
    }

    private static int unsignedLe16(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static String hexPrefix(byte[] data, int offset, int length) {
        if (data == null || offset < 0 || offset >= data.length || length <= 0) return "";
        int end = Math.min(data.length, offset + length);
        StringBuilder result = new StringBuilder((end - offset) * 3);
        for (int i = offset; i < end; i++) {
            if (i > offset) result.append(' ');
            result.append(String.format("%02X", data[i] & 0xFF));
        }
        return result.toString();
    }

    private static boolean matchesHeaderPrefix(byte[] data) {
        for (int i = 0; i < data.length; i++) {
            if (data[i] != MAGIC[i]) return false;
        }
        return true;
    }

    private static int matchingHeaderSuffixLength(byte[] data, int start) {
        int available = data.length - start;
        for (int length = Math.min(MAGIC.length - 1, available); length > 0; length--) {
            int suffixStart = data.length - length;
            boolean matches = true;
            for (int i = 0; i < length; i++) {
                if (data[suffixStart + i] != MAGIC[i]) {
                    matches = false;
                    break;
                }
            }
            if (matches) return length;
        }
        return 0;
    }
}
