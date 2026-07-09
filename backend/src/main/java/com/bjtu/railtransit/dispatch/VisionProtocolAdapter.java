package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.domain.model.TrainState;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;

@Component
public class VisionProtocolAdapter implements ProtocolAdapter {
    @Override public byte[] encodeTrainState(TrainState s) {
        return String.format("{\"trainId\":\"%s\",\"positionCm\":%d,\"speedCms\":%d,\"direction\":%d}",
            s.getTrainId(), metersToCm(s.getPositionMeters()), speedMsToCms(s.getSpeedMps()),
            Byte.toUnsignedInt(directionToProtocol(s.getDirection()))).getBytes(StandardCharsets.UTF_8);
    }
    @Override public Object decodeCommand(byte[] raw) {
        return raw == null ? "" : new String(raw, StandardCharsets.UTF_8);
    }
}
