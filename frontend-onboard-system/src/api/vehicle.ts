// 郭逸晨车载模块（成员三）——车辆仿真 API 调用

import type {
  SidingStatus,
  SimulationControlRequest,
  SimulationResult,
  SimulationRunRequest,
  SimulationRunResponse,
} from '../types/vehicle';

const API_BASE = '/api';
const VEHICLE_RUN_TIMEOUT_MS = 15_000;

export interface DispatchCommand {
  commandId: string; trainId: string; commandType: string; targetValue: number;
  reason: string; status: string; source: string;
}
export interface TimetableEntry {
  stationId: number;
  stationName: string;
  stationIndex: number;
  stationKm: number;
  plannedArrival: number;
  plannedDeparture: number;
  plannedDwell: number;
}

export interface SignalStationArrival {
  trainId: string;
  stationName: string;
  stationIndex: number;
  arrivalTimeSeconds: number;
  departureTimeSeconds: number;
  dwellSeconds: number;
  plannedArrivalSeconds: number;
  plannedDepartureSeconds: number;
  plannedDwellSeconds: number;
  arrivalDeviation: number;
  departureDeviation: number;
}

export interface OnboardSnapshot {
  currentTimeSeconds: number;
  train: {
    trainId: string;
    positionMeters: number;
    speedKmh: number;
    accelerationMps2: number;
    trainLengthMeters: number;
    direction: string;
    drivingMode: string;
    status: string;
    currentStationId?: string;
    nextStationId?: string;
    faultSpeedLimitKmh?: number | null;
    positionLost?: boolean;
    integrityLost?: boolean;
    loadFactor?: number | null;
  } | null;
  commands: DispatchCommand[];
  movementAuthorityMeters: number;
  speedLimitKmh: number;
  communicationStatus: string;
  safetyStatus: string;
  timetable: TimetableEntry[];
  stationArrivals?: SignalStationArrival[];
  signalSource?: string;
}

export interface SignalManagedTrain {
  trainId: string;
  direction: 'UP' | 'DOWN';
  status: string;
  currentStationIndex: number;
  nextStationIndex: number;
  originStationId?: number;
  destinationStationId?: number;
  drivingMode?: string;
}

async function integrationRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: { 'Content-Type': 'application/json' }, ...init,
  });
  const body = await response.json();
  if (!response.ok || !body.success) throw new Error(body.message || `HTTP ${response.status}`);
  return body.data as T;
}

export const getOnboardSnapshot = (trainId: string) =>
  integrationRequest<OnboardSnapshot>(`/signal/train/${trainId}/snapshot`);
export const getSignalManagedTrains = async () => {
  const snapshot = await integrationRequest<{ trains?: SignalManagedTrain[] }>('/simulations/snapshot');
  return snapshot.trains ?? [];
};
export const ackDispatchCommand = (commandId: string, executionStatus = 'EXECUTING') =>
  integrationRequest<DispatchCommand>('/dispatch/commands/ack', {
    method: 'POST', body: JSON.stringify({ commandId, accepted: true, executionStatus }),
  });
export const reportOnboardStatus = (payload: object) =>
  integrationRequest<string>('/signal/report/status', { method: 'POST', body: JSON.stringify(payload) });
export const reportOnboardEvent = (payload: object) =>
  integrationRequest<string>('/dispatch/report/event', { method: 'POST', body: JSON.stringify(payload) });

export async function runVehicleSimulation(
  request?: SimulationRunRequest,
): Promise<SimulationResult> {
  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), VEHICLE_RUN_TIMEOUT_MS);
  try {
    const res = await fetch(`${API_BASE}/vehicle/simulation/run`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: request ? JSON.stringify(request) : undefined,
      signal: controller.signal,
    });
    if (!res.ok) throw new Error(`车辆仿真接口调用失败: ${res.status}`);
    const body: SimulationRunResponse = await res.json();
    if (!body.success) throw new Error(body.message || '车辆仿真接口返回失败');
    return body.data;
  } catch (error) {
    if (controller.signal.aborted) {
      throw new Error('车辆上线等待后端响应超过 15 秒。请确认后端已启动，再重新上线。');
    }
    throw error;
  } finally {
    window.clearTimeout(timeoutId);
  }
}

export interface ManualRequestPending {
  status: 'PENDING_APPROVAL';
  message: string;
  commandId?: string;
  trainId?: string;
}

/**
 * 驾驶员控制续算。
 * request.currentState.position 必须是全程累积里程。
 * request.totalTargetPosition 是本次续算目标站累计里程（通常是下一未到达站）。
 * set_manual 在 ATO 会话中可能返回 PENDING_APPROVAL（中控审批中）。
 */
export async function callVehicleControl(
  request: SimulationControlRequest,
): Promise<SimulationResult | ManualRequestPending> {
  const res = await fetch(`${API_BASE}/vehicle/simulation/control`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  });
  if (!res.ok) throw new Error(`控制接口调用失败: ${res.status}`);
  const body = await res.json();
  if (!body.success) throw new Error(body.message || '控制接口返回失败');
  if (body.data && body.data.status === 'PENDING_APPROVAL') {
    return body.data as ManualRequestPending;
  }
  return body.data as SimulationResult;
}

export async function applyManualDecision(
  trainId: string,
  decision: 'MANUAL_APPROVED' | 'MANUAL_REJECTED' | 'APPROVED' | 'REJECTED',
): Promise<{ status: string; mode?: string }> {
  const res = await fetch(`${API_BASE}/vehicle/simulation/manual-decision`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ trainId, decision }),
  });
  const body = await res.json();
  if (!res.ok || !body.success) throw new Error(body.message || '人工接管审批应用失败');
  return body.data;
}

export async function resetVehicleSimulation(
  trainId: string,
  positionMeters = 0,
): Promise<{ status: string; eventType?: string; positionMeters?: number }> {
  const res = await fetch(`${API_BASE}/vehicle/simulation/reset`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ trainId, positionMeters }),
  });
  const body = await res.json();
  if (!res.ok || !body.success) throw new Error(body.message || '车载复位失败');
  return body.data;
}

export const getOnboardCommands = (trainId: string) =>
  integrationRequest<DispatchCommand[]>(`/onboard/${trainId}/commands`);

export async function confirmVehicleDeparture(trainId: string): Promise<{ status: string; departureState?: string }> {
  const res = await fetch(`${API_BASE}/vehicle/simulation/depart-confirm`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ trainId }),
  });
  const body = await res.json();
  if (!res.ok || !body.success) throw new Error(body.message || '车载发车确认失败');
  return body.data;
}

/**
 * 查询全部 13 站侧线占用状态（数据源 GET /api/dispatch/siding/all）。
 * 由父组件（Vehicle/index.tsx）统一轮询后下发给 2D LineRunView 与 3D ThreeRailwayView，
 * 保证两个视图共享同一份实时状态、无需刷新页面即可联动颜色。
 * 字段名 status 与后端 SidingStatus.getStatus() 序列化契约一致（不得改为 state）。
 */
export async function getAllSidingStatuses(): Promise<SidingStatus[]> {
  const res = await fetch(`${API_BASE}/dispatch/siding/all`);
  if (!res.ok) throw new Error(`侧线状态接口失败: ${res.status}`);
  const body = await res.json();
  if (!body || !body.success) throw new Error(body?.message || '侧线状态接口返回失败');
  return body.data as SidingStatus[];
}
