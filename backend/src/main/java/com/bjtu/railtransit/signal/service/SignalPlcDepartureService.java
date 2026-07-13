package com.bjtu.railtransit.signal.service;

import com.bjtu.railtransit.dispatch.CommandBus;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.bjtu.railtransit.vehicle.protocol704.Protocol704VehicleControlBridge;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

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

    /** 停站开门后撤销发车授权时，熄灭 ATO 就绪灯。 */
    public void onDepartureRevoked(String trainId) {
        if (trainId == null || trainId.isBlank()) return;
        if (!vehicleBridge.isLaboratoryControlEnabled(trainId)) return;
        vehicleBridge.syncDepartureAuth(trainId, false);
    }

    /**
     * 是否应向 PLC 写「具备 ATO」(byte25 bit0 = 1)。
     * 条件：信号已授权发车、门关好、有效 MA、未激活 ATO、未紧急制动。
     */
    public boolean isAtoReady(String trainId) {
        Protocol704VehicleControlBridge.ControlStateSnapshot control = vehicleBridge.snapshot(trainId);
        if (control == null || control.state() == null) return false;
        if (control.emergencyLatched()) return false;
        if (!control.doorsClosed()) return false;
        if ("ATO".equalsIgnoreCase(control.mode())) return false;
        if (control.state().getVelocity() > STOPPED_SPEED_MPS) return false;

        boolean signalAuthorized = commandBus.hasOpenCommand(trainId, "DEPART")
                || control.departureAuthorized();
        if (!signalAuthorized) return false;

        MovingAuthority ma = maRegistry.get(trainId);
        if (ma == null) return false;
        double position = control.state().getAbsolutePosition() != null
                ? control.state().getAbsolutePosition()
                : control.state().getPosition();
        return ma.getEndOfAuthorityM() > position;
    }
}
