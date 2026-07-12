package com.bjtu.railtransit.vehicle.protocol704;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** Deferred where a versioned PLC/device contract is genuinely absent or on-site verification is pending. */
class Protocol704ExternalContractTest {
    @Test @Disabled("缺少内容: PLC sequence/replay 字段；提供方: 设备/协议方；启用条件: 版本化字段合同；未来验证: 重放与乱序帧拒绝")
    void plcSequenceAndReplayProtectionRequiresVersionedProtocolContract() { }

    @Test @Disabled("缺少内容: 设备 ACK 编码与时序；提供方: PLC 协议方；启用条件: ACK 合同；未来验证: 命令关联确认")
    void plcAcknowledgementRequiresDeviceAckContract() { }

    @Test @Disabled("编码已就绪: Protocol704FrameEncoder 支持 26B/28B 两种格式；待办: 台架现场验收确认 PLC 回显速度字段")
    void vehicleStateOutboundFrameRequiresApprovedEncodingContract() { }

    @Test @Disabled("位域已解析: Protocol704FrameParser 覆盖 byte34/byte25 全部 ATO 模式位；待办: 老师确认位语义与设备行为一致")
    void atoModeSelectionRequiresApprovedPlcFieldDefinition() { }

    @Test @Disabled("入站已解析 byte29 车门开关位；缺少内容: 出站车门控制命令字段定义；提供方: PLC 协议方；启用条件: 安全联锁合同；未来验证: 车门命令闭环")
    void doorControlRequiresApprovedPlcFieldDefinition() { }
}
