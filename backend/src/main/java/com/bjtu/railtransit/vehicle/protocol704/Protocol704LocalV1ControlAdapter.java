package com.bjtu.railtransit.vehicle.protocol704;

import com.bjtu.railtransit.vehicle.dto.ControlCommand;

import java.util.Optional;

/**
 * protocol704-adapter-local-v1：仅把已解析的本地控制意图转换为车辆控制 DTO。
 * 它不验证 PLC 帧、不建立会话或 ACK，也不调用车辆仿真。
 */
final class Protocol704LocalV1ControlAdapter {

    private Protocol704LocalV1ControlAdapter() {
    }

    static Optional<ControlCommand> toLocalV1(MappedControlCommand mapped) {
        if (mapped == null || mapped.getCommand() == null) {
            return Optional.empty();
        }
        return switch (mapped.getCommand()) {
            case "traction" -> withDirection(new ControlCommand(
                    "traction", 0.0, mapped.getLevelPercent()), mapped.getDirection());
            case "brake" -> withDirection(new ControlCommand(
                    "brake", mapped.getTargetDecel(), mapped.getLevelPercent()), mapped.getDirection());
            case "emergency_brake" -> withDirection(new ControlCommand(
                    "emergency_brake", mapped.getTargetDecel(), mapped.getLevelPercent()), mapped.getDirection());
            case "coast" -> withDirection(new ControlCommand("coast", 0.0, 0.0), mapped.getDirection());
            case "SET_MANUAL", "RESUME_ATO", "ATO_START", "DEPART_CONFIRM" ->
                    Optional.of(new ControlCommand(mapped.getCommand(), 0.0, 0.0));
            default -> Optional.empty();
        };
    }

    private static Optional<ControlCommand> withDirection(ControlCommand command, String direction) {
        if (direction != null && !direction.equals("FORWARD") && !direction.equals("ZERO")
                && !direction.equals("REVERSE")) return Optional.empty();
        command.setDirection(direction);
        return Optional.of(command);
    }
}
