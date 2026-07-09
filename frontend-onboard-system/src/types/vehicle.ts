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
  // 驾驶模式（/control 接口返回时填充）
  currentMode?: DrivingMode | null;
  nextMode?: DrivingMode | null;
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

export interface SimulationRunRequest {
  fromStationId?: number;
  toStationId?: number;
  dwellTimeSeconds?: number;
}

/** 驾驶模式（本轮新增）。 */
export type DrivingMode = 'ato' | 'manual' | 'emergency';

/** POST /api/vehicle/simulation/control 请求体。 */
export interface SimulationControlRequest {
  fromStationId: number;
  toStationId: number;
  /** currentState.position 必须是从 fromStation 起的全程累积里程。 */
  currentState: TrainState;
  currentMode: DrivingMode;
  controlCommand: {
    command: string;      // traction / coast / brake / emergency_brake
    targetDecel: number;  // m/s2
  };
  /** 本次仿真从 fromStation 到 toStation 的总目标距离，= stopResult.targetStopPosition。 */
  totalTargetPosition: number;
}
