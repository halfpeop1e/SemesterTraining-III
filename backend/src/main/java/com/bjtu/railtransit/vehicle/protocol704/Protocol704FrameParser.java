package com.bjtu.railtransit.vehicle.protocol704;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

public class Protocol704FrameParser {

    // The mode-select mappings below remain local conventions. ATO start itself
    // is defined by the teacher PLC table: byte34 bit7.
    private static final int BYTE34_MODE_MANUAL = 0x08; // mode_downgrade_confirm
    private static final int BYTE34_MODE_ATO = 0x04;    // mode_upgrade_confirm
    private static final int BYTE34_DEPART = 0x10;      // confirm_btn
    private static final int BYTE34_ATO_START = 0x80;   // ato_start_btn

    private static final int EXPECTED_FRAME_LENGTH = 46;
    private static final int FRAME_HEADER_MAGIC = 0xAA55AA55;

    public static Parsed704Frame parseFrame(byte[] data) {
        Parsed704Frame result = new Parsed704Frame();
        result.setFrameLength(data.length);
        Map<String, Object> fields = new LinkedHashMap<>();
        StringBuilder note = new StringBuilder();
        boolean allVerified = true;

        fields.put("raw_len", data.length);

        boolean headerValid = false;
        if (data.length >= 4) {
            int magic = readUInt32LE(data, 0);
            fields.put("frame_header", String.format("0x%08X", magic));
            headerValid = (magic == FRAME_HEADER_MAGIC);
            fields.put("header_valid", headerValid);
            if (!headerValid) {
                note.append("frame header mismatch (expected 0xAA55AA55); ");
                allVerified = false;
            }
        } else {
            note.append("frame too short for header; ");
            allVerified = false;
        }

        int expectedTotalLen = 0;
        int expectedDataLen = 0;
        if (data.length >= 8) {
            expectedTotalLen = readUInt16LE(data, 4);
            expectedDataLen = readUInt16LE(data, 6);
            fields.put("total_len_field", expectedTotalLen);
            fields.put("data_len_field", expectedDataLen);
            if (expectedTotalLen != EXPECTED_FRAME_LENGTH) {
                note.append(String.format("total_len_field=%d (expected %d); ", expectedTotalLen, EXPECTED_FRAME_LENGTH));
            }
            if (expectedDataLen != 22) {
                note.append(String.format("data_len_field=%d (expected 22); ", expectedDataLen));
            }
        }

        if (data.length >= EXPECTED_FRAME_LENGTH) {
            note.append(String.format("received %dB frame (matches PLC 46B spec); ", EXPECTED_FRAME_LENGTH));
        } else {
            note.append(String.format("unexpected frame length %dB (expected %dB PLC frame); ", data.length, EXPECTED_FRAME_LENGTH));
            allVerified = false;
        }

        if (data.length >= 20) {
            int year = readUInt16LE(data, 8);
            int month = readUInt16LE(data, 10);
            int day = readUInt16LE(data, 12);
            int hour = readUInt16LE(data, 14);
            int minute = readUInt16LE(data, 16);
            int second = readUInt16LE(data, 18);
            fields.put("plc_time", String.format("%04d-%02d-%02d %02d:%02d:%02d", year, month, day, hour, minute, second));
        }

        int verifyType = 0;
        int verifyCode = 0;
        if (data.length >= 24) {
            verifyType = readUInt16LE(data, 20);
            verifyCode = readUInt16LE(data, 22);
            fields.put("verify_type", verifyType);
            fields.put("verify_code", verifyCode);
        }

        MappedControlCommand mapped = new MappedControlCommand();
        mapped.setCommand("coast");
        mapped.setLevelPercent(0);
        mapped.setTargetDecel(0);
        mapped.setVerified(false);
        mapped.setTriggerByteOffset(-1);
        mapped.setTriggerByteValue(-1);
        mapped.setDirection("ZERO");

        if (data.length >= EXPECTED_FRAME_LENGTH) {
            parseDataArea(data, fields, mapped, note);
            if (!mapped.isVerified()) {
                allVerified = false;
            }
        } else {
            note.append("insufficient bytes for data area; ");
            allVerified = false;
        }

        fields.put("header_first8_hex", bytesToHex(data, 0, Math.min(8, data.length)));
        fields.put("data_area_hex", bytesToHex(data, 24, Math.min(22, Math.max(0, data.length - 24))));

        result.setFields(fields);
        result.setHasUnverifiedFields(!allVerified);
        result.setNote(note.toString().trim());
        result.setMappedCommand(mapped);
        return result;
    }

