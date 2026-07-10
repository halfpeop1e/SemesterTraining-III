package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.domain.model.TrainState;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TeacherProtocolAdapterTest {
    @Test
    void signalFrameUsesTeacherHeaderUnitsAndDirection() {
        SignalProtocolAdapter adapter = new SignalProtocolAdapter();
        TrainState train = new TrainState();
        train.setTrainId("T1");
        train.setDirection("UP");
        train.setPositionMeters(123.45);
        train.setSpeed(36);
        train.setMaxSpeedLimit(60);
        train.setCarCount(6);

        byte[] frame = adapter.encodeTrainStateFrame(List.of(train));
        assertEquals(SignalProtocolAdapter.FRAME_HEADER_BYTES + SignalProtocolAdapter.TRAIN_BYTES, frame.length);
        assertEquals(0xFF, Byte.toUnsignedInt(frame[0]));
        assertEquals(0xF0, Byte.toUnsignedInt(frame[1]));

        ByteBuffer content = ByteBuffer.wrap(frame, SignalProtocolAdapter.FRAME_HEADER_BYTES,
                SignalProtocolAdapter.TRAIN_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1, Byte.toUnsignedInt(content.get()));
        assertEquals(1000, content.getInt()); // 10 m/s = 1000 cm/s
        assertEquals(12_345, Integer.toUnsignedLong(content.getInt()));
        assertEquals(0x55, Byte.toUnsignedInt(content.get()));
    }

    @Test
    void vehicleFramesUseTwentyFixedLittleEndianSlots() {
        VehicleProtocolAdapter adapter = new VehicleProtocolAdapter();
        byte[] commands = adapter.encodePlatformCommands(List.of(
                new VehicleProtocolAdapter.VehicleControlSlot(1, 1, 75)));
        assertEquals(VehicleProtocolAdapter.PLATFORM_COMMAND_BYTES, commands.length);
        ByteBuffer commandBuffer = ByteBuffer.wrap(commands).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1, commandBuffer.getDouble());
        assertEquals(75, commandBuffer.getDouble());

        ByteBuffer model = ByteBuffer.allocate(VehicleProtocolAdapter.MODEL_OUTPUT_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        model.putDouble(0.5).putDouble(12).putDouble(100);
        while (model.hasRemaining()) model.putDouble(0);
        List<VehicleProtocolAdapter.VehicleModelState> decoded =
                adapter.decodeModelOutput(model.array());
        assertEquals(20, decoded.size());
        assertEquals(0.5, decoded.get(0).accelerationMps2());
        assertEquals(12, decoded.get(0).speedMps());
        assertEquals(100, decoded.get(0).cumulativeDistanceMeters());
    }
}
