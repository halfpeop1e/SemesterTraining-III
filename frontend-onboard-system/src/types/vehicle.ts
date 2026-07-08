// 郭逸晨车载模块（成员三）——车辆仿真相关类型定义
//
// 对应后端 backend/src/main/java/com/bjtu/railtransit/vehicle/dto 与 enums 下的
// SimulationResult / TrainState / SimulationSummary / StopResult / SafetyEvent / SimulationPhase。
//
// 注意：后端 SimulationPhase 通过 @JsonValue 输出的实际 JSON 字符串是小写
// （traction/coast/braking/stopped），因此这里将 phase 类型定义为 string，
// 页面展示时按大小写不敏感方式映射为中文，兼容后端未来调整大小写的情况。

/** 单个仿真时间点的列车状态。 */
export interface TrainState {
  /** 仿真时刻，单位 s。 */
  time: number;
  /** 列车当前位置，单位 m。 */
  position: number;
  /** 当前速度，单位 m/s。 */
  velocity: number;
  /** 当前加速度，单位 m/s2（制动时为负值）。 */
  acceleration: number;
  /** 当前运行阶段，后端枚举序列化字符串（如 traction/coast/braking/stopped）。 */
  phase: string;
  /** 列车编号。 */
  trainId: string;
}

/** 仿真总结指标。 */
export interface SimulationSummary {
  /** 全程最大速度，单位 m/s。 */
  maxVelocity: number;
  /** 全程总用时，单位 s。 */
  totalTime: number;
  /** 终态位置，单位 m。 */
  finalPosition: number;
}

/** 自动停站结果。 */
export interface StopResult {
  /** 目标停车点位置，单位 m。 */
  targetStopPosition: number;
  /** 实际停车点位置，单位 m。 */
  actualStopPosition: number;
  /** 停站误差，单位 m。 */
  stopError: number;
  /** 是否成功。 */
  success: boolean;
  /** 失败原因或风险提示，成功时可能为 null。 */
  reason: string | null;
}

/** SafetyGuard 安全事件（阶段 1A 恒为空数组）。 */
export interface SafetyEvent {
  /** 触发原因。 */
  reason: string;
  /** 触发时刻，单位 s。 */
  time: number;
  /** 触发位置，单位 m。 */
  position: number;
  /** 触发速度，单位 m/s。 */
  velocity: number;
  /** 采取的保护动作。 */
  action: string;
}

/** 一次仿真的完整返回结果，对应 POST /api/vehicle/simulation/run 的 data 字段。 */
export interface SimulationResult {
  states: TrainState[];
  summary: SimulationSummary;
  stopResult: StopResult;
  safetyEvents: SafetyEvent[];
}

/** POST /api/vehicle/simulation/run 的完整响应结构，即 ApiResponse<SimulationResult>。 */
export interface SimulationRunResponse {
  success: boolean;
  message: string;
  data: SimulationResult;
}