    private static void parseDataArea(byte[] data, Map<String, Object> fields, MappedControlCommand cmd, StringBuilder note) {
        int byte24 = data[24] & 0xFF;
        fields.put("lights_high_breaker_on", (byte24 & 0x02) != 0);
        fields.put("brake_release_bad", (byte24 & 0x04) != 0);
        fields.put("doors_closed_ok", (byte24 & 0x20) != 0);
        fields.put("network_fault", (byte24 & 0x40) != 0);
        fields.put("ar_mode_available", (byte24 & 0x80) != 0);

        int byte25 = data[25] & 0xFF;
        fields.put("ato_mode_available", (byte25 & 0x01) != 0);
        fields.put("wash_mode_entered", (byte25 & 0x02) != 0);
        fields.put("ato_mode_active", (byte25 & 0x04) != 0);
        fields.put("ar_mode_active", (byte25 & 0x08) != 0);

        int speedWord = readUInt16LE(data, 26);
        fields.put("plc_speed_raw_word", speedWord);
        fields.put("speed_unit_note", "PLC speed field at byte26-27; documented as an upper-computer speed echo, but the unit and PLC-output acceptance are not verified");
        note.append("plc_speed_raw=").append(speedWord).append(" (unit/echo behavior unverified); ");

        int byte28 = data[28] & 0xFF;
        boolean ebButtonLocked = (byte28 & 0x01) != 0;
        boolean busControl = (byte28 & 0x02) != 0;
        boolean forcedRelease = (byte28 & 0x04) != 0;
        boolean forcedPump = (byte28 & 0x08) != 0;
        boolean emergencyCmd = (byte28 & 0x10) != 0;
        boolean parkBrakeApply = (byte28 & 0x20) != 0;
        boolean parkBrakeRelease = (byte28 & 0x40) != 0;
        boolean whistle = (byte28 & 0x80) != 0;

        fields.put("eb_button_locked", ebButtonLocked);
        fields.put("bus_control_btn", busControl);
        fields.put("forced_release", forcedRelease);
        fields.put("forced_pump", forcedPump);
        fields.put("emergency_command_btn", emergencyCmd);
        fields.put("park_brake_apply", parkBrakeApply);
        fields.put("park_brake_release", parkBrakeRelease);
        fields.put("whistle", whistle);

        int byte29 = data[29] & 0xFF;
        fields.put("door_open_left", (byte29 & 0x01) != 0);
        fields.put("door_open_right", (byte29 & 0x02) != 0);
        fields.put("door_close_left", (byte29 & 0x04) != 0);
        fields.put("door_close_right", (byte29 & 0x08) != 0);

        int extLight = readUInt16LE(data, 30);
        int doorMode = readUInt16LE(data, 32);
        fields.put("ext_light_switch", extLight);
        fields.put("door_mode_switch", doorMode);

        int byte34 = data[34] & 0xFF;
        boolean highAccel = (byte34 & 0x01) != 0;
        boolean modeUpgradeConfirm = (byte34 & 0x04) != 0;
        boolean modeDowngradeConfirm = (byte34 & 0x08) != 0;
        boolean confirmBtn = (byte34 & 0x10) != 0;
        boolean arBtn = (byte34 & 0x20) != 0;
        boolean tractionAssistReset = (byte34 & 0x40) != 0;
        boolean atoStartBtn = (byte34 & 0x80) != 0;

        fields.put("high_accel_btn", highAccel);
        fields.put("mode_upgrade_confirm", modeUpgradeConfirm);
        fields.put("mode_downgrade_confirm", modeDowngradeConfirm);
        fields.put("confirm_btn", confirmBtn);
        fields.put("ar_btn", arBtn);
        fields.put("traction_assist_reset", tractionAssistReset);
        fields.put("ato_start_btn", atoStartBtn);

        int byte35 = data[35] & 0xFF;
        boolean washSwitch = (byte35 & 0x01) != 0;
        boolean keySwitch = (byte35 & 0x02) != 0;
        boolean vigilance = (byte35 & 0x04) != 0;

        fields.put("wash_mode_switch", washSwitch);
        fields.put("key_switch_on", keySwitch);
        fields.put("vigilance_btn", vigilance);

        int directionHandle = readUInt16LE(data, 36);
        int masterHandle = readUInt16LE(data, 38);
        int tractionLevel = readUInt16LE(data, 40);
        int brakeLevel = readUInt16LE(data, 42);

        fields.put("direction_handle", directionHandle);
        fields.put("direction_desc", directionHandle == 0 ? "0位" : directionHandle == 1 ? "向前" : directionHandle == 2 ? "向后" : "UNKNOWN");
        fields.put("master_handle", masterHandle);
        fields.put("traction_level_percent_raw", tractionLevel);
        fields.put("brake_level_percent_raw", brakeLevel);

        cmd.setDirectionHandle(directionHandle);
        cmd.setMasterHandle(masterHandle);
        cmd.setTractionLevelRaw(tractionLevel);
        cmd.setBrakeLevelRaw(brakeLevel);
        String direction = switch (directionHandle) {
            case 1 -> "FORWARD";
            case 0 -> "ZERO";
            case 2 -> "REVERSE";
            default -> "UNKNOWN";
        };
        cmd.setDirection(direction);
        fields.put("direction_semantic", direction);

        // Mode/departure commands have priority over the ordinary master handle.
        if ((byte34 & BYTE34_MODE_MANUAL) != 0) {
            cmd.setCommand("SET_MANUAL");
            cmd.setNote("LOCAL_V1_UNCONFIRMED: byte34 bit3 -> SET_MANUAL");
            fields.put("control_mode_request", "MANUAL");
        } else if ((byte34 & BYTE34_ATO_START) != 0) {
            cmd.setCommand("ATO_START");
            cmd.setNote("DOC_DEFINED: byte34 bit7 -> ATO_START; the two green buttons are interlocked by the desk hardware before this boolean is sent");
            fields.put("control_mode_request", "ATO_START");
        } else if ((byte34 & BYTE34_MODE_ATO) != 0) {
            cmd.setCommand("RESUME_ATO");
            cmd.setNote("LOCAL_V1_UNCONFIRMED: byte34 bit2 -> RESUME_ATO");
            fields.put("control_mode_request", "ATO");
        } else if ((byte34 & BYTE34_DEPART) != 0) {
            cmd.setCommand("DEPART_CONFIRM");
            cmd.setNote("LOCAL_V1_UNCONFIRMED: byte34 bit4 -> DEPART_CONFIRM");
            fields.put("departure_confirm", true);
        } else if (direction.equals("UNKNOWN")) {
            cmd.setCommand("UNSUPPORTED");
            cmd.setNote("LOCAL_V1_UNCONFIRMED: unknown direction handle=" + directionHandle);
            note.append("UNKNOWN direction handle=").append(directionHandle).append("; ");
        }

        if ("UNSUPPORTED".equals(cmd.getCommand()) || "SET_MANUAL".equals(cmd.getCommand())
                || "RESUME_ATO".equals(cmd.getCommand()) || "ATO_START".equals(cmd.getCommand())
                || "DEPART_CONFIRM".equals(cmd.getCommand())) {
            // already mapped above
        } else if (ebButtonLocked || masterHandle == 0x0004) {
            cmd.setCommand("emergency_brake");
            cmd.setLevelPercent(100);
            cmd.setTargetDecel(2.5);
            cmd.setTriggerByteOffset(28);
            cmd.setTriggerByteValue(byte28);
            cmd.setNote("DOC_DEFINED: emergency_brake from EB button (byte28 bit0) OR master_handle=0x0004 (fast brake); FIELD POSITIONS from 704 PLC protocol doc, value ranges require on-site verification");
            note.append("EMERGENCY_BRAKE detected; ");
        } else if (masterHandle == 0x0001) {
            cmd.setCommand("traction");
            int level = Math.max(0, Math.min(100, tractionLevel));
            cmd.setLevelPercent(level);
            cmd.setTargetDecel(0);
            cmd.setTriggerByteOffset(38);
            cmd.setTriggerByteValue(masterHandle);
            cmd.setNote("DOC_DEFINED: traction from master_handle=0x0001; traction_level raw=" + tractionLevel + " mapped to 0-100%; FIELD POSITIONS from 704 PLC protocol doc, value ranges require on-site verification");
            note.append("TRACTION detected; level=").append(level).append("; ");
        } else if (masterHandle == 0x0002) {
            cmd.setCommand("brake");
            int level = normalizeBrakeLevel(brakeLevel);
            cmd.setLevelPercent(level);
            cmd.setTargetDecel(mapBrakeLevelToDecel(level));
            cmd.setTriggerByteOffset(38);
            cmd.setTriggerByteValue(masterHandle);
            cmd.setNote("DOC_DEFINED: brake from master_handle=0x0002; brake_level raw=" + brakeLevel
                    + " normalized to " + level + "% with targetDecel; field position is documented,"
                    + " while the 0x9Cxx/0x9Dxx laboratory encoding is derived from the 2026-07-13 capture");
            note.append("BRAKE detected; level=").append(level).append("; ");
        } else if (masterHandle == 0x0000) {
            cmd.setCommand("coast");
            cmd.setLevelPercent(0);
            cmd.setTargetDecel(0);
            cmd.setTriggerByteOffset(38);
            cmd.setTriggerByteValue(masterHandle);
            cmd.setNote("DOC_DEFINED: coast/zero from master_handle=0x0000; FIELD POSITIONS from 704 PLC protocol doc, value ranges require on-site verification");
            note.append("COAST/zero detected; ");
        } else {
            cmd.setCommand("UNSUPPORTED");
            cmd.setLevelPercent(0);
            cmd.setTargetDecel(0);
            cmd.setNote("LOCAL_V1_UNCONFIRMED: unknown master_handle value=" + masterHandle);
            note.append("UNKNOWN master_handle=").append(masterHandle).append("; rejected; ");
        }

        fields.put("mapped_command", cmd.getCommand());
        fields.put("mapping_verified", false);
        fields.put("mapping_note", cmd.getNote());
    }

