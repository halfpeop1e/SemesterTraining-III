package com.bjtu.railtransit.vehicle.protocol704;

/**
 * PLC 上位机→司机台指示灯 byte24/byte25 编码（协议 7.2）。
 * 与 {@code scripts/plc_lamp_write_demo.py} 的 LAMP_BITS 位定义一致。
 */
public final class PlcLampEncoder {

    private PlcLampEncoder() {}

    public record LampInputs(
            boolean highBreakerOn,
            boolean brakeReleaseBad,
            boolean doorOpenLamp,
            boolean doorsClosedOk,
            boolean networkFault,
            boolean arReady,
            boolean atoReady,
            boolean washMode,
            boolean atoActive,
            boolean arActive
    ) {
        public static LampInputs fromVehicleState(RealtimeVehicleState state) {
            RealtimeVehicleState s = state != null ? state : new RealtimeVehicleState();
            boolean doorsClosed = s.isDoorsClosed();
            String mode = s.getMode();
            boolean atoActive = mode != null && mode.equalsIgnoreCase("ATO");
            return new LampInputs(
                    true,
                    false,
                    !doorsClosed,
                    doorsClosed,
                    s.isNetworkFault(),
                    false,
                    s.isAtoReady(),
                    false,
                    atoActive,
                    false);
        }

        public static LampInputs fromHilState(boolean doorsClosed,
                                              boolean powerAvailable,
                                              boolean atoReady,
                                              boolean atoActive) {
            return new LampInputs(
                    true,
                    false,
                    !doorsClosed,
                    doorsClosed,
                    !powerAvailable,
                    false,
                    atoReady,
                    false,
                    atoActive,
                    false);
        }
    }

    public static int encodeByte24(LampInputs inputs) {
        int b24 = 0;
        if (inputs.highBreakerOn()) b24 |= 1 << 1;
        if (inputs.brakeReleaseBad()) b24 |= 1 << 2;
        if (inputs.doorOpenLamp()) b24 |= 1 << 4;
        if (inputs.doorsClosedOk()) b24 |= 1 << 5;
        if (inputs.networkFault()) b24 |= 1 << 6;
        if (inputs.arReady()) b24 |= 1 << 7;
        return b24;
    }

    public static int encodeByte25(LampInputs inputs) {
        int b25 = 0;
        if (inputs.atoReady()) b25 |= 1 << 0;
        if (inputs.washMode()) b25 |= 1 << 1;
        if (inputs.atoActive()) b25 |= 1 << 2;
        if (inputs.arActive()) b25 |= 1 << 3;
        return b25;
    }
}
