package com.bjtu.railtransit.hil;

import com.bjtu.railtransit.vehicle.protocol704.PlcLampEncoder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.util.Locale;

/** Encodes only teacher-ICD fields whose offsets are explicit in the supplied document. */
public final class TeacherDeviceFrameCodec {
    /** Actual 9999 TCP payload. Its legacy header still contains totalLen=62. */
    public static final int SIGNAL_SCREEN_BYTES = 68;
    /** Actual 8888 TCP payload. The old document incorrectly says 572. */
    public static final int NETWORK_SCREEN_BYTES = 570;
    public static final int PLC_DOCUMENTED_BYTES = 26;
    public static final int PLC_CAPTURE_VARIANT_BYTES = 28;
    /** Vision 1.3: 4 + 1 + 77 + 1 + 29 + 4 + 2 + 1 + 1 + 4 + 2 + 1 + 1. */
    public static final int VISION_BASE_BYTES = 128;
    public static final int VISION_SIGNAL_COUNT = 77;
    public static final int VISION_SWITCH_COUNT = 29;

    private TeacherDeviceFrameCodec() {}

    /**
     * Builds a PLC output frame without claiming that a particular variant has
     * passed hardware acceptance. The supplied ICD says 26 bytes/dataLen=2;
     * a third-party capture contains a non-confirmed 28-byte variant.
     */
    public static byte[] plcOutput(HilVehicleSnapshot state, PlcOutputFrameFormat format) {
        ByteBuffer frame = header(format.totalBytes, format.dataBytes, false);
        PlcLampEncoder.LampInputs lamps = PlcLampEncoder.LampInputs.fromHilState(
                state.doorsClosed(), state.powerAvailable(), state.atoAvailable(), state.atoActive());
        frame.put(24, (byte) PlcLampEncoder.encodeByte24(lamps));
        frame.put(25, (byte) PlcLampEncoder.encodeByte25(lamps));
        if (format.includesSpeedWord) {
            // Candidate only: third-party software wrote this value; its PLC
            // acceptance and unit were not demonstrated by the supplied capture.
            frame.putShort(26, (short) clampU16(Math.round(state.speedMps() * 3.6)));
        }
        return frame.array();
    }

    public enum PlcOutputFrameFormat {
        DOCUMENTED_26(PLC_DOCUMENTED_BYTES, 2, false),
        CAPTURE_VARIANT_28(PLC_CAPTURE_VARIANT_BYTES, 4, true);

        private final int totalBytes;
        private final int dataBytes;
        private final boolean includesSpeedWord;

        PlcOutputFrameFormat(int totalBytes, int dataBytes, boolean includesSpeedWord) {
            this.totalBytes = totalBytes;
            this.dataBytes = dataBytes;
            this.includesSpeedWord = includesSpeedWord;
        }

