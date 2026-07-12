// 郭逸晨车载模块（成员三）——704协议对接类型定义

export interface Protocol704PortStatus {
  port: number;
  connected: boolean;
  connecting: boolean;
  lastConnectSuccessTime?: number;
  lastDisconnectTime?: number;
  lastReceiveTime?: number;
  lastFrameIntervalMs?: number;
  lastFrameLength?: number;
  bytesReceived?: number;
  frameCount?: number;
  lastError?: string;
}

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
  activeBinding?: string;
  simulationReady?: boolean;
  simulationReadiness?: string;
  simulationContextUpdatedAt?: number;
}
