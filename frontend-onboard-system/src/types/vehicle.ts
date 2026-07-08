// 郭逸晨车载模块（成员三）——车辆仿真相关类型定义
//
// 对应后端 backend/src/main/java/com/bjtu/railtransit/vehicle/dto 与 enums 下的
// SimulationResult / TrainState / SimulationSummary / StopResult / SafetyEvent / SimulationPhase。
//
// 注意：后端 SimulationPhase 通过 @JsonValue 输出的实际 JSON 字符串是小写
// （traction/coast/braking/stopped/dwell），因此这里将 phase 类型定义为 string，
// 页面展示时按大小写不敏感方式映射为中文，兼容后端未来调整大小写的情况。

/** 单个仿真时间点的列车状态。 */
export interface TrainState {
  /** 仿真时刻，单位 s（多站时全程连续递增）。 */
  time: number;
  /** 列车当前位置（本次区间内相对位置，0 → runDistanceM），单位 m。DriverCabView 继续使用此字段。 */
  position: number;
  /** 当前速度，单位 m/s。 */
  velocity: number;
  /** 当前加速度，单位 m/s2（制动时为负值）。 */
  acceleration: number;
  /** 当前运行阶段，后端枚举序列化字符串（traction/coast/braking/stopped/dwell）。 */
  phase: string;
  /** 列车编号。 */
  trainId: string;
  /** 多站：全线绝对里程，单位 m（多站时连续递增）。LineRunView 优先使用此字段。 */
  absolutePosition?: number;
  /** 多站：区间编号（0-based，单区间时为 0）。 */
  segmentIndex?: number;
  /** 多站：当前所在或即将到达的站点 id。 */
  stationId?: number;
  /** 多站：当前所在或即将到达的站点中文名。 */
  stationName?: string;
}

/** 仿真总结指标。 */
export interface SimulationSummary {
  /** 全程最大速度，单位 m/s。 */
  maxVelocity: number;
  /** 全程总用时，单位 s。 */
  totalTime: number;
  /** 终态位置（相对坐标），单位 m。 */
  finalPosition: number;
  /** 线路限速，单位 m/s（假设值 20.0 m/s）。 */
  speedLimit: number;
  /** 后端仿真采样步长，单位 s。前端播放 interval = dtPerFrame * 1000 / speedMultiplier。 */
  dtPerFrame: number;
  /** 起始站真实线路绝对里程，单位 m（= fromStation.km * 1000）。 */
  lineStartPosition: number;
  /** 终止站真实线路绝对里程，单位 m（= toStation.km * 1000）。 */
  lineTargetPosition: number;
  /** 起始站中文名。 */
  fromStationName: string;
  /** 终止站中文名。 */
  toStationName: string;
  // ---- 多站连续仿真新增字段 ----
  /** 起始站 id。 */
  fromStationId?: number;
  /** 终止站 id。 */
  toStationId?: number;
  /** 总经停站数（含起点和终点）。 */
  totalStations?: number;
  /** 已完成停车的站数。 */
  completedStops?: number;
  /** 所有中间站驻留时间之和，单位 s。 */
  totalDwellTime?: number;
  // ---- 驾驶模式状态机字段（control 接口返回时填充）----
  /** 续算结束后的驾驶模式（control 接口返回时填充，run 接口返回时为 null）。 */
  currentMode?: DrivingMode | null;
  /** 下一步允许切换的驾驶模式建议（EMERGENCY 停稳后建议 MANUAL）。 */
  nextMode?: DrivingMode | null;
}

/** 自动停站结果。 */
export interface StopResult {
  /** 目标停车点位置（相对坐标），单位 m。 */
  targetStopPosition: number;
  /** 实际停车点位置（相对坐标），单位 m。 */
  actualStopPosition: number;
  /** 停站误差，单位 m。 */
  stopError: number;
  /** 是否成功。 */
  success: boolean;
  /** 失败原因或风险提示，成功时可能为 null。 */
  reason: string | null;
  /** 停车窗到位状态：in_window/overshoot/undershoot/not_accurate。 */
  stopWindowState: string;
  /** 制动触发位置，单位 m */
  brakeTriggerPosition?: number;
  /** 预测停车位置，单位 m */
  predictedStopPosition?: number;
}

/** SafetyGuard 安全事件。 */
export interface SafetyEvent {
  /** 触发原因（OVERSPEED / DRIVER_EMERGENCY_BRAKE / EMERGENCY_MODE_ENGAGED 等）。 */
  reason: string;
  /** 触发时刻，单位 s。 */
  time: number;
  /** 触发位置，单位 m。 */
  position: number;
  /** 触发速度，单位 m/s。 */
  velocity: number;
  /** 采取的保护动作（brake / emergency_brake）。 */
  action: string;
}

/** 多站连续仿真中单个站的停车记录。 */
export interface StationStop {
  stationId: number;
  stationName: string;
  arrivalTime: number;
  departureTime: number;
  dwellTime: number;
  stopError: number;
  inWindow: boolean;
  targetAbsolutePosition: number;
  actualAbsolutePosition: number;
}

/** 一次仿真的完整返回结果，对应 POST /api/vehicle/simulation/run 的 data 字段。 */
export interface SimulationResult {
  states: TrainState[];
  summary: SimulationSummary;
  stopResult: StopResult;
  safetyEvents: SafetyEvent[];
  /** 多站连续仿真各站停车记录（单区间时为空数组）。 */
  stationStops?: StationStop[];
}

/** POST /api/vehicle/simulation/run 的完整响应结构。 */
export interface SimulationRunResponse {
  success: boolean;
  message: string;
  data: SimulationResult;
}

/** 阶段4B：站点类型（对应 configs/line-profile.json stations 条目）。 */
export interface StationOption {
  id: number;
  name: string;
  code: string;
  km: number;
}

/** POST /api/vehicle/simulation/run 可选请求体。 */
export interface SimulationRunRequest {
  fromStationId?: number;
  toStationId?: number;
  /** 中间站驻留时间，单位 s（null 时后端默认 30s）。 */
  dwellTimeSeconds?: number;
}

/** 驾驶模式枚举（本轮新增）。 */
export type DrivingMode = 'ato' | 'manual' | 'emergency';

/** POST /api/vehicle/simulation/control 请求体。 */
export interface SimulationControlRequest {
  fromStationId: number;
  toStationId: number;
  currentState: TrainState;
  currentMode: DrivingMode;
  controlCommand: {
    command: string;      // traction / coast / brake / emergency_brake
    targetDecel: number;  // 目标减速度，单位 m/s2
  };
}
