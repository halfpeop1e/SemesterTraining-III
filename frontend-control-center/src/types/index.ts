// ==================== 仿真日志 ====================
export interface SimulationLog {
  trainId: number;
  timestamp: number;        // ms
  speed: number;            // m/s
  position: number;         // m
  direction: string;        // "up" | "down"
  loadWeight: number;       // kg
  tractionForce: number;    // N
  brakeForce: number;       // N
  tractiveBrakeCmd: string; // "traction" | "brake" | "coast"
  tractiveBrakePercent: number; // 0-100
  emergencyBrake: boolean;
  availableTractionCount: number;
  availableBrakeCount: number;
  faultSpeedLimit: number;  // m/s
  drivingMode: string;
  currentSegId: number;
}

// ==================== 能耗 ====================
export interface EnergyRecord {
  trainId: number;
  totalTractionEnergyKwh: number;
  totalRegenEnergyKwh: number;
  netEnergyKwh: number;
  maxTractionPowerKw: number;
  avgTractionPowerKw: number;
}

export interface PeakPowerResult {
  maxPeakKw: number;
  timeOfPeak: number;       // ms
  vehiclesAtPeak: number[];
  riskLevel: string;        // "safe" | "warning" | "danger"
}

// ==================== 评估指标 ====================
export interface StopErrorResult {
  stationId: number;
  stationName: string;
  direction: string;
  targetPosition: number;
  actualPosition: number;
  error: number;
  status: string;           // "in_window" | "over" | "under"
}

export interface StationDelay {
  stationId: number;
  stationName: string;
  plannedArrival: number;   // s
  actualArrival: number;    // s
  delay: number;            // s
}

export interface PunctualityResult {
  avgDelay: number;
  maxDelay: number;
  punctualityRate: number;  // 0-1
  delayPerStation: StationDelay[];
}

export interface ComfortResult {
  maxAcceleration: number;  // m/s²
  maxDeceleration: number;  // m/s²
  maxJerk: number;          // m/s³
  comfortScore: number;     // 0-100
  comfortLevel: string;     // "excellent" | "good" | "acceptable" | "poor"
}

export interface SafetyEvent {
  trainId: number;
  timestamp: number;
  eventType: string;        // "over_speed" | "eb_triggered" | "brake_insufficient" | "degraded_mode"
  description: string;
  speedAtEvent: number;
  positionAtEvent: number;
}

// ==================== 综合报告 ====================
export interface EvaluationReport {
  scenarioName: string;
  simulationDuration: number;
  energyRecords: EnergyRecord[];
  peakPowerResult: PeakPowerResult;
  powerRiskLevel: string;
  powerSupplyThreshold: number;
  stopErrors: StopErrorResult[];
  punctuality: PunctualityResult;
  comfort: ComfortResult;
  safetyEvents: SafetyEvent[];
  summary: Record<string, number>;
}

// ==================== 请求类型 ====================
export interface EnergyCalculateRequest {
  scenarioName: string;
  simulationLogs: SimulationLog[];
  tractionEfficiency: number;
  regenEfficiency: number;
  powerSupplyThreshold: number;
}

export interface EvaluationRequest {
  scenarioName: string;
  simulationLogs: SimulationLog[];
  stationPositions: Record<number, number>;
  stationNames: Record<number, string>;
  plannedArrivals: Record<number, number>;
  stationDirections: Record<number, string>;
  speedLimits: Record<number, number>;
  stopWindowTolerance: number;
  punctualityTolerance: number;
}

// ==================== API 响应 ====================
export interface ApiResponse<T> {
  success: boolean;
  message: string;
  data: T;
}
