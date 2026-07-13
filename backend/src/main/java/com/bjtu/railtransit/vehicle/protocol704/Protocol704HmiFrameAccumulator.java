package com.bjtu.railtransit.vehicle.protocol704;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reassembles the fixed 26-byte local-v1 HMI input stream.
 *
 * <p>The TCP read boundary is not a protocol boundary: one read may contain a
 * partial frame, several frames, or leading garbage.  Only a frame with the
 * exact advertised 26-byte length is emitted.  Invalid advertised lengths are
 * discarded while retaining enough bytes to recover a following frame.</p>
 */
public final class Protocol704HmiFrameAccumulator {

    private static final byte[] HEADER = {0x55, (byte) 0xAA, 0x55, (byte) 0xAA};
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private Protocol704InputState lastInputState = Protocol704InputState.CONNECTED_NO_BYTES;
    private String inputDiagnostic = "TCP connected; no HMI input bytes received";
    private String lastInputHeader;
    private Integer lastInputTotalLength;
    private Integer lastInputDataLength;
    private long emittedFrameCount;

    public synchronized List<byte[]> append(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return List.of();
        pending.write(bytes, 0, bytes.length);
        List<byte[]> frames = new ArrayList<>();
        Protocol704InputState diagnosticState = null;
        while (true) {
            byte[] buffer = pending.toByteArray();
            if (buffer.length == 0) break;
            int headerIndex = indexOfHeader(buffer);
            if (headerIndex < 0) {
                retainHeaderPrefix(buffer);
                if (diagnosticState == null
                        || diagnosticState == Protocol704InputState.PARTIAL_FRAME
                        || diagnosticState == Protocol704InputState.FIRST_FRAME_RECEIVED
                        || diagnosticState == Protocol704InputState.FRAME_RECEIVED) {
                    lastInputHeader = hexPrefix(bytes, 4);
                    diagnosticState = buffer.length > 0
                            && matchesSuffix(buffer, Math.min(buffer.length, HEADER.length - 1))
                            ? Protocol704InputState.PARTIAL_FRAME
                            : Protocol704InputState.HEADER_MISMATCH;
                    inputDiagnostic = diagnosticState == Protocol704InputState.PARTIAL_FRAME
                            ? "received partial HMI frame header; waiting for complete frame"
                            : "HMI frame header mismatch; expected 55 AA 55 AA";
                }
                break;
            }
            if (headerIndex > 0) {
                discard(headerIndex);
                if (diagnosticState == null
                        || diagnosticState == Protocol704InputState.PARTIAL_FRAME
                        || diagnosticState == Protocol704InputState.FIRST_FRAME_RECEIVED
                        || diagnosticState == Protocol704InputState.FRAME_RECEIVED) {
                    diagnosticState = Protocol704InputState.HEADER_MISMATCH;
                    inputDiagnostic = "discarded bytes before HMI frame header; expected 55 AA 55 AA";
                }
                continue;
            }
            if (buffer.length < 8) {
                diagnosticState = Protocol704InputState.PARTIAL_FRAME;
                inputDiagnostic = "received partial HMI frame; waiting for length fields";
                break;
            }

            int totalLength = ByteBuffer.wrap(buffer)
                    .order(ByteOrder.LITTLE_ENDIAN).getShort(4) & 0xFFFF;
            int dataLength = ByteBuffer.wrap(buffer)
                    .order(ByteOrder.LITTLE_ENDIAN).getShort(6) & 0xFFFF;
            lastInputHeader = hexPrefix(buffer, 4);
            lastInputTotalLength = totalLength;
            lastInputDataLength = dataLength;
            if (totalLength != Protocol704HmiInputParser.FRAME_LENGTH) {
                diagnosticState = Protocol704InputState.TOTAL_LENGTH_MISMATCH;
                inputDiagnostic = "HMI total length mismatch: received " + totalLength
                        + ", expected " + Protocol704HmiInputParser.FRAME_LENGTH;
                // Drop one byte, then rescan. This also recovers when an
                // invalid 26B candidate is immediately followed by a frame.
                discard(1);
                continue;
            }
            if (buffer.length < totalLength) {
                diagnosticState = Protocol704InputState.PARTIAL_FRAME;
                inputDiagnostic = "received " + buffer.length
                        + "B of a complete HMI frame; waiting for " + totalLength + "B";
                break;
            }

            byte[] frame = Arrays.copyOf(buffer, totalLength);
            if (dataLength != Protocol704HmiInputParser.DATA_LENGTH) {
                diagnosticState = Protocol704InputState.DATA_LENGTH_MISMATCH;
                inputDiagnostic = "HMI data length mismatch: received " + dataLength
                        + ", expected " + Protocol704HmiInputParser.DATA_LENGTH;
                discard(totalLength);
                continue;
            }
            discard(totalLength);
            if (Protocol704HmiInputParser.isStructurallyValid(frame)) {
                frames.add(frame);
                emittedFrameCount++;
                diagnosticState = emittedFrameCount == 1
                        ? Protocol704InputState.FIRST_FRAME_RECEIVED
                        : Protocol704InputState.FRAME_RECEIVED;
                inputDiagnostic = diagnosticState == Protocol704InputState.FIRST_FRAME_RECEIVED
                        ? "successfully received and parsed first HMI frame"
                        : "successfully received and parsed HMI frame";
            } else {
                diagnosticState = Protocol704InputState.STRUCTURE_INVALID;
                inputDiagnostic = "complete 26B HMI frame failed structural validation";
            }
        }
        if (diagnosticState != null) lastInputState = diagnosticState;
        return frames;
    }

