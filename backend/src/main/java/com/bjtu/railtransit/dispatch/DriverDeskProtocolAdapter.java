package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.domain.model.TrainState;
import org.springframework.stereotype.Component;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Component
public class DriverDeskProtocolAdapter implements ProtocolAdapter {
    @Override public byte[] encodeTrainState(TrainState s) {
        return ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(speedMsToCms(s.getSpeedMps())).put(directionToProtocol(s.getDirection()))
            .put((byte) (s.isEmergencyBraking() ? 1 : 0)).array();
    }
    @Override public Object decodeCommand(byte[] raw) { return raw == null ? new byte[0] : raw.clone(); }
}
