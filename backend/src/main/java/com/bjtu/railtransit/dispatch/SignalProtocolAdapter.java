package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.domain.model.TrainState;
import org.springframework.stereotype.Component;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class SignalProtocolAdapter implements ProtocolAdapter {
    public static final int FRAME_HEADER_BYTES = 12;
    public static final int TRAIN_BYTES = 19;

    @Override public byte[] encodeTrainState(TrainState s) {
        ByteBuffer b = ByteBuffer.allocate(TRAIN_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        b.put(protocolTrainId(s.getTrainId()));
        b.putInt(speedMsToCms(s.getSpeedMps()));
        b.putInt(saturatingUnsignedInt(metersToCm(s.getPositionMeters())));
        b.put(directionToProtocol(s.getDirection()));
        b.putInt(saturatingUnsignedInt(Math.round(s.getLoadFactor() * 310_000)));
        b.putShort((short) Math.min(0xFFFF, speedMsToCms(s.getMaxSpeedLimit() / 3.6)));
        b.put((byte) (s.isEmergencyBraking() ? 1 : 0));
        b.put((byte) Math.max(0, s.isEmergencyBraking() ? 0 : s.getCarCount()));
        b.put((byte) Math.max(0, s.getCarCount()));
        return b.array();
    }

    /**
     * Teacher ICD 3.3.1: FF F0 + source(01 00 01 00) + destination(00 10 00 10)
     * + little-endian data length + fixed train records.
     *
     * The ICD contains an unresolved 18/19-byte conflict for faultSpeed. This
     * implementation uses the later per-train definition (uint16 cm/s), hence 19 bytes.
     */
    public byte[] encodeTrainStateFrame(List<TrainState> states) {
        int count = Math.min(40, states.size());
        int contentLength = count * TRAIN_BYTES;
        ByteBuffer frame = ByteBuffer.allocate(FRAME_HEADER_BYTES + contentLength)
                .order(ByteOrder.LITTLE_ENDIAN);
        frame.put((byte) 0xFF).put((byte) 0xF0);
        frame.put(new byte[]{0x01, 0x00, 0x01, 0x00});
        frame.put(new byte[]{0x00, 0x10, 0x00, 0x10});
        frame.putShort((short) (2 + contentLength));
        for (int i = 0; i < count; i++) frame.put(encodeTrainState(states.get(i)));
        return frame.array();
    }

    @Override public Object decodeCommand(byte[] raw) { return raw == null ? new byte[0] : raw.clone(); }

    private byte protocolTrainId(String trainId) {
        if (trainId == null) return 0;
        String digits = trainId.replaceAll("\\D+", "");
        if (!digits.isEmpty()) {
            try { return (byte) Math.min(255, Integer.parseInt(digits)); }
            catch (NumberFormatException ignored) { }
        }
        return (byte) (Math.abs(trainId.getBytes(StandardCharsets.UTF_8)[0]) & 0xFF);
    }

    private int saturatingUnsignedInt(long value) {
        return (int) Math.max(0, Math.min(0xFFFF_FFFFL, value));
    }
}
