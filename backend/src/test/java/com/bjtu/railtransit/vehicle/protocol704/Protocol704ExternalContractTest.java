package com.bjtu.railtransit.vehicle.protocol704;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** Deferred only where a versioned PLC/device contract is genuinely absent. */
class Protocol704ExternalContractTest {
    @Test @Disabled("缺少内容: PLC sequence/replay 字段；提供方: 设备/协议方；启用条件: 版本化字段合同；未来验证: 重放与乱序帧拒绝")
    void plcSequenceAndReplayProtectionRequiresVersionedProtocolContract() { }

    @Test @Disabled("缺少内容: 设备 ACK 编码与时序；提供方: PLC 协议方；启用条件: ACK 合同；未来验证: 命令关联确认")
    void plcAcknowledgementRequiresDeviceAckContract() { }

    @Test @Disabled("缺少内容: 车辆状态出站帧编码；提供方: PLC 协议方；启用条件: 审批编码合同；未来验证: 状态帧互操作")
    void vehicleStateOutboundFrameRequiresApprovedEncodingContract() { }

    @Test @Disabled("缺少内容: ATO 模式选择字段定义；提供方: PLC 协议方；启用条件: 位域和语义获批；未来验证: ATO 选择仲裁")
    void atoModeSelectionRequiresApprovedPlcFieldDefinition() { }

    @Test @Disabled("缺少内容: 车门控制字段定义；提供方: PLC 协议方；启用条件: 安全联锁合同；未来验证: 车门命令闭环")
    void doorControlRequiresApprovedPlcFieldDefinition() { }
}