    private static double mapBrakeLevelToDecel(int levelPercent) {
        double maxServiceDecel = 1.2;
        return maxServiceDecel * (levelPercent / 100.0);
    }

    /**
     * The teacher document calls this a 0-100 percent WORD, but the live desk
     * emits a signed 16-bit value around -25,345 (wire 0x9Cxx/0x9Dxx) for
     * service brake.  Its low byte follows the handle setting, so preserve the
     * documented 0-100 form and use the observed low-byte form only for that
     * known range.  Any other out-of-range value stays invalid and is rejected
     * by the control bridge.
     */
    private static int normalizeBrakeLevel(int raw) {
        if (raw >= 0 && raw <= 100) return raw;
        if (raw >= 0x9C00 && raw <= 0x9DFF) {
            return Math.min(100, raw & 0xFF);
        }
        return raw;
    }

    private static int readUInt16LE(byte[] data, int offset) {
        if (offset + 1 >= data.length) return 0;
        ByteBuffer bb = ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN);
        return bb.getShort() & 0xFFFF;
    }

    private static int readUInt32LE(byte[] data, int offset) {
        if (offset + 3 >= data.length) return 0;
        ByteBuffer bb = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN);
        return bb.getInt();
    }

    static String bytesToHex(byte[] bytes) {
        return bytesToHex(bytes, 0, bytes.length);
    }

    private static String bytesToHex(byte[] bytes, int offset, int length) {
        if (offset < 0) offset = 0;
        if (offset >= bytes.length) return "";
        int end = Math.min(offset + length, bytes.length);
        StringBuilder sb = new StringBuilder((end - offset) * 3);
        for (int i = offset; i < end; i++) {
            if (i > offset) sb.append(' ');
            sb.append(String.format("%02X", bytes[i] & 0xFF));
        }
        return sb.toString();
    }
}
