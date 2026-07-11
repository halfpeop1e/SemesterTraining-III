// ===== 枚举（对应后端 signal/domain 枚举） =====
export type Direction = 'UP' | 'DOWN' | 'INVALID';

export type AuthorityBasis =
  | 'LINE_LIMIT' | 'TSR' | 'PRECEDING_TRAIN' | 'SWITCH' | 'TURNBACK_END'
  | 'SIGNAL' | 'ROUTE_END' | 'OVERLAP_END' | 'AXLE_OCCUPIED';

export type SignalEvent =
  | 'NONE' | 'PRECEDING_OCCUPATION' | 'SPEED_RESTRICTION' | 'SWITCH_ABNORMAL'
  | 'MA_EXPIRED' | 'DEGRADED' | 'SIGNAL_BOUNDARY' | 'ROUTE_BLOCKED'
  | 'AXLE_OCCUPIED' | 'POSITION_LOSS';

export type SignalAspect =
  | 'RED' | 'YELLOW' | 'RED_YELLOW' | 'GREEN' | 'YELLOW_DARK'
  | 'RED_DARK' | 'GREEN_DARK' | 'WHITE' | 'BLUE'
  | 'RED_BROKEN' | 'GREEN_BROKEN' | 'YELLOW_BROKEN' | 'WHITE_BROKEN';

export type SwitchState = 'NORMAL' | 'REVERSE' | 'FAIL';

export type HealthStatus = 'HEALTHY' | 'DEGRADED' | 'FAULT';

export type EventLevel = 'INFO' | 'WARN' | 'ERROR';

export type EventCategory = 'MA' | 'SWITCH' | 'SIGNAL' | 'TSR' | 'TRAIN' | 'SYSTEM';

// ===== API 响应（对应后端 common.ApiResponse） =====
export interface ApiResponse<T> {
  success: boolean;
  message: string;
  data: T;
}

// ===== 运行时数据（对应后端 signal/domain） =====
export interface TrainState {
  trainId: string;
  positionM: number;       // 车头里程 m
  speedKmh: number;
  accelerationMps2: number;
  lengthM: number;
  direction: Direction;
  timestamp: number;       // 仿真时刻 s
  /** 故障限速 km/h；省略/undefined = 无故障限速（协议 A6） */
  faultSpeedLimitKmh?: number;
  /** 定位丢失（协议 A9） */
  positionLost?: boolean;
  /** 完整性丢失（协议 A10） */
  integrityLost?: boolean;
}

export interface MovingAuthority {
  trainId: string;
  endOfAuthorityM: number;      // 授权终点里程 m
  maxSpeedKmh: number;          // 该授权下最大速度
  basis: AuthorityBasis;        // 哪条约束最紧
  timestamp: number;
  event: SignalEvent;           // 伴随事件/降级标记
  capSignalId: number | null;   // 截断 EoA 的信号机
  routeId: number | null;       // 授权所沿进路
}

// ===== MA 请求（对应后端 signal/web/MaRequest） =====
export interface MaRequest {
  lineProfile: LineProfile;
  trains: TrainState[];
  routes?: Record<string, Route>;
  nowSec?: number;
}

// ===== 静态拓扑（对应后端 signal/model） =====
export interface LineProfile {
  lineId: string;
  name: string;
  stations: Station[];
  platforms: Platform[];
  segments: TrackSegment[];
  switches: Switch[];
  turnbacks: Turnback[];
  tsrs: TemporarySpeedRestriction[];
  staticSpeedRestrictions: StaticSpeedRestriction[];
  gradients: Gradient[];
  signals: Signal[];
  balises: Balise[];
  axleSections: AxleCounterSection[];
  logicalSections: LogicalSection[];
  routes: Route[];
  overlaps: OverlapSection[];
  physicalSections: PhysicalSection[];
  totalLengthM: number;
  segmentMileage: Record<string, [number, number]>; // segId → [startM, endM]
}

export interface Station {
  id: string;
  name: string;
  positionM: number;
  isTerminal: boolean;
  platformLengthM: number;
  platformIds: number[];
}

export interface Platform {
  id: number;
  chainage: string;
  centerM: number;
  segId: number;
  dir: number;
  triggerAxleSectionIds: number[];
  clearPass: boolean;
}

export interface Signal {
  id: number;
  name: string;
  type: number;
  attr: number;
  segId: number;
  offsetCm: number;
  protectDir: number;
  aspect: SignalAspect | null;
}

export interface Switch {
  id: string;
  positionM: number;
  normalSegId: number;
  reverseSegId: number;
  mergeSegId: number;
  linkedSwitchId: number;
  state: SwitchState | null;
  divergingSpeedLimitKmh: number;
}

export interface Gradient {
  id: number;
  startSegId: number;
  startOffsetCm: number;
  endSegId: number;
  endOffsetCm: number;
  permille: number;
  tiltDir: number;
  verticalCurveRadiusCm: number;
}

export interface TemporarySpeedRestriction {
  id?: string;
  startM: number;
  endM: number;
  speedLimitKmh: number;
  active: boolean;
}

export interface StaticSpeedRestriction {
  id: number;
  segId: number;
  startOffsetCm: number;
  endOffsetCm: number;
  switchId: number | null;
  speedLimitCmps: number;
  speedLimitKmh: number;
}

export interface Turnback {
  id: string;
  positionM: number;
  type: string;
}

export interface TrackSegment {
  id: string;
  lengthCm: number;
  lengthM?: number; // 兼容后端若已转换
  forwardStartSegId: number;
  sideStartSegId: number;
  forwardEndSegId: number;
  sideEndSegId: number;
  zcZoneId: number;
  atsZoneId: number;
  ciZoneId: number;
}

export interface Balise {
  id: number;
  [key: string]: unknown;
}

export interface AxleCounterSection {
  id: string;
  occupied?: boolean;
  segId?: number;
  [key: string]: unknown;
}

export interface LogicalSection {
  id: string;
  [key: string]: unknown;
}

export interface Route {
  id: number | string;
  name?: string;
  startSignalId: number;
  endSignalId: number;
  axleSectionIds?: number[];
  overlapIds?: number[];
  pathSegIds?: number[];
  built?: boolean;
  cancelled?: boolean;
  [key: string]: unknown;
}

export interface OverlapSection {
  id: string;
  [key: string]: unknown;
}

export interface PhysicalSection {
  id: string;
  segIds?: number[];
  [key: string]: unknown;
}

// ===== Dashboard / 状态 =====
export interface SignalStatus {
  onlineTrains: number;
  alertCount: number;
  health: HealthStatus;
  simulationTime: number; // s
}

export interface SignalEventItem {
  id: string;
  timestamp: number; // ms
  level: EventLevel;
  category: EventCategory;
  message: string;
  sourceId?: string;
}

// ===== 控制请求 =====
export interface SwitchOperateRequest {
  switchId: string;
  position: SwitchState;
}

export interface RouteBuildRequest {
  routeId: number;
}

export interface SignalOpenRequest {
  signalId: number;
  aspect?: SignalAspect;
}

export interface TsrSetRequest {
  startM: number;
  endM: number;
  speedLimitKmh: number;
  active: boolean;
}

export interface ControlResult {
  success: boolean;
  message: string;
}
