package com.bjtu.railtransit.hil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.util.Locale;

/** Encodes only teacher-ICD fields whose offsets are explicit in the supplied document. */
public final class TeacherDeviceFrameCodec {
    public static final int SIGNAL_SCREEN_BYTES = 66;
    public static final int NETWORK_SCREEN_BYTES = 572;
    public static final int PLC_DOCUMENTED_BYTES = 26;
    public static final int PLC_CAPTURE_VARIANT_BYTES = 28;

    private TeacherDeviceFrameCodec() {}

    /**
     * Builds a PLC output frame without claiming that a particular variant has
     * passed hardware acceptance. The supplied ICD says 26 bytes/dataLen=2;
     * a third-party capture contains a non-confirmed 28-byte variant.
     */
    public static byte[] plcOutput(HilVehicleSnapshot state, PlcOutputFrameFormat format) {
        ByteBuffer frame = header(format.totalBytes, format.dataBytes, false);
        int lamps = 0;
        if (state.doorsClosed()) lamps |= 1 << 5;
        if (!state.powerAvailable()) lamps |= 1 << 6;
        frame.put(24, (byte) lamps);
        int modes = 0;
        if (state.atoAvailable()) modes |= 1;
        if (state.atoActive()) modes |= 1 << 2;
        frame.put(25, (byte) modes);
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
        ByteBuffer frame = screenHeader(SIGNAL_SCREEN_BYTES);
        frame.put(36, (byte) state.currentStationId());
        frame.put(37, (byte) state.nextStationId());
        frame.put(38, (byte) state.terminalStationId());
        frame.put(39, (byte) 1); // CM communication alive
        frame.put(40, (byte) 1); // MM communication alive
        frame.put(41, (byte) 1); // CTC communication alive

        // ICD table 26 overlaps _nRunDir@42 with _nSpeed@42. The 66-byte field
        // list and on-site demo use speed@42, so direction is intentionally not
        // written until a capture resolves the contradiction.
        frame.putFloat(42, (float) (state.speedMps() * 3.6));
        frame.putFloat(46, (float) state.accelerationMps2());
        frame.putShort(50, (short) 0); // traction cut-off feedback; not yet modelled
        frame.putShort(52, (short) clampU16(Math.round(state.speedLimitKmh())));
        frame.put(54, modeCode(state.mode()));
        frame.put(55, (byte) ("TRACTION".equals(state.handle()) ? 1 : 0));
        frame.put(56, (byte) ("BRAKE".equals(state.handle()) ? 1 : 0));
        frame.put(57, (byte) (state.emergencyBrake() ? 1 : 0));
        frame.put(58, (byte) 0);
        frame.put(59, (byte) (state.nextSignalState() & 0x0F));
        frame.putShort(60, (short) protocolTrainNumber(state.trainId()));
        frame.putFloat(62, (float) Math.max(0, state.nextStationDistanceM()));
        return frame.array();
    }

    public static byte[] networkScreen(HilVehicleSnapshot state) {
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
        frame.putShort(570, (short) protocolTrainNumber(state.trainId()));
        return frame.array();
    }

    private static ByteBuffer screenHeader(int size) {
        ByteBuffer frame = header(size, size - 24, true);
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
        ByteBuffer frame = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN);
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
}
