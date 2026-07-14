package com.bjtu.railtransit.signal.service;

import com.bjtu.railtransit.dispatch.CommandBus;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.bjtu.railtransit.vehicle.protocol704.Protocol704VehicleControlBridge;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 信号系统 → PLC 司机台 ATO 指示灯闭环。
 *
 * <p>流程：信号/调度下发 DEPART → 同步到 704 bridge → 上位机写 byte25 bit0
 *（具备 ATO）→ 司机台 ATO 按钮闪烁；司机长按两个绿色按钮后 PLC 上报
 * {@code ATO_START}，bridge 进入 ATO 模式并写 byte25 bit2（激活 ATO）。</p>
 */
@Service
public class SignalPlcDepartureService {

    private static final double STOPPED_SPEED_MPS = 0.1;

    private final CommandBus commandBus;
    private final MovementAuthorityRegistry maRegistry;
    private final Protocol704VehicleControlBridge vehicleBridge;

    public SignalPlcDepartureService(CommandBus commandBus,
                                     MovementAuthorityRegistry maRegistry,
                                     @Lazy Protocol704VehicleControlBridge vehicleBridge) {
        this.commandBus = commandBus;
        this.maRegistry = maRegistry;
        this.vehicleBridge = vehicleBridge;
    }

    /** ATS/信号下发 DEPART 后，同步到实体司机台控制 bridge，供 PLC 输出写 ato_ready。 */
    public void onDepartureAuthorized(String trainId) {
        if (trainId == null || trainId.isBlank()) return;
        if (!vehicleBridge.isLaboratoryControlEnabled(trainId)) return;
        vehicleBridge.syncDepartureAuth(trainId, true);
    }

    /** ATP has priority over every manual and automatic control source. */
    public void onAtpEmergencyBrake(String trainId) {
        if (trainId == null || trainId.isBlank()) return;
        vehicleBridge.applyAtpEmergencyBrake(trainId);
    }

    /** 停站开门后撤销发车授权时，熄灭 ATO 就绪灯。 */
    public void onDepartureRevoked(String trainId) {
        if (trainId == null || trainId.isBlank()) return;
        if (!vehicleBridge.isLaboratoryControlEnabled(trainId)) return;
        vehicleBridge.syncDepartureAuth(trainId, false);
    }

    /** Read-only laboratory state for the signal-cycle station-leg adapter. */
    public Protocol704VehicleControlBridge.ControlStateSnapshot controlSnapshot(String trainId) {
        return vehicleBridge.snapshot(trainId);
    }

    /** Laboratory trains eligible for backend-owned automatic station-leg routing. */
    public List<String> activeLaboratoryTrainIds() {
        return vehicleBridge.activeLaboratoryTrainIds();
    }

    /**
     * A laboratory ATO leg is planned to the bridge's next platform target.
     * An EoA merely ahead of the train is not enough: starting ATO with that
     * authority would let the generated trajectory pass the signal-system EoA.
     */
    public boolean authorityCoversNextAtoStation(
            Protocol704VehicleControlBridge.ControlStateSnapshot control,
            MovingAuthority ma) {
        if (control == null || ma == null || control.state() == null) return false;
        double target = control.nextTargetAbsolutePositionM();
        return Double.isFinite(target)
                && target > control.absolutePositionM()
                && ma.getEndOfAuthorityM() + 0.001 >= target;
    }

    /** Diagnostic reason for the physical ATO-ready interlock. */
    public String atoReadinessBlockingReason(String trainId) {
        Protocol704VehicleControlBridge.ControlStateSnapshot control = vehicleBridge.snapshot(trainId);
        if (control == null || control.state() == null) return "CONTROL_CONTEXT_UNAVAILABLE";
        if (control.emergencyLatched()) return "ATP_EMERGENCY_BRAKE";
        // The ready lamp must describe the same cab precondition that ATO_START
        // enforces. Do not advertise a start before the key preparation is done.
        if (!control.keySwitchOn()) return "KEY_SWITCH_OFF";
        if (!control.keyPreparationComplete()) return "KEY_CYCLE_INCOMPLETE";
        if (control.directionHandle() != 1) return "DIRECTION_NOT_FORWARD";
        if (control.masterHandle() != 0) return "MASTER_HANDLE_NOT_ZERO";
        if (!control.doorsClosed()) return "DOORS_OPEN";
        if (control.atoRunning()) return "ATO_RUNNING";
        if (control.state().getVelocity() > STOPPED_SPEED_MPS) return "TRAIN_NOT_STOPPED";
        if (control.doorCycleRequired()) return "DOOR_CYCLE_REQUIRED";
        if (!control.departureAuthorized()) return "SIGNAL_DEPARTURE_NOT_AUTHORIZED";
        MovingAuthority ma = maRegistry.get(trainId);
        if (ma == null) return "MOVEMENT_AUTHORITY_UNAVAILABLE";
        if (!authorityCoversNextAtoStation(control, ma)) return "MA_DOES_NOT_REACH_NEXT_STATION";
        return "NONE";
    }

    /**
     * 是否应向 PLC 写「具备 ATO」(byte25 bit0 = 1)。
     * 条件：信号已授权发车、门关好、有效 MA、未激活 ATO、未紧急制动。
     */
    public boolean isAtoReady(String trainId) {
        return "NONE".equals(atoReadinessBlockingReason(trainId));
    }

    /**
     * One externally visible workflow state for the signal page and the cab
     * diagnostic API. It deliberately derives ATO_READY from the exact same
     * predicate as the physical ready lamp, so a stale bridge authorization
     * can never make the UI advertise a start that the MA would reject.
     */
    public String atoWorkflowState(String trainId) {
        Protocol704VehicleControlBridge.ControlStateSnapshot control = vehicleBridge.snapshot(trainId);
        if (control == null || control.state() == null) return "NO_ACTIVE_SIMULATION";
        if (control.emergencyLatched()) return "ATP_EMERGENCY_BRAKE";
        if (control.atoRunning()) return "ATO_RUNNING";

        return switch (atoReadinessBlockingReason(trainId)) {
            case "NONE" -> "ATO_READY";
            case "KEY_SWITCH_OFF" -> "KEY_SWITCH_OFF";
            case "KEY_CYCLE_INCOMPLETE" -> "KEY_CYCLE_INCOMPLETE";
            case "DIRECTION_NOT_FORWARD" -> "DIRECTION_NOT_FORWARD";
            case "MASTER_HANDLE_NOT_ZERO" -> "MASTER_HANDLE_NOT_ZERO";
            case "DOORS_OPEN" -> "DOORS_OPEN";
            case "DOOR_CYCLE_REQUIRED" -> "DOOR_CYCLE_REQUIRED";
            case "TRAIN_NOT_STOPPED" -> "TRAIN_NOT_STOPPED";
            case "SIGNAL_DEPARTURE_NOT_AUTHORIZED" -> "WAITING_SIGNAL_AUTHORIZATION";
            case "MOVEMENT_AUTHORITY_UNAVAILABLE", "MA_DOES_NOT_REACH_NEXT_STATION" ->
                    "WAITING_MOVEMENT_AUTHORITY";
            default -> "WAITING_SIGNAL_AUTHORIZATION";
        };
    }
}
