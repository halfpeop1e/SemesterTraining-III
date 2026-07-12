// 郭逸晨车载模块（成员三）——车辆仿真相关类型定义
//
// 坐标约定：TrainState.position 是从本次选择的起点站开始的连续累积相对里程，
// 多站时不归零。absolutePosition = summary.lineStartPosition + position。

/** 单个仿真时间点的列车状态。 */
export interface TrainState {
  /** 仿真时刻，单位 s（多站时全程连续递增）。 */
  time: number;
  /**
   * 列车当前位置，单位 m。
   * 从本次选择的 fromStation 开始的连续累积相对里程，多站时不归零。
   * DriverCabView 用此字段计算 distanceToTarget。
   */
  position: number;
  /** 当前速度，单位 m/s。 */
  velocity: number;
  /** 当前加速度，单位 m/s2（制动时为负值）。 */
  acceleration: number;
  /** 当前运行阶段（traction/coast/braking/stopped/dwell）。 */
  phase: string;
  /** 列车编号。 */
  trainId: string;
  /**
   * 全线绝对里程，单位 m（可选）。
   * = summary.lineStartPosition + position。
   * LineRunView 优先使用此字段在全线地图上定位列车。
   */
  absolutePosition?: number;
  /** 当前轮周牵引力 N（正值, 惰行/制动时为0, 后端新增字段）。 */
  tractionForce?: number;
  /** 当前轮周制动力 N（正值, 牵引/惰行时为0, 后端新增字段）。 */
  brakeForce?: number;
  /** 当前可用电机数（默认16, 后端新增字段）。 */
  availableMotors?: number;
}

/** 仿真总结指标。 */
export interface SimulationSummary {
  maxVelocity: number;
  totalTime: number;
  finalPosition: number;
  speedLimit: number;
  dtPerFrame: number;
  lineStartPosition: number;
  lineTargetPosition: number;
  fromStationName: string;
  toStationName: string;
  // 多站汇总字段
  totalStations?: number;
  completedStops?: number;
  departureState?: 'READY_TO_DEPART' | 'RUNNING' | 'STOPPED';
  // 驾驶模式（/control 接口返回时填充）
  currentMode?: DrivingMode | null;
  nextMode?: DrivingMode | null;
  /** 列车总质量 kg（后端新增字段）。 */
  trainMass?: number;
  /** 列车总电机数（后端新增字段）。 */
  totalMotors?: number;
}

/** 自动停站结果。 */
export interface StopResult {
  /** 目标停车点位置（从 fromStation 起的累积相对里程），单位 m。 */
  targetStopPosition: number;
  /** 实际停车点位置（从 fromStation 起的累积相对里程），单位 m。 */
  actualStopPosition: number;
  stopError: number;
  success: boolean;
  reason: string | null;
  stopWindowState: string;
  brakeTriggerPosition?: number;
  predictedStopPosition?: number;
}

/** SafetyGuard 安全事件。 */
export interface SafetyEvent {
  reason: string;
  time: number;
  position: number;
  velocity: number;
  action: string;
}

/** 多站连续仿真中单个站的停车记录。 */
export interface StationStop {
  stationId: number;
  stationName: string;
  segmentStartPosition: number;
  targetPosition: number;
  actualPosition: number;
  stopError: number;
  inWindow: boolean;
  arrivalTime: number;
  dwellTime: number;
}

/** 一次仿真的完整返回结果。 */
export interface SimulationResult {
  states: TrainState[];
  summary: SimulationSummary;
  stopResult: StopResult;
  safetyEvents: SafetyEvent[];
  /** 多站连续仿真各站停车记录（单区间时为空数组）。 */
  stationStops?: StationStop[];
}

export interface SimulationRunResponse {
  success: boolean;
  message: string;
  data: SimulationResult;
}

export interface StationOption {
  id: number;
  name: string;
  code: string;
  km: number;
}

/**
 * 侧线（存车线/折返线）占用状态。数据来源：GET /api/dispatch/siding/{stationId}。
 *
 * 后端 DTO 字段为 `status`（SidingStatus.getStatus()），前端必须读 `status` 与之对齐。
 * 历史上前端曾误用 `state`，导致真实 RESERVED/OCCUPIED 被回退为 AVAILABLE（已修复）。
 */
export interface SidingStatus {
  stationId: number;
  stationName: string;
  /** 侧线占用状态，与后端 SidingStatus.status 字段一致。 */
  status: 'AVAILABLE' | 'OCCUPIED' | 'RESERVED';
  occupiedTrainId: string | null;
}

export interface SimulationRunRequest {
  trainId?: string;
  fromStationId?: number;
  toStationId?: number;
  dwellTimeSeconds?: number;
}

/** 驾驶模式（本轮新增）。 */
export type DrivingMode = 'ato' | 'manual' | 'emergency';

/** POST /api/vehicle/simulation/control 请求体。 */
export interface SimulationControlRequest {
  trainId?: string;
  fromStationId: number;
  toStationId: number;
  /** currentState.position 必须是从 fromStation 起的全程累积里程。 */
  currentState: TrainState;
  currentMode: DrivingMode;
  /** /simulation/control 必填；/protocol704/sync-state 只同步状态不需要此字段。 */
  controlCommand?: {
    // 牵引/惰行/制动/紧急制动；模式恢复：resume_ato（MANUAL→ATO）、reset_emergency（EMERGENCY→MANUAL）
    command: string;      // traction / coast / brake / emergency_brake / resume_ato / reset_emergency
    targetDecel: number;  // m/s2
    levelPercent?: number; // 0~100，牵引/制动级位百分比
    direction?: 'FORWARD' | 'ZERO' | 'REVERSE';
  };
  /**
   * 本次续算目标站累计里程（通常是下一未到达站）。
   * Bug B2 修复：多站仿真中传"下一未到达站的累计里程"，而非末站里程，避免 ATO 跳过中间站。
   * 单区间仿真时仍可传 stopResult.targetStopPosition。
   * /protocol704/sync-state 不需要此字段。
   */
  totalTargetPosition?: number;
  /** 下一目标站 id（前端计算后传入，供后端日志/返回使用）。可选。 */
  nextStationId?: number;
  /** 下一目标站名（前端计算后传入，供后端日志/返回使用）。可选。 */
  nextStationName?: string;
  /** 车载确认发车后才允许仿真进入 RUNNING；sync-state 用此字段同步 Bridge 发车授权。 */
  departureConfirmed?: boolean;
}
