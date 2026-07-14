package com.bjtu.railtransit.hil;

import com.bjtu.railtransit.signal.domain.SignalAspect;
import com.bjtu.railtransit.signal.model.Signal;
import com.bjtu.railtransit.signal.model.Switch;
import com.bjtu.railtransit.signal.service.LineProfileLoader;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/** Maps the live signal model to the fixed Version 1.3 main-line packet order. */
@Service
public class VisionStateProvider {
    private static final List<Integer> SIGNAL_MODEL_IDS = List.of(
            145, 145, 115, 109, 109, 115, 8, 9, 8, 9, 10, 61, 13, 14, 15, 16, 17,
            10, 61, 62, 63, 64, 65, 17, 67, 18, 19, 20, 21, 22, 71, 72, 72, 71, 73,
            74, 75, 27, 75, 26, 100, 29, 30, 31, 32, 33, 34, 35, 36, 89, 90, 73, 75,
            76, 77, 101, 78, 30, 79, 36, 86, 87, 88, 89, 90, 42, 45, 46, 47, 43, 44,
            91, 92, 93, 94, 95, 48);

    private final LineProfileLoader lineLoader;

    static {
        if (SIGNAL_MODEL_IDS.size() != TeacherDeviceFrameCodec.VISION_SIGNAL_COUNT) {
            throw new IllegalStateException("Vision 1.3 signal order must contain exactly 77 entries");
        }
    }

    public VisionStateProvider(LineProfileLoader lineLoader) {
        this.lineLoader = lineLoader;
    }

    public byte[] signalStates() {
        byte[] states = new byte[TeacherDeviceFrameCodec.VISION_SIGNAL_COUNT];
        Arrays.fill(states, (byte) 0x01); // missing/unconfigured aspects remain red fail-safe
        List<Signal> signals = lineLoader.getLineProfile().getSignals();
        for (int i = 0; i < states.length; i++) {
            int id = SIGNAL_MODEL_IDS.get(i);
            Signal signal = signals.stream().filter(item -> item.getId() == id).findFirst().orElse(null);
            states[i] = visionAspect(signal == null ? null : signal.getAspect());
        }
        return states;
    }

    public byte[] switchStates() {
        byte[] states = new byte[TeacherDeviceFrameCodec.VISION_SWITCH_COUNT];
        Arrays.fill(states, (byte) 0x01);
        List<Switch> switches = lineLoader.getLineProfile().getSwitches();
        for (int i = 0; i < states.length; i++) {
            int id = i + 1;
            Switch value = switches.stream().filter(item -> String.valueOf(id).equals(item.getId())).findFirst().orElse(null);
            if (value != null && value.getState() != null) states[i] = (byte) value.getState().toProtocol();
        }
        return states;
    }

    private static byte visionAspect(SignalAspect aspect) {
        if (aspect == null) return 0x01;
        return switch (aspect) {
            case GREEN -> 0x02;
            case WHITE -> 0x04;
            case YELLOW -> 0x10;
            case BLUE -> 0x40;
            case RED_YELLOW -> 0x11;
            default -> 0x01;
        };
    }
}