        public static PlcOutputFrameFormat fromConfig(String value) {
            if (value == null) return DOCUMENTED_26;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "documented-26", "documented_26" -> DOCUMENTED_26;
                case "capture-variant-28", "capture_variant_28" -> CAPTURE_VARIANT_28;
                default -> throw new IllegalArgumentException(
                        "unsupported PLC output frame format '" + value
                                + "'; use documented-26 or capture-variant-28");
            };
        }
    }

    public static byte[] signalScreen(HilVehicleSnapshot state) {
        return signalScreen(state, protocolTrainNumber(state.trainId()));
    }

    public static byte[] signalScreen(HilVehicleSnapshot state, int screenTrainNumber) {
        ByteBuffer frame = screenHeader(SIGNAL_SCREEN_BYTES, 62, 42);
        frame.put(36, (byte) state.currentStationId());
        frame.put(37, (byte) state.nextStationId());
        frame.put(38, (byte) state.terminalStationId());
        // Every supplied 9999 capture carries 0/0/0 here. The old demo treated
        // these status bytes as active-high "alive" flags without hardware
        // evidence, which makes our frame differ from the accepted desk image.
        frame.put(39, (byte) 0);
        frame.put(40, (byte) 0);
        frame.put(41, (byte) 0);

        // Laboratory-confirmed 9999 layout. The 62-byte header value is not
        // the TCP payload size: fields through byte 67 are transmitted.
        frame.put(42, (byte) state.directionCode());
        frame.put(43, (byte) 0);
        frame.putFloat(44, (float) (state.speedMps() * 3.6));
        frame.putFloat(48, (float) state.accelerationMps2());
        frame.putShort(52, (short) 0); // traction cut-off feedback; not yet modelled
        frame.putShort(54, (short) clampU16(Math.round(state.speedLimitKmh())));
        frame.put(56, modeCode(state.mode()));
        frame.put(57, (byte) ("TRACTION".equals(state.handle()) ? 1 : 0));
        frame.put(58, (byte) ("BRAKE".equals(state.handle()) ? 1 : 0));
        frame.put(59, (byte) (state.emergencyBrake() ? 1 : 0));
        frame.put(60, (byte) 0);
        frame.put(61, (byte) (state.nextSignalState() & 0x0F));
        frame.putShort(62, (short) clampU16(screenTrainNumber));
        frame.putFloat(64, (float) Math.max(0, state.nextStationDistanceM()));
        return frame.array();
    }

    public static byte[] networkScreen(HilVehicleSnapshot state) {
        return networkScreen(state, protocolTrainNumber(state.trainId()));
    }

    public static byte[] networkScreen(HilVehicleSnapshot state, int screenTrainNumber) {
        ByteBuffer frame = screenHeader(NETWORK_SCREEN_BYTES);
        frame.put(36, (byte) state.currentStationId());
        frame.put(37, (byte) state.nextStationId());
        frame.put(38, (byte) state.terminalStationId());
        frame.put(39, (byte) (state.powerAvailable() ? 1 : 0));
        frame.putFloat(40, (float) (state.speedMps() * 3.6));
        frame.putFloat(44, (float) state.accelerationMps2());
        frame.putShort(48, (short) clampU16(Math.round(state.tractionForceKn())));
        frame.putShort(50, (short) clampU16(Math.round(state.lineVoltageV())));
        frame.putShort(52, (short) clampU16(Math.round(state.speedLimitKmh())));
        frame.put(54, handleCode(state));
        frame.put(55, networkModeCode(state.mode()));
        frame.putShort(56, (short) 110);
        frame.put(58, (byte) (state.directionCode() == 0 ? 1 : 2));
        frame.put(59, (byte) 0x01); // TC1 active, TC2 inactive

        for (int car = 0; car < 6; car++) {
            frame.putShort(144 + car * 2, (short) 800); // main reservoir kPa
            frame.putShort(156 + car * 2, (short) (state.emergencyBrake() ? 350 :
                    ("BRAKE".equals(state.handle()) ? 100 + state.handleLevelPercent() * 3 : 0)));
            frame.put(168 + car, (byte) 50); // AW load placeholder, documented default
            frame.put(174 + car, (byte) clampU8(Math.round(Math.abs(state.lineCurrentA()))));
        }
        frame.putShort(568, (short) clampU16(screenTrainNumber));
        return frame.array();
    }

    /**
     * Beijing Metro Line 9 Vision 1.3 TCMS2VIEW datagram.
     * The laboratory device uses the 128-byte 77-signal/29-switch definition.
     */
    public static byte[] vision(HilVehicleSnapshot state, int liveCounter,
                                byte[] signalStates, byte[] switchStates) {
        requireCount(signalStates, VISION_SIGNAL_COUNT, "vision signal states");
        requireCount(switchStates, VISION_SWITCH_COUNT, "vision switch states");

        ByteBuffer frame = ByteBuffer.allocate(VISION_BASE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        frame.putInt(liveCounter);
        frame.put((byte) VISION_SIGNAL_COUNT);
        frame.put(signalStates);
        frame.put((byte) VISION_SWITCH_COUNT);
        frame.put(switchStates);
        frame.putInt(clampI32(Math.round(Math.max(0, state.speedMps()) * 1000.0)));
        frame.putShort((short) 0); // departure display is reserved by Vision 1.3
        frame.put(visionRunState(state));
        frame.put((byte) clampI8(Math.round(state.accelerationMps2() / 1.1 * 100.0)));
        frame.putInt(clampI32(Math.round(Math.max(0, state.positionM()) * 1000.0)));
        frame.putShort((short) clampU16(state.visionEdgeId()));
        frame.put((byte) (state.visionDirection() < 0 ? -1 : 1));
        frame.put((byte) 0); // single-train laboratory workflow; frame stays exactly 128 B
        return frame.array();
    }

    private static ByteBuffer screenHeader(int size) {
        return screenHeader(size, size, size - 24);
    }

    private static ByteBuffer screenHeader(int size, int headerTotalLength, int headerDataLength) {
        ByteBuffer frame = header(headerTotalLength, headerDataLength, true, size);
        LocalDateTime now = LocalDateTime.now();
        frame.putShort(24, (short) now.getYear());
        frame.putShort(26, (short) now.getMonthValue());
        frame.putShort(28, (short) now.getDayOfMonth());
        frame.putShort(30, (short) now.getHour());
        frame.putShort(32, (short) now.getMinute());
        frame.putShort(34, (short) now.getSecond());
        return frame;
    }

    private static ByteBuffer header(int totalLength, int dataLength, boolean screenHeader) {
        return header(totalLength, dataLength, screenHeader, totalLength);
    }

    private static ByteBuffer header(int totalLength, int dataLength, boolean screenHeader, int allocationLength) {
        ByteBuffer frame = ByteBuffer.allocate(allocationLength).order(ByteOrder.LITTLE_ENDIAN);
        frame.putInt(0, 0xAA55AA55);
        frame.putShort(4, (short) totalLength);
        frame.putShort(6, (short) dataLength);
        if (screenHeader) {
            frame.putLong(8, System.currentTimeMillis());
            frame.putShort(16, (short) 0);
            frame.putShort(18, (short) 0);
            frame.putShort(20, (short) 1);
            frame.putShort(22, (short) 1);
        } else {
            LocalDateTime now = LocalDateTime.now();
            frame.putShort(8, (short) now.getYear());
            frame.putShort(10, (short) now.getMonthValue());
            frame.putShort(12, (short) now.getDayOfMonth());
            frame.putShort(14, (short) now.getHour());
            frame.putShort(16, (short) now.getMinute());
            frame.putShort(18, (short) now.getSecond());
        }
        return frame;
    }

    private static byte handleCode(HilVehicleSnapshot state) {
        if (state.emergencyBrake()) return 3;
        if ("TRACTION".equals(state.handle())) return 1;
        if ("BRAKE".equals(state.handle())) return 2;
        return 0;
    }

    private static byte networkModeCode(String mode) {
        return (byte) ("ATO".equalsIgnoreCase(mode) ? 0x11 : 0x00);
    }

    private static byte visionRunState(HilVehicleSnapshot state) {
        if ("TRACTION".equals(state.handle())) return 0x11;
        if ("BRAKE".equals(state.handle()) || state.emergencyBrake()) return 0x12;
        return 0x13;
    }

    private static byte modeCode(String mode) {
        if (mode == null) return 4; // RM
        return switch (mode.toUpperCase()) {
            case "DTO" -> 0;
            case "ATO" -> 1;
            case "AR" -> 2;
            case "SM", "MANUAL" -> 3;
            default -> 4;
        };
    }

    private static int protocolTrainNumber(String trainId) {
        if (trainId == null) return 1;
        String digits = trainId.replaceAll("\\D+", "");
        if (digits.isEmpty()) return 1;
        try { return Math.max(1, Math.min(0xFFFF, Integer.parseInt(digits))); }
        catch (NumberFormatException ignored) { return 1; }
    }

    private static int clampU16(long value) { return (int) Math.max(0, Math.min(0xFFFF, value)); }
    private static int clampU8(long value) { return (int) Math.max(0, Math.min(0xFF, value)); }
    private static int clampI8(long value) { return (int) Math.max(-128, Math.min(127, value)); }
    private static int clampI32(long value) { return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value)); }

    private static void requireCount(byte[] values, int expected, String name) {
        if (values == null || values.length != expected) {
            throw new IllegalArgumentException(name + " must contain exactly " + expected + " values");
        }
    }
}
