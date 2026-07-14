// 郭逸晨车载模块（成员三）——704协议对接类型定义

export interface Protocol704PortStatus {
  port: number;
  host?: string;
  channel?: string;
  connected: boolean;
  connecting: boolean;
  reconnecting?: boolean;
  lastConnectSuccessTime?: number;
  lastDisconnectTime?: number;
  lastReceiveTime?: number;
  lastFrameIntervalMs?: number;
  lastFrameLength?: number;
  bytesReceived?: number;
  frameCount?: number;
  lastError?: string;
  inputState?: Protocol704InputState;
  inputDiagnostic?: string;
  lastInputHeader?: string;
  lastInputTotalLength?: number;
  lastInputDataLength?: number;
  pendingInputBytes?: number;
  bytesSent?: number;
  lastSendTime?: number;
  lastOutputHex?: string;
  lastOutputTime?: number;
  outputFrameCount?: number;
  outputErrorCount?: number;
  lastOutputError?: string;
}

export type Protocol704InputState =
  | 'TCP_NOT_CONNECTED'
  | 'TCP_CONNECTING'
  | 'CHANNEL_DISABLED'
  | 'CONNECTED_NO_BYTES'
  | 'PARTIAL_FRAME'
  | 'HEADER_MISMATCH'
  | 'TOTAL_LENGTH_MISMATCH'
  | 'DATA_LENGTH_MISMATCH'
  | 'STRUCTURE_INVALID'
  | 'FIRST_FRAME_RECEIVED'
  | 'FRAME_RECEIVED';

export interface MappedControlCommand704 {
  command: string;
  levelPercent: number;
  targetDecel: number;
  verified: boolean;
  note: string;
  triggerByteOffset?: number;
  triggerByteValue?: number;
  directionHandle?: number;
  masterHandle?: number;
  tractionLevelRaw?: number;
  brakeLevelRaw?: number;
  direction?: string;
}

export interface RealtimeVehicleState704 {
  trainId: string;
  lastUpdateTime: number;
  positionM: number;
  velocityMs: number;
  accelerationMs2: number;
  mode: string;
  lastCommand: string;
  note: string;
  doorsClosed?: boolean;
  phase?: string;
  departureState?: string;
  emergencyLatched?: boolean;
}

export interface Protocol704LogEntry {
  trainId: string;
  port: number;
  direction: string;
  timestamp: number;
  timestampFormatted: string;
  rawHex: string;
  frameLength: number;
  parsedFields?: Record<string, unknown>;
  mappedCommand?: MappedControlCommand704;
  verified: boolean;
  note?: string;
  source?: string;
}

export interface Protocol704CommandLifecycle {
  commandId: string;
  source: 'PLC_704_LOCAL_V1';
  receivedAt: number;
  parsedCommand?: string;
  level: number;
  activeTrainId?: string;
  sessionId?: string;
  previousMode?: string;
  resultMode?: string;
  status: 'RECEIVED' | 'PARSED' | 'VALIDATED' | 'EXECUTED' | 'REJECTED' | 'FAILED';
  rejectionReason?: string;
  executionError?: string;
  departureState?: string;
  executedState?: {
    time: number; position: number; velocity: number; acceleration: number; phase: string; trainId: string;
    absolutePosition?: number;
  };
  /** 命令执行后紧急制动是否锁存（后端 emergencyLatchedAfter）。 */
  emergencyLatchedAfter?: boolean;
  /** 命令触发的完整续算仿真结果（仅 EXECUTED 且命令触发了物理仿真时存在）。
   *  前端用它把 EB 制动轨迹拼接到主 result.states 后面播放。 */
  executedResult?: {
    states: Array<{
      time: number; position: number; velocity: number; acceleration: number;
      phase: string; trainId: string; absolutePosition?: number;
    }>;
    summary: {
      currentMode?: string;
      nextMode?: string;
      [key: string]: unknown;
    };
    stopResult?: { [key: string]: unknown };
    safetyEvents?: Array<{ [key: string]: unknown }>;
  };
}

export interface Parsed704Frame {
  frameLength: number;
  fields?: Record<string, unknown>;
  hasUnverifiedFields: boolean;
  note?: string;
  mappedCommand?: MappedControlCommand704;
  rawSpeedCms?: number;
  rawDistanceCm?: number;
}

export interface Protocol704Status {
  trainId: string;
  host: string;
  ports?: number[];
  portStatuses?: Record<string, Protocol704PortStatus>;
  connected: boolean;
  startTime?: number;
  lastRawHex?: string;
  lastFrameLength?: number;
  lastParsedFrame?: Parsed704Frame;
  lastMappedCommand?: MappedControlCommand704;
  realtimeVehicleState?: RealtimeVehicleState704;
  recentLogs?: Protocol704LogEntry[];
  connectionNote?: string;
  receivedValidFrame?: boolean;
  lastValidFrameTime?: number;
  lastCommandLifecycle?: Protocol704CommandLifecycle;
  lastOutputFrame?: string;
  lastOutputHmi?: string;
  activeBinding?: string;
  simulationReady?: boolean;
  simulationReadiness?: string;
  simulationContextUpdatedAt?: number;
}

export interface HilChannelStatus {
  enabled: boolean;
  connected: boolean;
  framesSent: number;
  bytesSent: number;
  lastError?: string | null;
}

export interface HilGatewayStatus {
  enabled: boolean;
  trainId: string;
  signalScreen?: HilChannelStatus;
  networkScreen?: HilChannelStatus;
  vision?: HilChannelStatus;
}
