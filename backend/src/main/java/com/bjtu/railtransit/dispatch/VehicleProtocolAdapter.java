package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.domain.model.TrainCommand;
import com.bjtu.railtransit.domain.model.TrainState;
import org.springframework.stereotype.Component;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class VehicleProtocolAdapter implements ProtocolAdapter {
    public static final int TRAIN_SLOTS = 20;
    public static final int MODEL_OUTPUT_BYTES = TRAIN_SLOTS * 3 * Double.BYTES;
    public static final int PLATFORM_COMMAND_BYTES = TRAIN_SLOTS * 2 * Double.BYTES;

    public record VehicleModelState(int trainNumber, double accelerationMps2,
                                    double speedMps, double cumulativeDistanceMeters) {}
    public record VehicleControlSlot(int trainNumber, int command, double percentage) {}

    @Override public byte[] encodeTrainState(TrainState state) {
        byte[] id = state.getTrainId().getBytes(StandardCharsets.US_ASCII);
        ByteBuffer b = ByteBuffer.allocate(1 + id.length + 4 + 8 + 1 + 4 + 1 + 1)
            .order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) id.length).put(id);
        b.putInt(speedMsToCms(state.getSpeedMps()));
        b.putLong(metersToCm(state.getPositionMeters()));
        b.put(directionToProtocol(state.getDirection()));
        b.putInt((int) Math.round(state.getLoadFactor() * 1000));
        b.put((byte) (state.isEmergencyBraking() ? 1 : 0));
        b.put((byte) tractionBrakePercent(state));
        return b.array();
    }
    @Override public Object decodeCommand(byte[] raw) {
        TrainCommand c = new TrainCommand();
        c.setCommandType(raw != null && raw.length > 0 && raw[0] == 2 ? "SLOW" : "HOLD");
        c.setTargetValue(raw != null && raw.length > 1 ? Byte.toUnsignedInt(raw[1]) : 0);
        c.setSource("DISPATCH");
        return c;
    }
    private int tractionBrakePercent(TrainState s) {
        return Math.min(100, (int) Math.round(Math.abs(s.getAccelerationMps2()) / 1.2 * 100));
    }

    /** Teacher vehicle ICD: 20 fixed slots × (command, percentage), little-endian double. */
    public byte[] encodePlatformCommands(List<VehicleControlSlot> commands) {
        ByteBuffer frame = ByteBuffer.allocate(PLATFORM_COMMAND_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int slot = 1; slot <= TRAIN_SLOTS; slot++) {
            final int currentSlot = slot;
            VehicleControlSlot value = commands.stream()
                    .filter(c -> c.trainNumber() == currentSlot).findFirst()
                    .orElse(new VehicleControlSlot(slot, 0, 0));
            frame.putDouble(value.command());
            frame.putDouble(Math.max(0, Math.min(100, value.percentage())));
        }
        return frame.array();
    }

    /** Teacher vehicle ICD: 20 fixed slots × (acceleration, speed, cumulative distance). */
    public List<VehicleModelState> decodeModelOutput(byte[] raw) {
        if (raw == null || raw.length != MODEL_OUTPUT_BYTES) {
            throw new IllegalArgumentException("Vehicle model frame must be " + MODEL_OUTPUT_BYTES + " bytes");
        }
        ByteBuffer frame = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        List<VehicleModelState> values = new ArrayList<>(TRAIN_SLOTS);
        for (int slot = 1; slot <= TRAIN_SLOTS; slot++) {
            values.add(new VehicleModelState(slot, frame.getDouble(), frame.getDouble(), frame.getDouble()));
        }
        return values;
    }
}