    public synchronized void reset() {
        pending.reset();
        lastInputState = Protocol704InputState.CONNECTED_NO_BYTES;
        inputDiagnostic = "TCP connected; no HMI input bytes received";
        lastInputHeader = null;
        lastInputTotalLength = null;
        lastInputDataLength = null;
        emittedFrameCount = 0;
    }

    public synchronized int pendingBytes() {
        return pending.size();
    }

    public synchronized Protocol704InputState getLastInputState() { return lastInputState; }
    public synchronized String getInputDiagnostic() { return inputDiagnostic; }
    public synchronized String getLastInputHeader() { return lastInputHeader; }
    public synchronized Integer getLastInputTotalLength() { return lastInputTotalLength; }
    public synchronized Integer getLastInputDataLength() { return lastInputDataLength; }

    private void discard(int count) {
        byte[] current = pending.toByteArray();
        pending.reset();
        if (count < current.length) pending.write(current, count, current.length - count);
    }

    private void retainHeaderPrefix(byte[] buffer) {
        int keep = 0;
        for (int len = Math.min(HEADER.length - 1, buffer.length); len > 0; len--) {
            if (matchesSuffix(buffer, len)) {
                keep = len;
                break;
            }
        }
        pending.reset();
        if (keep > 0) pending.write(buffer, buffer.length - keep, keep);
    }

    private static boolean matchesSuffix(byte[] buffer, int length) {
        int start = buffer.length - length;
        for (int i = 0; i < length; i++) {
            if (buffer[start + i] != HEADER[i]) return false;
        }
        return true;
    }

    private static int indexOfHeader(byte[] buffer) {
        outer:
        for (int i = 0; i <= buffer.length - HEADER.length; i++) {
            for (int j = 0; j < HEADER.length; j++) {
                if (buffer[i + j] != HEADER[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static String hexPrefix(byte[] bytes, int maxLength) {
        if (bytes == null || bytes.length == 0) return "";
        int length = Math.min(bytes.length, maxLength);
        StringBuilder result = new StringBuilder(length * 3);
        for (int i = 0; i < length; i++) {
            if (i > 0) result.append(' ');
            result.append(String.format("%02X", bytes[i] & 0xFF));
        }
        return result.toString();
    }
}
